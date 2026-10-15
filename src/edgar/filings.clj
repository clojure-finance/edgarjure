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
  "Return a lazy seq of raw (un-enriched) filing maps from the additional
   submission chunks listed in [:filings :files].

   Each chunk is fetched only when the seq is consumed that far, so callers
   that stop within the ~1000 filings of the :recent block (the common case —
   e.g. (e/filing \"AAPL\")) never trigger the extra HTTP requests.

   Chunks are ordered by :filingTo descending so the concatenation with the
   newest-first :recent block stays date-descending overall."
  [company]
  (letfn [(fetch-chunks [entries]
            (lazy-seq
             (when-let [[entry & more] (seq entries)]
               (let [url (str core/submissions-url "/" (:name entry))]
                 (concat (parse-filings-recent (core/edgar-get url))
                         (fetch-chunks more))))))]
    (fetch-chunks (->> (get-in company [:filings :files])
                       (sort-by :filingTo #(compare %2 %1))))))

(defn- amended? [filing]
  (str/ends-with? (str (:form filing)) "/A"))

(defn get-filings
  "Return a lazy seq of filing metadata maps for a company.
   ticker-or-cik : ticker string or CIK integer/string
   Options:
     :form            - filter by exact form type string e.g. \"10-K\" \"8-K\";
                        an amendment form such as \"10-K/A\" returns the
                        amendments themselves
     :start-date      - filter to filings on/after this date string \"YYYY-MM-DD\"
     :end-date        - filter to filings on/before this date string \"YYYY-MM-DD\"
     :limit           - max number of results (default all)
     :include-amends? - include amended filings e.g. 10-K/A, 10-Q/A (default
                        false; implied when :form itself is an amendment)"
  [ticker-or-cik & {:keys [form start-date end-date limit include-amends?]
                    :or {include-amends? false}}]
  (let [cik (company/company-cik ticker-or-cik)
        company (core/edgar-get (core/cik-url cik))
        main-filings (parse-filings-recent (get-in company [:filings :recent]))
        extra-filings (fetch-extra-filings company)
        filings (->> (concat main-filings extra-filings)
                     (map #(enrich-filing cik %)))
        strip-amends? (not (or include-amends? (amended? {:form form})))]
    (cond->> filings
      strip-amends? (remove amended?)
      form (filter #(= form (:form %)))
      start-date (filter #(>= (compare (:filingDate %) start-date) 0))
      end-date (filter #(<= (compare (:filingDate %) end-date) 0))
      limit (take limit))))

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
;;; Format: https://www.sec.gov/Archives/edgar/full-index/{year}/QTR{q}/{type}.idx
;;; ---------------------------------------------------------------------------

(defn full-index-url
  "Build the URL for a quarterly full-index file.
   type : \"company\" | \"form\" | \"crawler\" | \"master\""
  [year quarter type]
  (str core/full-index-url "/" year "/QTR" quarter "/" type ".idx"))

(defn- parse-master-idx
  "Parse the data rows of a pipe-delimited master index (quarterly
   full-index/master.idx or daily-index/master.YYYYMMDD.idx) into
   [cik company-name form-type date-filed filename] vectors, trimmed.
   :date-filed is normalised to YYYY-MM-DD (daily files use YYYYMMDD).

   The header has a variable number of lines before the first data row, so
   rather than dropping a fixed count we keep only lines whose fourth
   pipe-delimited field is a date — a property unique to data rows.

   Column order: CIK | Company Name | Form Type | Date Filed | Filename"
  [raw]
  (keep (fn [line]
          (let [parts (str/split line #"\|")]
            (when (= 5 (count parts))
              (let [[cik nm form date file] (map str/trim parts)]
                (when-let [[_ y m d] (re-matches #"(\d{4})-?(\d{2})-?(\d{2})" date)]
                  [cik nm form (str y "-" m "-" d) file])))))
        (str/split-lines raw)))

(defn get-quarterly-index
  "Fetch and parse the full quarterly index for year/quarter.
   Returns a seq of maps with :cik (zero-padded 10-digit string)
   :company-name :form-type :date-filed (YYYY-MM-DD) :filename.

   Uses master.idx, the pipe-delimited variant of the quarterly index.
   (company.idx and form.idx are fixed-width files; master.idx carries the
   same rows in a machine-friendly format.) The 30-60 MB file bypasses the
   in-memory response cache (the opt-in disk cache still applies)."
  [year quarter]
  (->> (parse-master-idx (core/edgar-get (full-index-url year quarter "master")
                                         :raw? true :cache :disk))
       (map (fn [[cik nm form date file]]
              {:cik (core/pad-cik cik)
               :company-name nm
               :form-type form
               :date-filed date
               :filename file}))))

(defn get-quarterly-index-by-form
  "Like get-quarterly-index but pre-filtered to a specific form type."
  [year quarter form]
  (filter #(= form (:form-type %)) (get-quarterly-index year quarter)))

;;; ---------------------------------------------------------------------------
;;; EFTS full-text search
;;; ---------------------------------------------------------------------------

(defn- url-encode ^String [s]
  (str/replace (java.net.URLEncoder/encode (str s) "UTF-8") "+" "%20"))

(defn- shape-search-hit
  "Shape one EFTS full-text hit. The raw _source map is kept (snake_case
   keys: :adsh :ciks :display_names :root_forms :file_date :period_ending
   :items :sics :biz_locations :inc_states :file_num ...) and the keys a
   filing map needs are added on top, so a hit works with e/html, e/text,
   e/filing-by-accession etc. The hit's _id is \"<adsh>:<file name>\" —
   the document the query matched, often an exhibit."
  [{:keys [_id _source]}]
  (let [doc (second (str/split (str _id) #":" 2))
        acc (some-> (:adsh _source) accession->str)
        cik (some-> (first (:ciks _source)) core/pad-cik)]
    (assoc _source
           :accessionNumber acc
           :cik cik
           :filingDate (:file_date _source)
           :companyName (first (:display_names _source))
           :document doc
           :url (when (and cik acc (not (str/blank? doc)))
                  (str core/archives-url "/" (Long/parseLong cik) "/"
                       (str/replace acc "-" "") "/" doc)))))

(defn search-filings
  "Full-text search across SEC EDGAR filings (EFTS, filings since 2001).
   Options:
     :forms       - vector of form types e.g. [\"10-K\" \"10-Q\"]
     :start-date  - \"YYYY-MM-DD\"
     :end-date    - \"YYYY-MM-DD\"
     :limit       - max results (default 10)

   Returns a lazy seq of one map per FILING, best match first. EFTS indexes
   documents, so a filing whose exhibits also match appears once (its
   best-scoring document). Each map is the raw EFTS _source map plus filing
   keys:
     :accessionNumber - dashed accession number (= :adsh)
     :cik             - zero-padded 10-digit CIK of the first filer
     :form            - form type, e.g. \"10-K\"
     :filingDate      - \"YYYY-MM-DD\" (= :file_date)
     :companyName     - first display name, e.g. \"Apple Inc.  (AAPL)  (CIK 0000320193)\"
     :document        - file name of the matching document
     :url             - archive URL of the matching document
   and the raw keys :adsh :ciks :display_names :root_forms :file_date
   :period_ending :items :sics :biz_locations :inc_states :file_num ...

   Lazily pages through the EFTS search-index with from= offsets, so :limit
   may exceed the EFTS page size. Note that EFTS itself caps result offsets
   at 10,000 — narrow the query or date range for exhaustive sweeps."
  [query & {:keys [forms start-date end-date limit] :or {limit 10}}]
  (let [base-params (cond-> (str "?q=" (url-encode query))
                      forms (str "&forms=" (url-encode (str/join "," forms)))
                      ;; EFTS ignores startdt/enddt without dateRange=custom,
                      ;; so emit it when EITHER bound is given
                      (or start-date end-date) (str "&dateRange=custom")
                      start-date (str "&startdt=" start-date)
                      end-date (str "&enddt=" end-date))]
    (letfn [(fetch-page [from]
              (lazy-seq
               (let [resp (core/edgar-get (str core/efts-url base-params "&from=" from))
                     hits (get-in resp [:hits :hits])
                     total (get-in resp [:hits :total :value] 0)]
                 (if (or (empty? hits) (>= (+ from (count hits)) total))
                   hits
                   (concat hits (fetch-page (+ from (count hits))))))))
            (distinct-by-adsh [hits seen]
              (lazy-seq
               (when-let [[h & more] (seq hits)]
                 (let [adsh (get-in h [:_source :adsh])]
                   (if (contains? seen adsh)
                     (distinct-by-adsh more seen)
                     (cons h (distinct-by-adsh more (conj seen adsh))))))))]
      (->> (distinct-by-adsh (fetch-page 0) #{})
           (take limit)
           (map shape-search-hit)))))

;;; ---------------------------------------------------------------------------
;;; Daily filing index
;;; https://www.sec.gov/Archives/edgar/daily-index/{year}/QTR{q}/master.YYYYMMDD.idx
;;; Pipe-delimited, same columns as the quarterly master.idx, one row per
;;; (filer, filing): a filing with several filers (Form 4 issuer + reporting
;;; owner, co-registrants) is listed once under each filer's CIK. Published
;;; once per business day around 10 pm ET; none on weekends and holidays.
;;; 1990s files use two-digit years (master.950103.idx).
;;; ---------------------------------------------------------------------------

(defn- missing-archive-file?
  "True for the statuses the SEC Archives answer for a file that does not
   exist (S3-backed, so usually 403 AccessDenied rather than 404)."
  [e]
  (contains? #{403 404} (:status (ex-data e))))

(defn- fetch-daily-master
  "Raw text of the daily master index for a LocalDate, or nil when the SEC
   published none for that date. A 403/404 on the expected file name is
   checked against the quarter's directory listing, so a genuine access
   failure is rethrown instead of being read as an empty day."
  [^java.time.LocalDate d]
  (let [dir (str core/daily-index-url "/" (.getYear d)
                 "/QTR" (inc (quot (dec (.getMonthValue d)) 3)))
        ymd (.format d java.time.format.DateTimeFormatter/BASIC_ISO_DATE)
        name8 (str "master." ymd ".idx")
        name6 (str "master." (subs ymd 2) ".idx")
        get-file (fn [nm]
                   (try (core/edgar-get (str dir "/" nm) :raw? true)
                        (catch clojure.lang.ExceptionInfo e
                          (when-not (missing-archive-file? e) (throw e)))))]
    (or (get-file name8)
        (let [listing (try (core/edgar-get (str dir "/index.json") :cache :none)
                           (catch clojure.lang.ExceptionInfo e
                             (when-not (missing-archive-file? e) (throw e))))
              names (set (map :name (get-in listing [:directory :item])))]
          (cond
            (contains? names name6) (core/edgar-get (str dir "/" name6) :raw? true)
            (contains? names name8) (throw (ex-info (str "Daily index " name8 " is listed but could not be fetched")
                                                    {:type ::daily-index-unavailable
                                                     :url (str dir "/" name8)}))
            :else nil)))))

(defn- accession-from-filename
  "\"edgar/data/320193/0000320193-26-000001.txt\" → \"0000320193-26-000001\""
  [filename]
  (some->> filename (re-find #"(\d{10}-\d{2}-\d{6})\.txt$") second))

(defn get-daily-filings
  "Return a seq of all filings the SEC disseminated on a given date, built
   from the EDGAR daily master index — complete, one map per filing.

   date — a java.time.LocalDate, or a \"YYYY-MM-DD\" string
   Options:
     :form      — keep only this exact form type (\"8-K\" excludes \"8-K/A\")
     :filter-fn — an arbitrary predicate applied to each result map

   Returns an empty seq for dates without an index: weekends, holidays,
   future dates, and the current day until the SEC publishes its index
   (around 10 pm ET).

   Each result map has:
     :accessionNumber — dashed accession number
     :form            — form type string
     :filingDate      — \"YYYY-MM-DD\" (the requested date; 1990s indexes
                        also list late-disseminated filings)
     :cik             — zero-padded 10-digit CIK of the first listed filer
     :companyName     — that filer's name
     :filers          — every filer as {:cik :name}, e.g. the issuer and
                        the reporting owner of a Form 4

   The maps carry :cik and :accessionNumber, so they work directly with
   e/html, e/text, e/items, e/obj, e/exhibits etc. (the primary document is
   resolved from the filing index on first use). The index names neither
   the primary document nor the filer roles: call e/filing-by-accession for
   :primaryDocument, :url and role-resolved :filers."
  [date & {:keys [form filter-fn]}]
  (let [d (if (instance? java.time.LocalDate date)
            date
            (java.time.LocalDate/parse (str date)))
        rows (some-> (fetch-daily-master d) parse-master-idx)
        acc-of (fn [[_ _ _ _ file]] (accession-from-filename file))
        by-acc (group-by acc-of rows)
        filings (->> (keep acc-of rows)
                     distinct
                     (map (fn [acc]
                            (let [rs (get by-acc acc)
                                  [cik nm frm filed] (first rs)]
                              {:accessionNumber acc
                               :form frm
                               :filingDate filed
                               :cik (core/pad-cik cik)
                               :companyName nm
                               :filers (->> rs
                                            (map (fn [[c n]] {:cik (core/pad-cik c) :name n}))
                                            distinct
                                            vec)}))))]
    (cond->> filings
      form (filter #(= form (:form %)))
      filter-fn (filter filter-fn))))
