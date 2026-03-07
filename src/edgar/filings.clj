(ns edgar.filings
  (:require [edgar.core :as core]
            [edgar.company :as company]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; Filing metadata helpers
;;; ---------------------------------------------------------------------------

(defn- parse-filings-recent
  "Convert the columnar :recent map from SEC submissions JSON into a seq of maps."
  [recent]
  (let [ks (map keyword (keys recent))
        cols (map #(get recent %) (keys recent))
        rows (apply map vector cols)]
    (map #(zipmap ks %) rows)))

(defn- accession->str
  "Normalise an accession number to dashed format: XXXXXXXXXX-YY-ZZZZZZ"
  [s]
  (if (re-matches #"\d{18}" s)
    (str (subs s 0 10) "-" (subs s 10 12) "-" (subs s 12))
    s))

(defn- enrich-filing
  "Add derived keys to a raw filing map."
  [cik filing]
  (-> filing
      (assoc :cik cik)
      (update :accessionNumber accession->str)))

;;; ---------------------------------------------------------------------------
;;; Get filings for a company
;;; ---------------------------------------------------------------------------

(defn get-filings
  "Return a lazy seq of filing metadata maps for a company.
   ticker-or-cik : ticker string or CIK integer/string
   Options:
     :form        - filter by form type string e.g. \"10-K\" \"10-Q\" \"8-K\"
     :start-date  - filter to filings on/after this date string \"YYYY-MM-DD\"
     :end-date    - filter to filings on/before this date string \"YYYY-MM-DD\"
     :limit       - max number of results (default all)"
  [ticker-or-cik & {:keys [form start-date end-date limit]}]
  (let [cik (company/company-cik ticker-or-cik)
        company (core/edgar-get (core/cik-url cik))
        recent (get-in company [:filings :recent])
        filings (->> (parse-filings-recent recent)
                     (map #(enrich-filing cik %)))]
    (cond->> filings
      form (filter #(= form (:form %)))
      start-date (filter #(>= (compare (:filingDate %) start-date) 0))
      end-date (filter #(<= (compare (:filingDate %) end-date) 0))
      limit (take limit))))

(defn latest-filing
  "Return the most recent filing from a seq of filing maps."
  [filings]
  (first filings))

(defn get-filing
  "Return the nth latest filing for a company.
   Options:
     :form - form type string e.g. \"10-K\" \"10-Q\" \"4\"
     :n    - 0-indexed position in results (default 0 = latest)"
  [ticker-or-cik & {:keys [form n] :or {n 0}}]
  (nth (get-filings ticker-or-cik :form form) n nil))

;;; ---------------------------------------------------------------------------
;;; Full-index quarterly (for bulk / historical crawling)
;;; Mirrors secedgar crawl-by-quarter approach
;;; Format: https://www.sec.gov/Archives/edgar/full-index/{year}/QTR{q}/company.gz
;;; ---------------------------------------------------------------------------

(defn full-index-url
  "Build the URL for a quarterly full-index file.
   type : \"company\" | \"form\" | \"crawler\" | \"master\""
  [year quarter type]
  (str core/full-index-url "/" year "/QTR" quarter "/" type ".idx"))

(defn get-quarterly-index
  "Fetch and parse the full quarterly index for year/quarter.
   Returns a seq of maps with :cik :company-name :form-type :date-filed :filename."
  [year quarter]
  (let [raw (core/edgar-get (full-index-url year quarter "company") :raw? true)
        lines (str/split-lines raw)]
    (->> lines
         (drop 10)
         (map str/trim)
         (remove str/blank?)
         (map (fn [line]
                (let [parts (str/split line #"\|")]
                  (when (= 5 (count parts))
                    {:cik (nth parts 0)
                     :company-name (nth parts 1)
                     :form-type (nth parts 2)
                     :date-filed (nth parts 3)
                     :filename (nth parts 4)}))))
         (remove nil?))))

(defn get-quarterly-index-by-form
  "Like get-quarterly-index but pre-filtered to a specific form type."
  [year quarter form]
  (filter #(= form (:form-type %)) (get-quarterly-index year quarter)))

;;; ---------------------------------------------------------------------------
;;; EFTS full-text search
;;; ---------------------------------------------------------------------------

(defn search-filings
  "Full-text search across SEC EDGAR filings.
   Options:
     :forms       - vector of form types e.g. [\"10-K\" \"10-Q\"]
     :start-date  - \"YYYY-MM-DD\"
     :end-date    - \"YYYY-MM-DD\"
     :limit       - max results (default 10)"
  [query & {:keys [forms start-date end-date limit] :or {limit 10}}]
  (let [params (cond-> (str "?q=" (java.net.URLEncoder/encode query "UTF-8"))
                 forms (str "&forms=" (str/join "," forms))
                 start-date (str "&dateRange=custom&startdt=" start-date)
                 end-date (str "&enddt=" end-date))
        resp (core/edgar-get (str core/efts-url params))]
    (->> (get-in resp [:hits :hits])
         (take limit)
         (map :_source))))
