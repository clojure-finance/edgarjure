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
  "Build the submission index URL for a filing."
  [{:keys [cik accessionNumber]}]
  (let [acc-clean (str/replace accessionNumber "-" "")]
    (str core/archives-url "/" cik "/" acc-clean "/" accessionNumber "-index.json")))

(defn filing-index
  "Fetch the filing index — list of all documents/attachments in a filing.
   Returns a map with :files (seq of {:name :type :document :description :size})."
  [filing]
  (core/edgar-get (filing-index-url filing)))

(defn primary-doc
  "Return the primary document entry from a filing index."
  [index]
  (->> (:files index)
       (filter #(= "1" (str (:sequence %))))
       first))

;;; ---------------------------------------------------------------------------
;;; Filing content access
;;; ---------------------------------------------------------------------------

(defn- doc-url
  "Build the URL for a specific document within a filing."
  [{:keys [cik accessionNumber]} doc-name]
  (let [acc-clean (str/replace accessionNumber "-" "")]
    (str core/archives-url "/" cik "/" acc-clean "/" doc-name)))

(defn filing-html
  "Fetch the primary HTML document of a filing as a string."
  [filing]
  (let [idx (filing-index filing)
        primary (primary-doc idx)]
    (when primary
      (core/edgar-get (doc-url filing (:name primary)) :raw? true))))

(defn filing-text
  "Fetch the primary document of a filing as plain text (HTML stripped)."
  [filing]
  (when-let [html (filing-html filing)]
    (-> html
        hickory/parse
        hickory/as-hickory
        (#(sel/select sel/any %))
        (->> (map :content)
             flatten
             (filter string?)
             (str/join " ")
             str/trim))))

(defn filing-document
  "Fetch a specific named document from a filing as a string."
  [filing doc-name & {:keys [raw?] :or {raw? true}}]
  (core/edgar-get (doc-url filing doc-name) :raw? raw?))

;;; ---------------------------------------------------------------------------
;;; Filing save to disk (sec-edgar-downloader analog)
;;; ---------------------------------------------------------------------------

(defn filing-save!
  "Download a filing's primary document to a directory.
   Creates subdirectory structure: dir/{form}/{cik}/{accession-no}/{filename}
   Returns the saved file path."
  [filing dir]
  (let [idx (filing-index filing)
        primary (primary-doc idx)
        form (:form filing)
        cik (:cik filing)
        acc (:accessionNumber filing)
        out-dir (fs/path dir form cik acc)
        out-file (fs/path out-dir (:name primary))]
    (fs/create-dirs out-dir)
    (spit (str out-file)
          (core/edgar-get (doc-url filing (:name primary)) :raw? true))
    (str out-file)))

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
               (core/edgar-get (doc-url filing (:name doc)) :raw? true))
         (str out-file))))))

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
        primary (->> (:files idx)
                     (filter #(= "1" (str (:sequence %))))
                     first)]
    (assoc stub
           :form (or (get-in idx [:formType]) (get-in idx [:form-type]))
           :primaryDocument (:name primary)
           :filingDate (get-in idx [:filingDate]))))

(defmulti filing-obj
  "Parse a filing into a structured form-specific map.
   Dispatches on the :form key of the filing map."
  :form)

(defmethod filing-obj :default [filing]
  {:form (:form filing)
   :raw-html (filing-html filing)})
