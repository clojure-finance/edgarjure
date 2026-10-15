(ns edgar.forms.form13f
  "Form 13F-HR parser — Institutional Investment Manager Holdings Report.
   Registers filing-obj methods for form types \"13F-HR\" and \"13F-HR/A\".

   Only handles XML-era filings (post-2013 Q2). Earlier filings use
   heterogeneous plain-text infotables and are not supported.

   Usage:
     (require '[edgar.forms.form13f])  ; side-effectful load registers the method
     (edgar.filing/filing-obj filing)  ; => {:form \"13F-HR\" :holdings <dataset> ...}"
  (:require [edgar.filing :as filing]
            [edgar.forms.xml :refer [parse-str find-tag find-tags tag-text
                                     child child-text]]
            [clojure.string :as str]
            [tech.v3.dataset :as ds]))

;;; ---------------------------------------------------------------------------
;;; Submission document locator
;;;
;;; An XML-era 13F-HR submission contains two XML documents:
;;;   - primary_doc.xml  — <edgarSubmission>: header, cover page, summary page
;;;   - an INFORMATION TABLE attachment (any name, e.g. 56757.xml) —
;;;     <informationTable>: one <infoTable> per position
;;; XBRL instance documents (*_htm.xml) are never either of the two.
;;; ---------------------------------------------------------------------------

(defn- information-table? [doc]
  (= "INFORMATION TABLE" (:type doc)))

(defn- fetch-documents
  "Fetch the raw cover (primary_doc.xml) and information-table XML of a 13F
   submission. Returns {:cover <string|nil> :infotable <string|nil>}."
  [filing]
  (let [xml-docs (filter (fn [{:keys [name type]}]
                           (and (str/ends-with? (str name) ".xml")
                                (not (str/ends-with? (str name) "_htm.xml"))
                                (not (str/includes? (str/lower-case (str type)) "xbrl"))))
                         (:files (filing/filing-index filing)))
        cover-doc (first (remove information-table? xml-docs))
        info-doc (or (first (filter information-table? xml-docs))
                     (first (remove #(= % cover-doc) xml-docs)))
        fetch #(when % (filing/filing-document filing (:name %) :raw? true))]
    {:cover (fetch cover-doc)
     :infotable (fetch info-doc)}))

;;; ---------------------------------------------------------------------------
;;; Cover document (primary_doc.xml) parsing
;;;
;;;   edgarSubmission/headerData/submissionType           13F-HR | 13F-HR/A
;;;   edgarSubmission/headerData/filerInfo/periodOfReport MM-DD-YYYY
;;;   edgarSubmission/formData/coverPage/                 reportCalendarOrQuarter,
;;;     isAmendment, amendmentNo, amendmentInfo/amendmentType, filingManager,
;;;     reportType, form13FFileNumber
;;;   edgarSubmission/formData/summaryPage/               otherIncludedManagersCount,
;;;     tableEntryTotal, tableValueTotal, otherManagers2Info
;;; ---------------------------------------------------------------------------

(defn- mdy->iso
  "13F XML dates are MM-DD-YYYY; return ISO yyyy-MM-dd. Anything else is
   returned unchanged."
  [s]
  (if-let [[_ m d y] (some->> s (re-matches #"(\d{2})-(\d{2})-(\d{4})"))]
    (str y "-" m "-" d)
    s))

(defn- parse-long-safe [s]
  (when (not-empty (str/trim (or s "")))
    (try (parse-long (str/replace s "," "")) (catch Exception _ nil))))

(defn- parse-manager [root]
  (let [filer (find-tag root :filingManager)
        addr (find-tag filer :address)]
    {:name (tag-text filer :name)
     :street (tag-text addr :street1)
     :city (tag-text addr :city)
     :state (tag-text addr :stateOrCountry)
     :zip (tag-text addr :zipCode)}))

(defn- parse-cover
  "Header, cover-page and summary-page fields of primary_doc.xml. Cover and
   summary fields are read as direct children: form13FFileNumber also
   appears under each summaryPage/otherManagers2Info entry."
  [root]
  (let [header (find-tag root :headerData)
        cover (find-tag root :coverPage)
        summary (find-tag root :summaryPage)]
    {:submission-type (tag-text header :submissionType)
     :period-of-report (mdy->iso (or (tag-text header :periodOfReport)
                                     (child-text cover :reportCalendarOrQuarter)))
     :report-type (child-text cover :reportType)
     :form13f-file-number (child-text cover :form13FFileNumber)
     :is-amendment? (= "true" (some-> (child-text cover :isAmendment) str/lower-case))
     :amendment-number (parse-long-safe (child-text cover :amendmentNo))
     :amendment-type (child-text (child cover :amendmentInfo) :amendmentType)
     :other-managers-count (parse-long-safe (child-text summary :otherIncludedManagersCount))
     :table-entry-count (parse-long-safe (child-text summary :tableEntryTotal))
     :table-value-total (parse-long-safe (child-text summary :tableValueTotal))}))

;;; ---------------------------------------------------------------------------
;;; Holdings infotable parsing
;;; Each <infoTable> element represents one position.
;;; ---------------------------------------------------------------------------

(defn- parse-holding [entry]
  (let [shrs-prn-amt (find-tag entry :shrsOrPrnAmt)]
    {:name (tag-text entry :nameOfIssuer)
     :cusip (tag-text entry :cusip)
     :title (tag-text entry :titleOfClass)
     :value (parse-long-safe (tag-text entry :value))
     :shares (parse-long-safe (child-text shrs-prn-amt :sshPrnamt))
     :shares-type (child-text shrs-prn-amt :sshPrnamtType)
     :put-call (tag-text entry :putCall)
     :investment-discretion (tag-text entry :investmentDiscretion)
     :other-managers (tag-text entry :otherManager)
     :voting-sole (parse-long-safe (tag-text entry :Sole))
     :voting-shared (parse-long-safe (tag-text entry :Shared))
     :voting-none (parse-long-safe (tag-text entry :None))}))

(defn- parse-holdings [infotable-root]
  (let [entries (find-tags infotable-root :infoTable)]
    (mapv parse-holding entries)))

(defn- holdings->dataset [holdings]
  (if (empty? holdings)
    (ds/->dataset {:name [] :cusip [] :title [] :value []
                   :shares [] :shares-type [] :put-call []
                   :investment-discretion [] :other-managers []
                   :voting-sole [] :voting-shared [] :voting-none []})
    (ds/->dataset holdings)))

;;; ---------------------------------------------------------------------------
;;; Public parse entry point
;;; ---------------------------------------------------------------------------

(defn parse-form13f
  "Parse a 13F-HR or 13F-HR/A filing map into a structured map. Header,
   cover and summary data come from primary_doc.xml, holdings from the
   separate INFORMATION TABLE attachment. Returns nil when the filing has
   neither XML document.

   Dollar amounts (:table-value-total, holdings :value, :total-value) are
   as filed: whole US dollars in filings made from January 3, 2023 onward,
   thousands of US dollars in earlier filings.

   Returns:
     {:form                 \"13F-HR\" | \"13F-HR/A\" (XML submissionType;
                            falls back to the filing's :form)
      :period-of-report     \"YYYY-MM-DD\"
      :report-type          \"13F HOLDINGS REPORT\" | \"13F COMBINATION REPORT\"
      :is-amendment?        boolean
      :amendment-number     long (amendments only)
      :amendment-type       \"RESTATEMENT\" | \"NEW HOLDINGS\" (amendments only)
      :form13f-file-number  \"028-...\"
      :other-managers-count long (other included managers)
      :table-entry-count    long (as reported on the summary page)
      :table-value-total    long (as reported on the summary page)
      :manager              {:name ... :street ... :city ... :state ... :zip ...}
      :holdings             <tech.ml.dataset>
                              columns: :name :cusip :title :value :shares
                                       :shares-type :put-call
                                       :investment-discretion :other-managers
                                       :voting-sole :voting-shared :voting-none
      :total-value          long (sum of the :value column)}"
  [filing]
  (let [{:keys [cover infotable]} (fetch-documents filing)]
    (when (or cover infotable)
      (let [cover-root (some-> cover parse-str)
            cover-data (some-> cover-root parse-cover)
            manager (some-> cover-root parse-manager)
            holdings (if infotable (parse-holdings (parse-str infotable)) [])
            form (or (:submission-type cover-data) (:form filing))]
        (merge
         (dissoc cover-data :submission-type)
         {:form form
          :is-amendment? (boolean (or (:is-amendment? cover-data)
                                      (str/ends-with? (str form) "/A")))
          :manager manager
          :holdings (holdings->dataset holdings)
          :total-value (reduce + 0 (keep :value holdings))})))))

;;; ---------------------------------------------------------------------------
;;; Register filing-obj methods
;;; ---------------------------------------------------------------------------

(defmethod filing/filing-obj "13F-HR" [filing]
  (parse-form13f filing))

(defmethod filing/filing-obj "13F-HR/A" [filing]
  (parse-form13f filing))
