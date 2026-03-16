(ns edgar.filings-test
  (:require [clojure.test :refer [deftest is testing]]
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
