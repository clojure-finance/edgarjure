(ns edgar.filings-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.core :as core]
            [edgar.filings :as filings]))

;;; ---------------------------------------------------------------------------
;;; get-filings — offline, against a submissions fixture with KEYWORD keys
;;; (jsonista's keyword-keys mapper is what core/edgar-get returns)
;;; ---------------------------------------------------------------------------

(def ^:private submissions-fixture
  {:cik "320193"
   :name "Apple Inc."
   :filings {:recent {:accessionNumber ["0000320193-25-000079" "0001193125-10-012091"
                                        "0000320193-09-000080" "0001193125-09-153165"]
                      :form ["10-K" "10-K/A" "10-K" "8-K"]
                      :filingDate ["2025-10-31" "2010-01-25" "2009-10-27" "2009-07-21"]
                      :primaryDocument ["aapl-20250927.htm" "d10ka.htm" "d10k.htm" "d8k.htm"]}
             :files []}})

(deftest get-filings-amendments-test
  (with-redefs [core/edgar-get (fn [_ & _] submissions-fixture)]
    (testing "amendments are stripped by default"
      (is (= ["10-K" "10-K" "8-K"] (map :form (filings/get-filings "320193")))))
    (testing ":form \"10-K\" excludes 10-K/A"
      (is (= ["0000320193-25-000079" "0000320193-09-000080"]
             (map :accessionNumber (filings/get-filings "320193" :form "10-K")))))
    (testing ":form \"10-K/A\" returns the amendments without :include-amends?"
      (is (= ["0001193125-10-012091"]
             (map :accessionNumber (filings/get-filings "320193" :form "10-K/A")))))
    (testing ":include-amends? true keeps amendments in an unfiltered listing"
      (is (= 4 (count (filings/get-filings "320193" :include-amends? true)))))
    (testing "returned maps carry the padded CIK and the document URL"
      (let [f (first (filings/get-filings 320193 :form "10-K/A"))]
        (is (= "0000320193" (:cik f)))
        (is (= "https://www.sec.gov/Archives/edgar/data/320193/000119312510012091/d10ka.htm"
               (:url f)))))))

;;; ---------------------------------------------------------------------------
;;; full-index-url
;;; ---------------------------------------------------------------------------

(deftest full-index-url-test
  (testing "builds correct company index URL"
    (is (= "https://www.sec.gov/Archives/edgar/full-index/2023/QTR1/company.idx"
           (filings/full-index-url 2023 1 "company"))))
  (testing "builds correct master index URL"
    (is (= "https://www.sec.gov/Archives/edgar/full-index/2020/QTR4/master.idx"
           (filings/full-index-url 2020 4 "master")))))

(deftest get-quarterly-index-test
  ;; Uses with-redefs on edgar-get to avoid network calls.
  ;; Fixture mirrors the REAL master.idx format: pipe-delimited with column
  ;; order CIK|Company Name|Form Type|Date Filed|Filename and a variable-length
  ;; header. (company.idx/form.idx are fixed-width and are NOT used.)
  (let [fake-idx (clojure.string/join "\n"
                                      ["Description:           Master Index of EDGAR Dissemination Feed"
                                       "Last Data Received:    March 31, 2023"
                                       "Comments:              webmaster@sec.gov"
                                       "Anonymous FTP:         ftp://ftp.sec.gov/edgar/"
                                       "Cloud HTTP:            https://www.sec.gov/Archives/"
                                       ""
                                       ""
                                       ""
                                       "CIK|Company Name|Form Type|Date Filed|Filename"
                                       "--------------------------------------------------------------------------------"
                                       "320193|APPLE INC|10-K|2023-01-20|edgar/data/320193/0000320193-23-000006.txt"
                                       "789019|MICROSOFT CORP|10-Q|2023-02-15|edgar/data/789019/0000950170-23-002346.txt"
                                       ""])
        requested-url (atom nil)]
    (with-redefs [edgar.core/edgar-get (fn [url & _] (reset! requested-url url) fake-idx)]
      (let [result (vec (filings/get-quarterly-index 2023 1))]
        (testing "fetches master.idx (pipe-delimited), not the fixed-width company.idx"
          (is (clojure.string/ends-with? @requested-url "master.idx")))
        (testing "returns 2 data rows, skipping all header/separator lines"
          (is (= 2 (count result))))
        (testing "column mapping: :cik is at parts[0], zero-padded to 10 digits"
          (is (= "0000320193" (:cik (first result)))))
        (testing "column mapping: :company-name is at parts[1]"
          (is (= "APPLE INC" (:company-name (first result)))))
        (testing "column mapping: :form-type is at parts[2]"
          (is (= "10-K" (:form-type (first result)))))
        (testing "column mapping: :date-filed is at parts[3]"
          (is (= "2023-01-20" (:date-filed (first result)))))
        (testing "column mapping: :filename is at parts[4]"
          (is (= "edgar/data/320193/0000320193-23-000006.txt" (:filename (first result)))))
        (testing "second row parsed correctly"
          (is (= "MICROSOFT CORP" (:company-name (second result))))
          (is (= "0000789019" (:cik (second result)))))
        (testing "header row 'CIK|Company Name|...' is NOT included as data"
          (is (every? #(re-matches #"\d{4}-\d{2}-\d{2}" (:date-filed %)) result)))
        (testing "separator row '-----' is NOT included as data"
          (is (every? #(re-matches #"\d{10}" (:cik %)) result))))))
  (testing "get-quarterly-index handles header longer than 10 lines without breaking"
    (let [long-header-idx (clojure.string/join "\n"
                                               (concat (repeat 15 "# header comment line")
                                                       ["111111|ONLY CORP|8-K|2023-03-01|edgar/data/111111/0001.txt"]))]
      (with-redefs [edgar.core/edgar-get (fn [_ & _] long-header-idx)]
        (let [result (vec (filings/get-quarterly-index 2023 1))]
          (is (= 1 (count result)))
          (is (= "ONLY CORP" (:company-name (first result))))
          (is (= "0000111111" (:cik (first result))))))))
  (testing "get-quarterly-index returns empty seq for input with no data lines"
    (with-redefs [edgar.core/edgar-get (fn [_ & _] "just a header\nno pipe data here\n")]
      (let [result (vec (filings/get-quarterly-index 2023 1))]
        (is (= [] result)))))
  (testing "get-quarterly-index-by-form filters correctly"
    (let [fake-idx (clojure.string/join "\n"
                                        ["320193|APPLE INC|10-K|2023-01-20|edgar/data/1/0001.txt"
                                         "789019|MICROSOFT CORP|10-Q|2023-02-15|edgar/data/2/0002.txt"
                                         "1018724|AMAZON COM INC|10-K|2023-03-01|edgar/data/3/0003.txt"])]
      (with-redefs [edgar.core/edgar-get (fn [_ & _] fake-idx)]
        (let [result (vec (filings/get-quarterly-index-by-form 2023 1 "10-K"))]
          (is (= 2 (count result)))
          (is (every? #(= "10-K" (:form-type %)) result)))))))

;;; ---------------------------------------------------------------------------
;;; get-quarterly-index cache mode
;;; ---------------------------------------------------------------------------

(deftest get-quarterly-index-bypasses-memory-cache-test
  (testing "the 30-60 MB master.idx is fetched with :cache :disk"
    (let [opts (atom nil)]
      (with-redefs [core/edgar-get (fn [_ & kvs] (reset! opts (apply hash-map kvs)) "")]
        (dorun (filings/get-quarterly-index 2024 1))
        (is (= {:raw? true :cache :disk} @opts))))))

(deftest accession-normalization-test
  (let [f #'edgar.filings/accession->str]
    (testing "18-digit undashed string gets dashes inserted"
      (is (= "0000320193-23-000064" (f "000032019323000064"))))
    (testing "already-dashed string is returned unchanged"
      (is (= "0000320193-23-000064" (f "0000320193-23-000064"))))
    (testing "another real accession number"
      (is (= "0001193125-22-082474" (f "000119312522082474"))))))

(deftest amended-predicate-test
  (let [amended? #'edgar.filings/amended?]
    (testing "10-K/A is amended"
      (is (amended? {:form "10-K/A"})))
    (testing "10-Q/A is amended"
      (is (amended? {:form "10-Q/A"})))
    (testing "8-K/A is amended"
      (is (amended? {:form "8-K/A"})))
    (testing "10-K is not amended"
      (is (not (amended? {:form "10-K"}))))
    (testing "10-Q is not amended"
      (is (not (amended? {:form "10-Q"}))))
    (testing "plain 4 is not amended"
      (is (not (amended? {:form "4"}))))))

(deftest parse-filings-recent-test
  (let [f #'edgar.filings/parse-filings-recent
        recent {"form" ["10-K" "10-Q"]
                "filingDate" ["2023-11-03" "2023-07-28"]
                "accessionNumber" ["0000320193-23-000106" "0000320193-23-000077"]}
        result (vec (f recent))]
    (testing "returns one map per filing row"
      (is (= 2 (count result))))
    (testing "first row :form"
      (is (= "10-K" (:form (first result)))))
    (testing "first row :filingDate"
      (is (= "2023-11-03" (:filingDate (first result)))))
    (testing "first row :accessionNumber"
      (is (= "0000320193-23-000106" (:accessionNumber (first result)))))
    (testing "second row :form is 10-Q"
      (is (= "10-Q" (:form (second result)))))))

(deftest parse-filings-recent-single-row-test
  (let [f #'edgar.filings/parse-filings-recent
        recent {"form" ["8-K"]
                "filingDate" ["2024-01-15"]
                "accessionNumber" ["0000320193-24-000005"]}
        result (vec (f recent))]
    (testing "single-row map parses to one filing"
      (is (= 1 (count result))))
    (testing "form is preserved"
      (is (= "8-K" (:form (first result)))))))

(deftest enrich-filing-url-test
  (let [enrich #'edgar.filings/enrich-filing
        cik "0000320193"
        filing {:accessionNumber "000032019323000106"
                :form "10-K"
                :primaryDocument "aapl-20230930.htm"}
        result (enrich cik filing)]
    (testing ":url is computed from cik, accessionNumber and primaryDocument"
      (is (= "https://www.sec.gov/Archives/edgar/data/320193/000032019323000106/aapl-20230930.htm"
             (:url result))))
    (testing ":url is nil when primaryDocument is absent"
      (let [r (enrich cik (dissoc filing :primaryDocument))]
        (is (nil? (:url r)))))))

(deftest fetch-extra-filings-flat-chunk-test
  (let [f #'edgar.filings/fetch-extra-filings]
    (testing "fetch-extra-filings returns empty (lazy) seq when no :files key"
      (let [company {:filings {:recent {}}}]
        (is (empty? (f company)))))
    (testing "parse-filings-recent handles flat columnar chunk (no :recent wrapper)"
      (let [parse #'edgar.filings/parse-filings-recent
            chunk {"form" ["10-K" "10-Q"]
                   "filingDate" ["2020-01-01" "2020-07-01"]
                   "accessionNumber" ["0000320193-20-000001" "0000320193-20-000002"]
                   "primaryDocument" ["doc1.htm" "doc2.htm"]}
            result (vec (parse chunk))]
        (is (= 2 (count result)))
        (is (= "10-K" (:form (first result))))
        (is (= "10-Q" (:form (second result))))))))

(deftest latest-effective-filing-logic-test
  (let [original {:form "10-K" :filingDate "2023-11-03" :accessionNumber "A"}
        amendment {:form "10-K/A" :filingDate "2024-01-15" :accessionNumber "B"}
        older-amendment {:form "10-K/A" :filingDate "2023-10-01" :accessionNumber "C"}]
    (testing "amendment newer than original → amendment returned"
      (with-redefs [edgar.filings/get-filings (fn [_ & _] [amendment original])]
        (is (= amendment (filings/latest-effective-filing "AAPL" :form "10-K")))))
    (testing "amendment older than original → original returned"
      (with-redefs [edgar.filings/get-filings (fn [_ & _] [original older-amendment])]
        (is (= original (filings/latest-effective-filing "AAPL" :form "10-K")))))
    (testing "no amendment → original returned"
      (with-redefs [edgar.filings/get-filings (fn [_ & _] [original])]
        (is (= original (filings/latest-effective-filing "AAPL" :form "10-K")))))
    (testing "no original → amendment returned"
      (with-redefs [edgar.filings/get-filings (fn [_ & _] [amendment])]
        (is (= amendment (filings/latest-effective-filing "AAPL" :form "10-K")))))))

;;; ---------------------------------------------------------------------------
;;; get-daily-filings — daily master index
;;; Fixture: verbatim header and rows of the real
;;; daily-index/2026/QTR4/master.20261013.idx (captured 2026-10-15), with a
;;; two-filer Form 4, a duplicated co-registrant row, an 8-K and an 8-K/A.
;;; ---------------------------------------------------------------------------

(def ^:private daily-master-fixture
  (clojure.string/join
   "\n"
   ["Description:           Daily Index of EDGAR Dissemination Feed"
    "Last Data Received:    Oct 13, 2026"
    "Comments:              webmaster@sec.gov"
    "Anonymous FTP:         ftp://ftp.sec.gov/edgar/"
    " "
    "CIK|Company Name|Form Type|Date Filed|File Name"
    "--------------------------------------------------------------------------------"
    "102037|UNIVERSAL CORP /VA/|8-K|20261013|edgar/data/102037/0001193125-26-420470.txt"
    "1104038|OpenWorld, Inc.|8-K/A|20261013|edgar/data/1104038/0001214659-26-012828.txt"
    "1777677|Fundrise Real Estate Interval Fund, LLC|4|20261013|edgar/data/1777677/0001435109-26-000153.txt"
    "1836839|Starr Gayle P|4|20261013|edgar/data/1836839/0001435109-26-000153.txt"
    "2138533|Fundrise Innovation Fund II, LLC|N-2/A|20261013|edgar/data/2138533/0001213900-26-109086.txt"
    "2138533|Fundrise Innovation Fund II, LLC|N-2/A|20261013|edgar/data/2138533/0001213900-26-109086.txt"
    ""]))

(defn- missing-file-ex [url]
  ;; what core/edgar-get throws for a file the S3-backed Archives do not have
  (ex-info "HTTP 403 from SEC API" {:type :edgar.core/http-error :status 403 :url url}))

(deftest get-daily-filings-test
  (let [urls (atom [])]
    (with-redefs [core/edgar-get (fn [url & _] (swap! urls conj url) daily-master-fixture)]
      (let [result (vec (filings/get-daily-filings "2026-10-13"))]
        (testing "fetches the daily master index for the date's quarter"
          (is (= ["https://www.sec.gov/Archives/edgar/daily-index/2026/QTR4/master.20261013.idx"]
                 @urls)))
        (testing "one map per filing: multi-filer and duplicate rows are merged"
          (is (= ["0001193125-26-420470" "0001214659-26-012828"
                  "0001435109-26-000153" "0001213900-26-109086"]
                 (mapv :accessionNumber result))))
        (testing "map shape: padded CIK, ISO date, every filer listed"
          (is (= {:accessionNumber "0001435109-26-000153"
                  :form "4"
                  :filingDate "2026-10-13"
                  :cik "0001777677"
                  :companyName "Fundrise Real Estate Interval Fund, LLC"
                  :filers [{:cik "0001777677" :name "Fundrise Real Estate Interval Fund, LLC"}
                           {:cik "0001836839" :name "Starr Gayle P"}]}
                 (nth result 2)))
          (is (= [{:cik "0002138533" :name "Fundrise Innovation Fund II, LLC"}]
                 (:filers (nth result 3)))))
        (testing ":form is an exact match — 8-K excludes 8-K/A"
          (is (= ["0001193125-26-420470"]
                 (map :accessionNumber (filings/get-daily-filings "2026-10-13" :form "8-K"))))
          (is (= ["0001214659-26-012828"]
                 (map :accessionNumber (filings/get-daily-filings "2026-10-13" :form "8-K/A")))))
        (testing ":filter-fn and LocalDate input"
          (is (= ["0001435109-26-000153"]
                 (map :accessionNumber
                      (filings/get-daily-filings (java.time.LocalDate/of 2026 10 13)
                                                 :filter-fn #(> (count (:filers %)) 1))))))))))

(deftest get-daily-filings-missing-day-test
  (testing "weekend/holiday: file missing and not in the quarter listing → empty seq"
    (with-redefs [core/edgar-get
                  (fn [url & _]
                    (if (clojure.string/ends-with? url "index.json")
                      {:directory {:item [{:name "master.20261009.idx"} {:name "master.20261013.idx"}]}}
                      (throw (missing-file-ex url))))]
      (is (= [] (vec (filings/get-daily-filings "2026-10-11"))))))
  (testing "future quarter: the listing itself is missing → empty seq"
    (with-redefs [core/edgar-get (fn [url & _] (throw (missing-file-ex url)))]
      (is (empty? (filings/get-daily-filings "2027-01-04")))))
  (testing "file listed but not fetchable (e.g. a block) → throws, not empty"
    (with-redefs [core/edgar-get
                  (fn [url & _]
                    (if (clojure.string/ends-with? url "index.json")
                      {:directory {:item [{:name "master.20261013.idx"}]}}
                      (throw (missing-file-ex url))))]
      (is (thrown? clojure.lang.ExceptionInfo (doall (filings/get-daily-filings "2026-10-13"))))))
  (testing "other HTTP errors propagate"
    (with-redefs [core/edgar-get (fn [url & _]
                                   (throw (ex-info "HTTP 500" {:type :edgar.core/http-error :status 500 :url url})))]
      (is (thrown? clojure.lang.ExceptionInfo (doall (filings/get-daily-filings "2026-10-13"))))))
  (testing "1990s two-digit file names are found through the listing"
    (let [urls (atom [])]
      (with-redefs [core/edgar-get
                    (fn [url & _]
                      (swap! urls conj url)
                      (cond
                        (clojure.string/ends-with? url "index.json")
                        {:directory {:item [{:name "master.950103.idx"}]}}
                        (clojure.string/ends-with? url "master.950103.idx")
                        (str "CIK       |Company Name        |Form Type |Date Filed|File Name\n"
                             "106498|WESTVACO CORP|DEFA14A|19950103|edgar/data/106498/0000106498-95-000002.txt\n")
                        :else (throw (missing-file-ex url))))]
        (is (= [{:accessionNumber "0000106498-95-000002" :form "DEFA14A" :filingDate "1995-01-03"
                 :cik "0000106498" :companyName "WESTVACO CORP"
                 :filers [{:cik "0000106498" :name "WESTVACO CORP"}]}]
               (vec (filings/get-daily-filings "1995-01-03"))))
        (is (= "https://www.sec.gov/Archives/edgar/daily-index/1995/QTR1/master.950103.idx"
               (last @urls)))))))

;;; ---------------------------------------------------------------------------
;;; search-filings — hit shaping and per-filing dedupe
;;; Hit shape as returned live by EFTS (_id = "<adsh>:<document>").
;;; ---------------------------------------------------------------------------

(defn- efts-hit [adsh doc form]
  {:_index "edgar_file"
   :_id (str adsh ":" doc)
   :_source {:adsh adsh
             :ciks ["0000815097"]
             :display_names ["CARNIVAL CORP  (CCL)  (CIK 0000815097)"]
             :form form
             :root_forms [form]
             :file_date "2023-01-27"
             :file_type form
             :period_ending "2022-11-30"
             :items []}})

(deftest search-filings-shape-test
  (let [hits [(efts-hit "0000815097-23-000012" "ccl-20221130.htm" "10-K")
              (efts-hit "0000815097-23-000012" "ex-97.htm" "10-K")
              (efts-hit "0000815097-22-000040" "ccl-20220831.htm" "10-Q")]]
    (with-redefs [core/edgar-get (fn [_ & _] {:hits {:hits hits :total {:value 3 :relation "eq"}}})]
      (let [result (vec (filings/search-filings "climate risk" :limit 10))]
        (testing "one map per filing (exhibit hits of the same filing are dropped)"
          (is (= ["0000815097-23-000012" "0000815097-22-000040"] (mapv :accessionNumber result))))
        (testing "filing keys are added; the best-scoring document is kept"
          (is (= {:accessionNumber "0000815097-23-000012"
                  :cik "0000815097"
                  :form "10-K"
                  :filingDate "2023-01-27"
                  :companyName "CARNIVAL CORP  (CCL)  (CIK 0000815097)"
                  :document "ccl-20221130.htm"
                  :url "https://www.sec.gov/Archives/edgar/data/815097/000081509723000012/ccl-20221130.htm"}
                 (select-keys (first result) [:accessionNumber :cik :form :filingDate
                                              :companyName :document :url]))))
        (testing "raw _source keys are kept"
          (is (= "2022-11-30" (:period_ending (first result))))
          (is (= "0000815097-23-000012" (:adsh (first result))))))
      (testing ":limit counts filings, not hits"
        (is (= 1 (count (filings/search-filings "climate risk" :limit 1))))))))

;;; ---------------------------------------------------------------------------
;;; search-filings date parameters
;;; ---------------------------------------------------------------------------

(deftest search-filings-date-params-test
  (let [captured (atom nil)
        fake-resp {:hits {:hits [] :total {:value 0}}}
        run! (fn [& opts]
               (reset! captured nil)
               (with-redefs [core/edgar-get (fn [url & _]
                                              (reset! captured url)
                                              fake-resp)]
                 (doall (apply filings/search-filings "apple" opts))))]
    (testing ":end-date alone still emits dateRange=custom (EFTS ignores enddt without it)"
      (run! :end-date "2024-01-31")
      (is (clojure.string/includes? @captured "dateRange=custom"))
      (is (clojure.string/includes? @captured "enddt=2024-01-31"))
      (is (not (clojure.string/includes? @captured "startdt"))))
    (testing "both dates emit dateRange=custom exactly once"
      (run! :start-date "2023-01-01" :end-date "2024-01-31")
      (is (= 1 (count (re-seq #"dateRange=custom" @captured))))
      (is (clojure.string/includes? @captured "startdt=2023-01-01"))
      (is (clojure.string/includes? @captured "enddt=2024-01-31")))
    (testing "no dates: no dateRange parameter at all"
      (run!)
      (is (not (clojure.string/includes? @captured "dateRange"))))))
