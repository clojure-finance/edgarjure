(ns edgar.integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [edgar.core :as core]
            [edgar.api :as e]
            [edgar.forms]
            [tech.v3.dataset :as ds]))

;; ---------------------------------------------------------------------------
;; Fixture — set identity once, clear cache, fetch the latest AAPL 10-K once
;; ---------------------------------------------------------------------------

(def ^:dynamic *aapl-10k* nil)

(defn with-identity [f]
  (core/set-identity! "Test User test@example.com")
  (core/clear-cache!)
  (binding [*aapl-10k* (e/filing "AAPL" :form "10-K")]
    (f)))

(use-fixtures :once with-identity)

;; ---------------------------------------------------------------------------
;; Company lookup
;; ---------------------------------------------------------------------------

(deftest cik-lookup-test
  (testing "ticker->CIK returns expected value for AAPL"
    (is (= "0000320193" (e/cik "AAPL"))))
  (testing "CIK input round-trips correctly"
    (is (= "0000320193" (e/cik "0000320193"))))
  (testing "company-name returns a non-blank string"
    (is (string? (e/company-name "AAPL")))
    (is (not (clojure.string/blank? (e/company-name "AAPL"))))))

(deftest company-metadata-test
  (testing "company-metadata returns a map with expected keys"
    (let [m (e/company-metadata "AAPL")]
      (is (map? m))
      (is (contains? m :sic))
      (is (contains? m :fiscal-year-end))
      (is (contains? m :addresses)))))

;; ---------------------------------------------------------------------------
;; Filings
;; ---------------------------------------------------------------------------

(deftest filings-test
  (testing "filings returns a non-empty seq for AAPL 10-K"
    (let [fs (e/filings "AAPL" :form "10-K" :limit 3)]
      (is (seq fs))
      (is (every? #(contains? % :accessionNumber) fs))
      (is (every? #(= "10-K" (:form %)) fs))))
  (testing "filings with include-amends? returns a non-empty seq"
    (let [fs (e/filings "AAPL" :form "10-K" :limit 10 :include-amends? true)]
      (is (seq fs)))))

(deftest filing-test
  (testing "filing returns a single map with required keys"
    (is (map? *aapl-10k*))
    (is (contains? *aapl-10k* :accessionNumber))
    (is (= "10-K" (:form *aapl-10k*)))))

(deftest filing-by-accession-test
  (testing "filing-by-accession round-trips the current AAPL 10-K accession"
    (let [accession (:accessionNumber *aapl-10k*)
          f (e/filing-by-accession accession)]
      (is (map? f))
      (is (= "10-K" (:form f)))
      (is (clojure.string/includes? (:accessionNumber f) "320193")))))

;; ---------------------------------------------------------------------------
;; Filing content
;; ---------------------------------------------------------------------------

(deftest filing-text-test
  (testing "text returns a non-blank string"
    (let [t (e/text *aapl-10k*)]
      (is (string? t))
      (is (> (count t) 1000)))))

(deftest filing-items-test
  (testing "items returns a map with item 7 for a 10-K"
    (let [items (e/items *aapl-10k* :only #{"7"})]
      (is (map? items))
      (is (contains? items "7"))
      (is (> (count (get-in items ["7" :text])) 500)))))

(deftest filing-exhibits-test
  (testing "exhibits returns a non-empty seq"
    (is (seq (e/exhibits *aapl-10k*)))))

;; ---------------------------------------------------------------------------
;; XBRL facts
;; ---------------------------------------------------------------------------

(deftest facts-test
  (testing "facts returns a dataset with expected columns"
    (let [ds (e/facts "AAPL" :concept "Assets" :form "10-K")]
      (is (ds/dataset? ds))
      (is (pos? (ds/row-count ds)))
      (is (contains? (set (ds/column-names ds)) :val))
      (is (contains? (set (ds/column-names ds)) :end))
      (is (contains? (set (ds/column-names ds)) :label)))))

(deftest concepts-test
  (testing "concepts returns a dataset with expected columns"
    (let [ds (e/concepts "AAPL")]
      (is (ds/dataset? ds))
      (is (pos? (ds/row-count ds)))
      (is (contains? (set (ds/column-names ds)) :concept))
      (is (contains? (set (ds/column-names ds)) :label)))))

;; ---------------------------------------------------------------------------
;; Financial statements
;; ---------------------------------------------------------------------------

(deftest income-statement-test
  (testing "income statement returns a non-empty dataset"
    (let [ds (e/income "AAPL")]
      (is (ds/dataset? ds))
      (is (pos? (ds/row-count ds)))))
  (testing "income statement :shape :wide returns a dataset"
    (let [ds (e/income "AAPL" :shape :wide)]
      (is (ds/dataset? ds))
      (is (pos? (ds/row-count ds)))))
  (testing ":as-of returns fewer or equal rows than default"
    (let [full (e/income "AAPL")
          pit (e/income "AAPL" :as-of "2020-01-01")]
      (is (<= (ds/row-count pit) (ds/row-count full))))))

(deftest balance-sheet-test
  (testing "balance sheet returns a non-empty dataset"
    (let [ds (e/balance "AAPL")]
      (is (ds/dataset? ds))
      (is (pos? (ds/row-count ds))))))

(deftest cashflow-test
  (testing "cash flow returns a non-empty dataset"
    (let [ds (e/cashflow "AAPL")]
      (is (ds/dataset? ds))
      (is (pos? (ds/row-count ds))))))

;; ---------------------------------------------------------------------------
;; Panel
;; ---------------------------------------------------------------------------

(deftest panel-test
  (testing "panel returns a combined dataset with :ticker column"
    (let [ds (e/panel ["AAPL" "MSFT"] :concept "Assets" :form "10-K")]
      (is (ds/dataset? ds))
      (is (pos? (ds/row-count ds)))
      (is (contains? (set (ds/column-names ds)) :ticker)))))

;; ---------------------------------------------------------------------------
;; Rate limiter — verify no explosion on burst
;; ---------------------------------------------------------------------------

(deftest rate-limiter-burst-test
  (testing "three sequential CIK lookups complete without error"
    (is (= "0000320193" (e/cik "AAPL")))
    (is (= "0000789019" (e/cik "MSFT")))
    (is (= "0001652044" (e/cik "GOOGL")))))
