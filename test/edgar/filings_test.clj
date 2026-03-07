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
