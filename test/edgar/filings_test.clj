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

(deftest latest-effective-filing-date-comparison-test
  (testing "amendment newer than original → compare positive"
    (is (pos? (compare "2023-12-15" "2023-11-03"))))
  (testing "original newer than amendment → compare negative"
    (is (neg? (compare "2023-11-03" "2023-12-15"))))
  (testing "same dates → compare zero"
    (is (zero? (compare "2023-11-03" "2023-11-03")))))
