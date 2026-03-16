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
  (let [raw-ks (keys recent)
        ks (map keyword raw-ks)
        cols (map #(get recent %) raw-ks)
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
  (let [acc (accession->str (:accessionNumber filing))
        acc-clean (str/replace acc "-" "")
        cik-numeric (str (Long/parseLong cik))
        primary (:primaryDocument filing)
        url (when primary
              (str core/archives-url "/" cik-numeric "/" acc-clean "/" primary))]
    (-> filing
        (assoc :cik cik
               :accessionNumber acc
               :url url))))

;;; ---------------------------------------------------------------------------
;;; Get filings for a company
;;; ---------------------------------------------------------------------------

(defn- fetch-extra-filings
  "Fetch additional submission chunks listed in [:filings :files] and return
   a seq of raw (un-enriched) filing maps."
  [company]
  (let [extra-files (get-in company [:filings :files])]
    (when (seq extra-files)
      (mapcat (fn [file-entry]
                (let [url (str core/submissions-url "/" (:name file-entry))
                      chunk (core/edgar-get url)]
                  (parse-filings-recent chunk)))
              extra-files))))

(defn- amended? [filing]
  (str/ends-with? (str (:form filing)) "/A"))

(defn get-filings
  "Return a lazy seq of filing metadata maps for a company.
   ticker-or-cik : ticker string or CIK integer/string
   Options:
     :form            - filter by form type string e.g. \"10-K\" \"10-Q\" \"8-K\"
     :start-date      - filter to filings on/after this date string \"YYYY-MM-DD\"
     :end-date        - filter to filings on/before this date string \"YYYY-MM-DD\"
     :limit           - max number of results (default all)
     :include-amends? - include amended filings e.g. 10-K/A, 10-Q/A (default false)"
  [ticker-or-cik & {:keys [form start-date end-date limit include-amends?]
                    :or {include-amends? false}}]
  (let [cik (company/company-cik ticker-or-cik)
        company (core/edgar-get (core/cik-url cik))
        main-filings (parse-filings-recent (get-in company [:filings :recent]))
        extra-filings (fetch-extra-filings company)
        filings (->> (concat main-filings extra-filings)
                     (map #(enrich-filing cik %)))]
    (cond->> filings
      (not include-amends?) (remove amended?)
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
     :form            - form type string e.g. \"10-K\" \"10-Q\" \"4\"
     :n               - 0-indexed position in results (default 0 = latest)
     :include-amends? - include amended filings (default false)"
  [ticker-or-cik & {:keys [form n include-amends?] :or {n 0 include-amends? false}}]
  (nth (get-filings ticker-or-cik :form form :include-amends? include-amends?) n nil))

(defn latest-effective-filing
  "Return the most recent effective (non-amended) filing for a company and form type.
   If an amendment exists that is newer than the original, returns the amendment instead.
   Options:
     :form - form type string e.g. \"10-K\""
  [ticker-or-cik & {:keys [form]}]
  (let [amend-form (when form (str form "/A"))
        all (get-filings ticker-or-cik :include-amends? true)
        matching (filter #(or (= form (:form %)) (= amend-form (:form %))) all)
        non-amended (filter #(= form (:form %)) matching)
        amendments (filter #(= amend-form (:form %)) matching)
        latest-original (first non-amended)
        latest-amendment (first amendments)]
    (if (and latest-amendment latest-original
             (pos? (compare (:filingDate latest-amendment)
                            (:filingDate latest-original))))
      latest-amendment
      (or latest-original latest-amendment))))

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

;;; ---------------------------------------------------------------------------
;;; Daily filing index
;;; Uses EFTS search-index with date range = single day + from= pagination.
;;; Each page returns up to 100 hits; we lazily fetch until exhausted.
;;;
;;; Each result map has keys:
;;;   :accessionNumber  — dashed accession number
;;;   :form             — form type string
;;;   :filingDate       — "YYYY-MM-DD"
;;;   :cik              — first CIK (string, zero-padded)
;;;   :companyName      — display name (may include ticker)
;;;   :periodOfReport   — "YYYY-MM-DD" or nil
;;;   :items            — vector of 8-K item numbers (or empty)
;;; ---------------------------------------------------------------------------

(defn- daily-index-url
  "Build an EFTS search-index URL for a single date with optional form filter."
  [date form from]
  (let [date-str (str date)]
    (cond-> (str core/efts-url
                 "?q=%22%22"
                 "&dateRange=custom"
                 "&startdt=" date-str
                 "&enddt=" date-str
                 "&from=" from)
      form (str "&forms=" (java.net.URLEncoder/encode form "UTF-8")))))

(defn- shape-daily-hit
  "Shape a raw EFTS _source map into a filing-like map."
  [src]
  {:accessionNumber (accession->str (:adsh src))
   :form (:form src)
   :filingDate (:file_date src)
   :cik (some-> (first (:ciks src))
                (as-> c (format "%010d" (Long/parseLong c))))
   :companyName (first (:display_names src))
   :periodOfReport (some-> (:period_ending src) not-empty)
   :items (or (:items src) [])})

(defn get-daily-filings
  "Return a lazy seq of all filings submitted on a given date.

   date      — a java.time.LocalDate, or a \"YYYY-MM-DD\" string
   Options:
     :form      — filter by form type string e.g. \"10-K\" \"8-K\"
     :filter-fn — an arbitrary predicate applied to each result map

   Lazily fetches pages of 100 from the EFTS search-index endpoint.
   Typical trading day: ~1,000–2,000 filings; busy days up to ~6,000.

   Each result map has:
     :accessionNumber  — dashed accession number
     :form             — form type string
     :filingDate       — \"YYYY-MM-DD\"
     :cik              — zero-padded 10-digit CIK string (first filer)
     :companyName      — display name (may include ticker in parens)
     :periodOfReport   — \"YYYY-MM-DD\" or nil
     :items            — vector of 8-K item strings (or empty)"
  [date & {:keys [form filter-fn]}]
  (let [date-str (str date)]
    (letfn [(fetch-page [from]
              (lazy-seq
               (let [resp (core/edgar-get (daily-index-url date-str form from))
                     hits (get-in resp [:hits :hits])
                     total (get-in resp [:hits :total :value] 0)
                     shaped (map (comp shape-daily-hit :_source) hits)]
                 (if (or (empty? hits) (>= (+ from (count hits)) total))
                   shaped
                   (concat shaped (fetch-page (+ from (count hits))))))))]
      (cond->> (fetch-page 0)
        filter-fn (filter filter-fn)))))
