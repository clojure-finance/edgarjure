(ns edgar.filing
  (:require [edgar.core :as core]
            [clojure.string :as str]
            [hickory.core :as hickory]
            [hickory.select :as sel]
            [babashka.fs :as fs])
  (:import [java.nio.file Path]))

;;; ---------------------------------------------------------------------------
;;; Filing index — list of documents in a single filing
;;; https://data.sec.gov/submissions/... gives accessionNumber
;;; Index JSON: https://data.sec.gov/submissions/{accession-no}-index.json
;;; ---------------------------------------------------------------------------

(defn filing-index-url
  "Build the HTML submission index URL for a filing."
  [{:keys [cik accessionNumber]}]
  (let [acc-clean (str/replace accessionNumber "-" "")
        cik-numeric (str (Long/parseLong cik))]
    (str core/archives-url "/" cik-numeric "/" acc-clean "/" accessionNumber "-index.html")))

(defn- cell-text
  "Recursively extract all text from a hickory node.
   Normalizes non-breaking spaces (\\u00A0) to regular spaces."
  [node]
  (cond
    (string? node) (str/replace node "\u00A0" " ")
    (map? node) (str/join "" (map cell-text (:content node)))
    :else ""))

(defn- parse-filing-index-html
  "Parse an HTML filing index page into
   {:files [...] :formType \"...\" :filingDate \"YYYY-MM-DD\"}."
  [html]
  (let [tree (hickory/as-hickory (hickory/parse html))
        node-str (fn [n] (str/trim (apply str (filter string? (tree-seq map? :content n)))))
        form-type (some->> (sel/select (sel/tag :strong) tree)
                           (map #(str/trim (cell-text %)))
                           (some #(second (re-matches #"Form\s+(\S+).*" %))))
        filing-date (some->> (map vector
                                  (map node-str (sel/select (sel/class "infoHead") tree))
                                  (map node-str (sel/select (sel/class "info") tree)))
                             (some (fn [[k v]] (when (= k "Filing Date") (not-empty v)))))
        rows (sel/select (sel/descendant (sel/tag :table) (sel/tag :tr)) tree)
        files (->> rows
                   (map (fn [row]
                          (let [cells (sel/select (sel/child (sel/tag :td)) row)
                                texts (mapv #(str/trim (cell-text %)) cells)]
                            (when (= 5 (count texts))
                              {:sequence (nth texts 0)
                               :description (nth texts 1)
                               :name (some-> (sel/select (sel/descendant (sel/tag :td) (sel/tag :a)) row)
                                             first
                                             (get-in [:content 0]))
                               :type (nth texts 3)
                               :size (nth texts 4)}))))
                   (remove nil?)
                   (remove #(= "Seq" (:sequence %)))
                   (remove #(str/blank? (:name %)))
                   (remove #(str/blank? (:size %))))]
    {:files files :formType form-type :filingDate filing-date}))

(defn filing-index
  "Fetch the filing index — list of all documents/attachments in a filing.
   Returns a map with :files (seq of {:sequence :name :type :description :size})
   and :formType (the SEC form type string)."
  [filing]
  (parse-filing-index-html
   (core/edgar-get (filing-index-url filing) :raw? true)))

(defn primary-doc
  "Return the primary document entry from a filing index."
  [index]
  (->> (:files index)
       (filter #(= "1" (str (:sequence %))))
       first))

;;; ---------------------------------------------------------------------------
;;; Filing content access
;;; ---------------------------------------------------------------------------

(defn filing-doc-url
  "Build the SEC archives URL for a specific document within a filing.
   doc-name is the filename as it appears in the filing index (e.g. \"goog-20260304.htm\", \"R2.htm\").
   Use (filing-index filing) to discover available document names."
  [{:keys [cik accessionNumber]} doc-name]
  (let [acc-clean (str/replace accessionNumber "-" "")
        cik-numeric (str (Long/parseLong cik))]
    (str core/archives-url "/" cik-numeric "/" acc-clean "/" doc-name)))

(defn filing-html
  "Fetch the primary HTML document of a filing as a string."
  [filing]
  (let [idx (filing-index filing)
        primary (primary-doc idx)]
    (when primary
      (core/edgar-get (filing-doc-url filing (:name primary)) :raw? true))))

(defn filing-text
  "Fetch the primary document of a filing as plain text (HTML stripped).
   Script and style elements and their subtree content are excluded."
  [filing]
  (when-let [html (filing-html filing)]
    (let [excluded-tags #{:script :style}
          extract-text
          (fn extract-text [node]
            (cond
              (string? node) (str/replace node "\u00A0" " ")
              (map? node) (if (excluded-tags (:tag node))
                            ""
                            (str/join "" (map extract-text (:content node))))
              :else ""))]
      (-> html
          hickory/parse
          hickory/as-hickory
          extract-text
          (str/replace #"\s+" " ")
          str/trim))))

(defn filing-document
  "Fetch a specific named document from a filing as a string."
  [filing doc-name & {:keys [raw?] :or {raw? true}}]
  (core/edgar-get (filing-doc-url filing doc-name) :raw? raw?))

;;; ---------------------------------------------------------------------------
;;; Filing save to disk (sec-edgar-downloader analog)
;;; ---------------------------------------------------------------------------

(defn filing-save!
  "Download a filing's primary document to a directory.
   Creates subdirectory structure: dir/{form}/{cik}/{accession-no}/{filename}
   Returns the saved file path, or nil if the filing has no primary document."
  [filing dir]
  (let [idx (filing-index filing)
        primary (primary-doc idx)]
    (when primary
      (let [form (:form filing)
            cik (:cik filing)
            acc (:accessionNumber filing)
            out-dir (fs/path dir form cik acc)
            out-file (fs/path out-dir (:name primary))]
        (fs/create-dirs out-dir)
        (spit (str out-file)
              (core/edgar-get (filing-doc-url filing (:name primary)) :raw? true))
        (str out-file)))))

(defn filing-save-all!
  "Download all documents in a filing to a directory.
   Returns a seq of saved file paths."
  [filing dir]
  (let [idx (filing-index filing)
        cik (:cik filing)
        acc (:accessionNumber filing)
        form (:form filing)
        out-dir (fs/path dir form cik acc)]
    (fs/create-dirs out-dir)
    (doall
     (for [doc (:files idx)
           :when (:name doc)]
       (let [out-file (fs/path out-dir (:name doc))]
         (spit (str out-file)
               (core/edgar-get (filing-doc-url filing (:name doc)) :raw? true))
         (str out-file))))))

;;; ---------------------------------------------------------------------------
;;; Exhibit and XBRL document access
;;; ---------------------------------------------------------------------------

(defn filing-exhibits
  "Return all exhibit entries from a filing's index as a seq of maps.
   Each map has :name :type :document :description :sequence.
   Exhibits have :type values beginning with \"EX-\" (e.g. \"EX-21\", \"EX-31.1\")."
  [filing]
  (let [idx (filing-index filing)]
    (->> (:files idx)
         (filter #(some-> (:type %) (str/starts-with? "EX-"))))))

(defn filing-exhibit
  "Return the first exhibit entry matching exhibit-type (e.g. \"EX-21\").
   Returns nil if no matching exhibit is found.
   Fetch its content with (filing-document filing (:name exhibit))."
  [filing exhibit-type]
  (let [idx (filing-index filing)]
    (->> (:files idx)
         (filter #(= exhibit-type (:type %)))
         first)))

(defn filing-xbrl-docs
  "Return all XBRL-related document entries from a filing's index as a seq of maps.
   Covers EX-101.* linkbases (instance, schema, calculation, label,
   presentation, definition) and .xsd schema files."
  [filing]
  (let [idx (filing-index filing)]
    (->> (:files idx)
         (filter #(or (some-> (:type %) (str/starts-with? "EX-101"))
                      (some-> (:name %) (str/ends-with? ".xsd")))))))

;;; ---------------------------------------------------------------------------
;;; Form-type dispatch — edgar.filing/obj
;;; Parsers for specific form types live in edgar.forms.*
;;; This multimethod is the extension point.
;;; ---------------------------------------------------------------------------

(defn filing-by-accession
  "Hydrate a filing map from an accession number string.
   Accepts dashed format: \"XXXXXXXXXX-YY-ZZZZZZ\"
   The first 10 digits of the accession number are the filer's CIK.
   Returns a filing map with :cik :accessionNumber :form :primaryDocument,
   ready for filing-html, filing-text, filing-obj, extract-items, etc.
   Throws ex-info with ::not-found if the accession number does not exist."
  [accession-number]
  (let [digits (str/replace accession-number "-" "")
        cik (str (Long/parseLong (subs digits 0 10)))
        acc-dashed (if (re-matches #"\d{10}-\d{2}-\d{6}" accession-number)
                     accession-number
                     (str (subs digits 0 10) "-" (subs digits 10 12) "-" (subs digits 12)))
        stub {:cik cik :accessionNumber acc-dashed}
        idx (try
              (filing-index stub)
              (catch Exception e
                (throw (ex-info "Filing not found"
                                {:type ::not-found
                                 :accession-number accession-number}
                                e))))
        primary (primary-doc idx)]
    (let [form-type (or (:formType idx)
                        (throw (ex-info "Could not determine form type from filing index"
                                        {:type ::not-found
                                         :accession-number accession-number})))]
      (let [m (assoc stub
                     :form form-type
                     :primaryDocument (:name primary)
                     :filingDate (:filingDate idx))]
        (assoc m :url (when (:primaryDocument m)
                        (filing-doc-url m (:primaryDocument m))))))))

(defmulti filing-obj
  "Parse a filing into a structured form-specific map.
   Dispatches on the :form key of the filing map."
  :form)

(defmethod filing-obj :default [filing]
  {:form (:form filing)
   :raw-html (filing-html filing)})
