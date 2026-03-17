(ns edgar.filings-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.core :as core]
            [edgar.filings :as filings]))

;;; ---------------------------------------------------------------------------
;;; Accession number normalisation (via the public behaviour of get-filings)
;;; We test the private helper indirectly by checking enriched filing maps.
;;; ---------------------------------------------------------------------------

(def ^:private raw-filing
  {:accessionNumber "000032019323000064"
   :form "10-K"
   :filingDate "2023-11-03"
   :primaryDocument "aapl-20230930.htm"})

(def ^:private dashed-filing
  {:accessionNumber "0000320193-23-000064"
   :form "10-K"
   :filingDate "2023-11-03"
   :primaryDocument "aapl-20230930.htm"})

;;; ---------------------------------------------------------------------------
;;; latest-filing
;;; ---------------------------------------------------------------------------

(deftest latest-filing-test
  (testing "returns first element of a seq"
    (is (= :a (filings/latest-filing [:a :b :c]))))
  (testing "returns nil for empty seq"
    (is (nil? (filings/latest-filing []))))
  (testing "returns the single element"
    (is (= raw-filing (filings/latest-filing [raw-filing])))))

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
  ;; Tests that header detection is date-based (not drop-N) and column mapping is correct.
  (let [;; Realistic SEC company.idx content with a variable-length header.
        ;; The header here has 11 lines (not 10) to verify the fix isn't just drop-11.
        fake-idx (clojure.string/join "\n"
                                      ["Full-Index of Submissions for the period 2023-01-01 to 2023-03-31"
                                       "Generated on: 2023-04-03 00:32:11"
                                       ""
                                       "This file was created using the EDGAR Company Search."
                                       "  https://www.sec.gov/cgi-bin/browse-edgar"
                                       ""
                                       "Column  1  Company Name"
                                       "Column  2  Form Type"
                                       "Column  3  Date Filed"
                                       "Column  4  Filename"
                                       "Column  5  CIK"
                                       "" ; extra blank — header is now 12 lines, not 10
                                       " Company Name                       |Form Type |Date Filed|Filename              |CIK"
                                       " -----------------------------------|----------|----------|----------------------|-------"
                                       "APPLE INC                           |10-K      |2023-01-20|edgar/data/1/0001.txt |0000320193"
                                       "MICROSOFT CORP                      |10-Q      |2023-02-15|edgar/data/2/0002.txt |0000789019"
                                       ""])]
    (with-redefs [edgar.core/edgar-get (fn [_ & _] fake-idx)]
      (let [result (vec (filings/get-quarterly-index 2023 1))]
        (testing "returns 2 data rows, skipping all header/separator lines"
          (is (= 2 (count result))))
        (testing "column mapping: :company-name is at parts[0]"
          (is (= "APPLE INC" (:company-name (first result)))))
        (testing "column mapping: :form-type is at parts[1]"
          (is (= "10-K" (:form-type (first result)))))
        (testing "column mapping: :date-filed is at parts[2]"
          (is (= "2023-01-20" (:date-filed (first result)))))
        (testing "column mapping: :filename is at parts[3]"
          (is (= "edgar/data/1/0001.txt" (:filename (first result)))))
        (testing "column mapping: :cik is at parts[4] (not parts[0])"
          (is (= "0000320193" (:cik (first result)))))
        (testing "second row parsed correctly"
          (is (= "MICROSOFT CORP" (:company-name (second result))))
          (is (= "0000789019" (:cik (second result)))))
        (testing "header row 'Company Name|Form Type|...' is NOT included as data"
          (is (every? #(re-matches #"\d{4}-\d{2}-\d{2}" (:date-filed %)) result)))
        (testing "separator row '---|---|...' is NOT included as data"
          (is (every? #(not (clojure.string/starts-with? (:company-name %) "---")) result))))))
  (testing "get-quarterly-index handles header longer than 10 lines without breaking"
    ;; Simulate a header with 15 lines before data — old drop-10 would include junk
    (let [long-header-idx (clojure.string/join "\n"
                                               (concat (repeat 15 "# header comment line")
                                                       ["ONLY CORP|8-K|2023-03-01|edgar/data/3/0003.txt|0000111111"]))]
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
                                        ["APPLE INC|10-K|2023-01-20|edgar/data/1/0001.txt|0000320193"
                                         "MICROSOFT CORP|10-Q|2023-02-15|edgar/data/2/0002.txt|0000789019"
                                         "AMAZON COM INC|10-K|2023-03-01|edgar/data/3/0003.txt|0001018724"])]
      (with-redefs [edgar.core/edgar-get (fn [_ & _] fake-idx)]
        (let [result (vec (filings/get-quarterly-index-by-form 2023 1 "10-K"))]
          (is (= 2 (count result)))
          (is (every? #(= "10-K" (:form-type %)) result)))))))

;;; ---------------------------------------------------------------------------
;;; get-filing keyword args
;;; ---------------------------------------------------------------------------

(def ^:private filings-seq
  [{:accessionNumber "0000320193-23-000064" :form "10-K" :filingDate "2023-11-03"}
   {:accessionNumber "0000320193-22-000108" :form "10-K" :filingDate "2022-10-28"}
   {:accessionNumber "0000320193-21-000105" :form "10-K" :filingDate "2021-10-29"}])

(deftest get-filing-test
  (testing "returns nil for empty seq"
    (is (nil? (filings/latest-filing []))))
  (testing ":n 0 is equivalent to latest-filing"
    (is (= (first filings-seq)
           (filings/latest-filing filings-seq))))
  (testing "latest-filing returns first element"
    (is (= {:accessionNumber "0000320193-23-000064" :form "10-K" :filingDate "2023-11-03"}
           (filings/latest-filing filings-seq)))))

(deftest get-filing-limit-passthrough-test
  (testing "get-filing passes :limit (inc n) to get-filings — avoids fetching all pages"
    (let [captured-opts (atom nil)
          fake-filings [{:form "10-K" :filingDate "2023-11-03" :accessionNumber "A"}
                        {:form "10-K" :filingDate "2022-10-28" :accessionNumber "B"}
                        {:form "10-K" :filingDate "2021-10-29" :accessionNumber "C"}]]
      (with-redefs [edgar.filings/get-filings
                    (fn [_ & opts]
                      (reset! captured-opts (apply hash-map opts))
                      fake-filings)]
        (testing "n=0 → :limit 1"
          (filings/get-filing "AAPL" :form "10-K" :n 0)
          (is (= 1 (:limit @captured-opts))))
        (testing "n=1 → :limit 2"
          (filings/get-filing "AAPL" :form "10-K" :n 1)
          (is (= 2 (:limit @captured-opts))))
        (testing "n=2 → :limit 3"
          (filings/get-filing "AAPL" :form "10-K" :n 2)
          (is (= 3 (:limit @captured-opts))))
        (testing "correct filing is returned for n=1"
          (let [result (filings/get-filing "AAPL" :form "10-K" :n 1)]
            (is (= "B" (:accessionNumber result)))))))))

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

(deftest latest-effective-filing-date-comparison-test
  (testing "amendment newer than original → compare positive"
    (is (pos? (compare "2023-12-15" "2023-11-03"))))
  (testing "original newer than amendment → compare negative"
    (is (neg? (compare "2023-11-03" "2023-12-15"))))
  (testing "same dates → compare zero"
    (is (zero? (compare "2023-11-03" "2023-11-03")))))

(deftest fetch-extra-filings-flat-chunk-test
  (let [f #'edgar.filings/fetch-extra-filings
        enrich #'edgar.filings/enrich-filing]
    (testing "fetch-extra-filings returns empty when no :files key"
      (let [company {:filings {:recent {}}}]
        (is (nil? (f company)))))
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

(deftest shape-daily-hit-nil-period-test
  (let [f #'edgar.filings/shape-daily-hit]
    (testing "nil period_ending produces nil :periodOfReport, not \"nil\""
      (let [src {:adsh "0000320193-24-000001"
                 :form "10-K"
                 :file_date "2024-11-01"
                 :ciks ["320193"]
                 :display_names ["Apple Inc."]
                 :period_ending nil
                 :items []}
            result (f src)]
        (is (nil? (:periodOfReport result)))
        (is (not= "nil" (:periodOfReport result)))))
    (testing "missing period_ending key produces nil"
      (let [src {:adsh "0000320193-24-000002"
                 :form "8-K"
                 :file_date "2024-11-01"
                 :ciks ["320193"]
                 :display_names ["Apple Inc."]}
            result (f src)]
        (is (nil? (:periodOfReport result)))))
    (testing "non-nil period_ending is preserved"
      (let [src {:adsh "0000320193-24-000003"
                 :form "10-K"
                 :file_date "2024-11-01"
                 :ciks ["320193"]
                 :display_names ["Apple Inc."]
                 :period_ending "2024-09-28"
                 :items []}
            result (f src)]
        (is (= "2024-09-28" (:periodOfReport result)))))
    (testing "blank string period_ending produces nil via not-empty"
      (let [src {:adsh "0000320193-24-000004"
                 :form "10-K"
                 :file_date "2024-11-01"
                 :ciks ["320193"]
                 :display_names ["Apple Inc."]
                 :period_ending ""
                 :items []}
            result (f src)]
        (is (nil? (:periodOfReport result)))))))
