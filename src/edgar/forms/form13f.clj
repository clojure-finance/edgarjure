(ns edgar.forms.form13f
  "Form 13F-HR parser — Institutional Investment Manager Holdings Report.
   Registers a filing-obj method for form type \"13F-HR\".

   Only handles XML-era filings (post-2013 Q2). Earlier filings use
   heterogeneous plain-text infotables and are not supported.

   Usage:
     (require '[edgar.forms.form13f])  ; side-effectful load registers the method
     (edgar.filing/filing-obj filing)  ; => {:form \"13F-HR\" :holdings <dataset> ...}"
  (:require [edgar.filing :as filing]
            [clojure.xml :as xml]
            [clojure.string :as str]
            [tech.v3.dataset :as ds]))

;;; ---------------------------------------------------------------------------
;;; XML helpers (mirrors form4 — no extra deps)
;;; ---------------------------------------------------------------------------

(defn- parse-xml-str [s]
  (xml/parse (java.io.ByteArrayInputStream. (.getBytes s "UTF-8"))))

(defn- find-tag
  "Return the first descendant element with the given tag (depth-first)."
  [node tag]
  (when (map? node)
    (if (= tag (:tag node))
      node
      (some #(find-tag % tag) (:content node)))))

(defn- find-tags
  "Return all descendant elements with the given tag."
  [node tag]
  (when (map? node)
    (let [here (if (= tag (:tag node)) [node] [])]
      (into here (mapcat #(find-tags % tag) (:content node))))))

(defn- tag-text
  "Find a descendant node by tag and return its trimmed text content."
  [node tag]
  (some-> (find-tag node tag)
          :content
          first
          str
          str/trim
          not-empty))

(defn- child-tag-text
  "Return text of the first *direct* child element matching tag."
  [node tag]
  (some-> (filter #(and (map? %) (= tag (:tag %))) (:content node))
          first
          :content
          first
          str
          str/trim
          not-empty))

;;; ---------------------------------------------------------------------------
;;; Primary XML document locator
;;;
;;; A 13F-HR submission package contains:
;;;   - primary-doc.htm  — cover page / header document
;;;   - infotable.xml    — the actual holdings infotable (XML era)
;;; We find the infotable by looking for an .xml attachment that is NOT
;;; the XBRL instance document (which ends in _htm.xml).
;;; ---------------------------------------------------------------------------

(defn- find-infotable-xml [filing]
  (let [idx (filing/filing-index filing)
        docs (:files idx)
        xml-doc (first (filter (fn [{:keys [name type]}]
                                 (and (str/ends-with? (str name) ".xml")
                                      (not (str/ends-with? (str name) "_htm.xml"))
                                      (not (str/includes? (str/lower-case (str type)) "xbrl"))))
                               docs))]
    (when xml-doc
      (filing/filing-document filing (:name xml-doc) :raw? true))))

(defn- find-primary-xml [filing]
  (let [idx (filing/filing-index filing)
        docs (:files idx)
        xml-doc (first (filter #(str/ends-with? (str (:name %)) ".xml") docs))]
    (when xml-doc
      (filing/filing-document filing (:name xml-doc) :raw? true))))

;;; ---------------------------------------------------------------------------
;;; Header / cover document parsing
;;; The primary XML document contains the <headerData> and <formData> wrapper.
;;; The infotable may be inline (single-file submission) or a separate attachment.
;;; ---------------------------------------------------------------------------

(defn- parse-manager [root]
  (let [filer (find-tag root :filingManager)
        addr (find-tag filer :address)]
    {:name (tag-text filer :name)
     :street (tag-text addr :street1)
     :city (tag-text addr :city)
     :state (tag-text addr :stateOrCountry)
     :zip (tag-text addr :zipCode)}))

(defn- parse-report-summary [root]
  (let [summary (find-tag root :reportSummary)]
    {:period-of-report (tag-text summary :periodOfReport)
     :report-type (tag-text summary :reportType)
     :form13f-file-number (tag-text summary :form13FFileNumber)
     :is-amendment? (= "RESTATEMENT" (str/upper-case
                                      (or (tag-text summary :reportType) "")))
     :other-managers-count (some-> (tag-text summary :otherManagersCount)
                                   parse-long)
     :table-entry-count (some-> (tag-text summary :tableEntryCount)
                                parse-long)
     :table-value-total (some-> (tag-text summary :tableValueTotal)
                                parse-long)}))

;;; ---------------------------------------------------------------------------
;;; Holdings infotable parsing
;;; Each <infoTable> element represents one position.
;;; ---------------------------------------------------------------------------

(defn- parse-double-safe [s]
  (when (not-empty (str/trim (or s "")))
    (try (parse-double (str/replace s "," "")) (catch Exception _ nil))))

(defn- parse-long-safe [s]
  (when (not-empty (str/trim (or s "")))
    (try (parse-long (str/replace s "," "")) (catch Exception _ nil))))

(defn- parse-holding [entry]
  (let [shrs-prn-amt (find-tag entry :shrsOrPrnAmt)]
    {:name (tag-text entry :nameOfIssuer)
     :cusip (tag-text entry :cusip)
     :title (tag-text entry :titleOfClass)
     :value (parse-long-safe (tag-text entry :value))
     :shares (parse-long-safe (child-tag-text shrs-prn-amt :sshPrnamt))
     :shares-type (child-tag-text shrs-prn-amt :sshPrnamtType)
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
  "Parse a 13F-HR filing map into a structured map.

   Returns:
     {:form                \"13F-HR\"
      :period-of-report    \"YYYY-MM-DD\"
      :report-type         \"13F-HR\" | \"13F-HR/A\"
      :is-amendment?       boolean
      :form13f-file-number \"028-...\"
      :table-entry-count   integer
      :table-value-total   long (thousands of USD)
      :manager             {:name ... :street ... :city ... :state ... :zip ...}
      :holdings            <tech.ml.dataset>
                             columns: :name :cusip :title :value :shares
                                      :shares-type :put-call
                                      :investment-discretion :other-managers
                                      :voting-sole :voting-shared :voting-none
      :total-value         long (sum of :value column, thousands of USD)}"
  [filing]
  (when-let [raw (or (find-infotable-xml filing)
                     (find-primary-xml filing))]
    (let [root (parse-xml-str raw)
          summary (parse-report-summary root)
          holdings (parse-holdings root)
          holdings-ds (holdings->dataset holdings)
          total-value (reduce + 0 (remove nil? (map :value holdings)))]
      (merge
       {:form "13F-HR"
        :manager (parse-manager root)
        :holdings holdings-ds
        :total-value total-value}
       summary))))

;;; ---------------------------------------------------------------------------
;;; Register filing-obj methods
;;; ---------------------------------------------------------------------------

(defmethod filing/filing-obj "13F-HR" [filing]
  (parse-form13f filing))

(defmethod filing/filing-obj "13F-HR/A" [filing]
  (parse-form13f filing))
