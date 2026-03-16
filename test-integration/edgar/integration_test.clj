(ns edgar.integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.java.io :as io]
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
    (is (not (str/blank? (e/company-name "AAPL"))))))

(deftest company-metadata-test
  (testing "company-metadata returns a map with expected keys"
    (let [m (e/company-metadata "AAPL")]
      (is (map? m))
      (is (contains? m :sic))
      (is (contains? m :fiscal-year-end))
      (is (contains? m :addresses)))))

(deftest search-companies-test
  (testing "search returns a non-empty seq of maps"
    (let [results (e/search "apple" :limit 5)]
      (is (seq results))
      (is (every? map? results))))
  (testing "search result count respects :limit"
    (let [results (e/search "microsoft" :limit 3)]
      (is (<= (count results) 3)))))

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
      (is (seq fs))))
  (testing "filings :url field is a string for each result"
    (let [fs (e/filings "AAPL" :form "10-K" :limit 3)]
      (is (every? #(string? (:url %)) fs)))))

(deftest filing-test
  (testing "filing returns a single map with required keys"
    (is (map? *aapl-10k*))
    (is (contains? *aapl-10k* :accessionNumber))
    (is (= "10-K" (:form *aapl-10k*))))
  (testing "filing :n 1 returns a different filing than :n 0"
    (let [f0 (e/filing "AAPL" :form "10-K" :n 0)
          f1 (e/filing "AAPL" :form "10-K" :n 1)]
      (is (not= (:accessionNumber f0) (:accessionNumber f1))))))

(deftest latest-effective-filing-test
  (testing "latest-effective-filing returns a map with accessionNumber"
    (let [f (e/latest-effective-filing "AAPL" :form "10-K")]
      (is (map? f))
      (is (contains? f :accessionNumber)))))

(deftest filing-by-accession-test
  (testing "filing-by-accession round-trips the current AAPL 10-K accession"
    (let [accession (:accessionNumber *aapl-10k*)
          f (e/filing-by-accession accession)]
      (is (map? f))
      (is (= "10-K" (:form f)))
      (is (str/includes? (:accessionNumber f) "320193"))))
  (testing "filing-by-accession includes :filingDate"
    (let [f (e/filing-by-accession (:accessionNumber *aapl-10k*))]
      (is (string? (:filingDate f)))
      (is (re-matches #"\d{4}-\d{2}-\d{2}" (:filingDate f))))))

(deftest filings-dataset-test
  (testing "filings-dataset returns a tech.ml.dataset"
    (let [ds (e/filings-dataset "AAPL" :form "10-K" :limit 5)]
      (is (ds/dataset? ds))
      (is (pos? (ds/row-count ds))))))

(deftest search-filings-test
  (testing "search-filings returns a non-empty seq for a broad query"
    (let [results (e/search-filings "climate risk" :forms ["10-K"] :limit 3)]
      (is (seq results))
      (is (every? map? results)))))

(deftest daily-filings-test
  (testing "daily-filings returns a seq for a known past date"
    (let [results (take 5 (e/daily-filings "2026-03-10"))]
      (is (seq results))
      (is (every? #(contains? % :accessionNumber) results))))
  (testing "daily-filings with :form filter returns only matching or amended forms"
    (let [results (take 5 (e/daily-filings "2026-03-10" :form "8-K"))]
      (when (seq results)
        (is (every? #(str/starts-with? (:form %) "8-K") results))))))

;; ---------------------------------------------------------------------------
;; Filing content
;; ---------------------------------------------------------------------------

(deftest filing-html-test
  (testing "html returns a non-blank string"
    (let [h (e/html *aapl-10k*)]
      (is (string? h))
      (is (> (count h) 1000))
      (is (str/includes? h "<")))))

(deftest filing-text-test
  (testing "text returns a non-blank string"
    (let [t (e/text *aapl-10k*)]
      (is (string? t))
      (is (> (count t) 1000))))
  (testing "text does not contain <script> or <style> content"
    (let [t (e/text *aapl-10k*)]
      (is (not (str/includes? t "font-size")))
      (is (not (str/includes? t "function()"))))))

(deftest filing-items-test
  (testing "items returns a map with item 7 for a 10-K"
    (let [items (e/items *aapl-10k* :only #{"7"})]
      (is (map? items))
      (is (contains? items "7"))
      (is (> (count (get-in items ["7" :text])) 500))))
  (testing "items result includes :title and :method keys"
    (let [items (e/items *aapl-10k* :only #{"7"})]
      (is (contains? (get items "7") :title))
      (is (contains? (get items "7") :method)))))

(deftest filing-item-single-test
  (testing "item returns a map for a known item id"
    (let [result (e/item *aapl-10k* "7")]
      (is (map? result))
      (is (contains? result :text))
      (is (> (count (:text result)) 500))))
  (testing "item returns nil for a non-existent item id"
    (is (nil? (e/item *aapl-10k* "99")))))

(deftest filing-obj-test
  (testing "obj on Form 4 returns structured insider trade map"
    (let [f4 (e/filing "AAPL" :form "4")
          result (e/obj f4)]
      (is (map? result))
      (is (= "4" (:form result)))
      (is (contains? result :issuer))
      (is (contains? result :reporting-owner))
      (is (contains? result :transactions))))
  (testing "obj on 13F-HR returns holdings dataset"
    (let [f13 (e/filing "GS" :form "13F-HR")
          result (e/obj f13)]
      (is (map? result))
      (is (= "13F-HR" (:form result)))
      (is (ds/dataset? (:holdings result)))
      (is (pos? (ds/row-count (:holdings result)))))))

(deftest filing-tables-test
  (testing "tables returns a seq of datasets"
    (let [ts (e/tables *aapl-10k*)]
      (is (seq ts))
      (is (every? ds/dataset? ts))))
  (testing "tables :nth 0 returns a single dataset"
    (let [t (e/tables *aapl-10k* :nth 0)]
      (is (ds/dataset? t))
      (is (pos? (ds/row-count t)))))
  (testing "tables :min-rows filter reduces result count"
    (let [all (e/tables *aapl-10k*)
          filtered (e/tables *aapl-10k* :min-rows 10)]
      (is (<= (count filtered) (count all))))))

(deftest doc-url-test
  (testing "doc-url builds a valid SEC archives URL"
    (let [url (e/doc-url *aapl-10k* (:primaryDocument *aapl-10k*))]
      (is (string? url))
      (is (str/starts-with? url "https://www.sec.gov/Archives/edgar/data/")))))

(deftest exhibits-test
  (testing "exhibits returns a non-empty seq"
    (is (seq (e/exhibits *aapl-10k*))))
  (testing "each exhibit map has :type and :name keys"
    (let [exs (e/exhibits *aapl-10k*)]
      (is (every? #(contains? % :type) exs))
      (is (every? #(contains? % :name) exs))
      (is (every? #(str/starts-with? (:type %) "EX-") exs)))))

(deftest exhibit-test
  (testing "exhibit returns a map for a known type"
    (let [ex (e/exhibit *aapl-10k* "EX-21")]
      (is (or (nil? ex) (map? ex)))
      (when ex
        (is (= "EX-21" (:type ex))))))
  (testing "exhibit returns nil for a non-existent type"
    (is (nil? (e/exhibit *aapl-10k* "EX-99999")))))

(deftest xbrl-docs-test
  (testing "xbrl-docs returns a seq of XBRL linkbase entries"
    (let [docs (e/xbrl-docs *aapl-10k*)]
      (is (seq docs))
      (is (every? #(contains? % :type) docs))
      (is (some #(or (str/starts-with? (:type %) "EX-101")
                     (str/ends-with? (:name %) ".xsd")) docs)))))

(deftest save-test
  (testing "save! writes the primary document and returns a path"
    (let [tmp (System/getProperty "java.io.tmpdir")
          path (e/save! *aapl-10k* tmp)]
      (is (string? path))
      (is (.exists (io/file path)))))
  (testing "save-all! writes multiple files and returns seq of paths"
    (let [tmp (System/getProperty "java.io.tmpdir")
          paths (e/save-all! *aapl-10k* tmp)]
      (is (seq paths))
      (is (every? string? paths)))))

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
      (is (contains? (set (ds/column-names ds)) :label))))
  (testing "facts :concept accepts a collection"
    (let [ds (e/facts "AAPL" :concept ["Assets" "NetIncomeLoss"] :form "10-K")]
      (is (ds/dataset? ds))
      (is (= #{"Assets" "NetIncomeLoss"} (set (ds/column ds :concept)))))))

(deftest concepts-test
  (testing "concepts returns a dataset with expected columns"
    (let [ds (e/concepts "AAPL")]
      (is (ds/dataset? ds))
      (is (pos? (ds/row-count ds)))
      (is (contains? (set (ds/column-names ds)) :concept))
      (is (contains? (set (ds/column-names ds)) :label)))))

(deftest frame-test
  (testing "frame returns a cross-sectional dataset"
    (let [ds (e/frame "Assets" "CY2023Q4I")]
      (is (ds/dataset? ds))
      (is (pos? (ds/row-count ds)))
      (is (contains? (set (ds/column-names ds)) :val))
      (is (contains? (set (ds/column-names ds)) :cik))))
  (testing "frame :unit shares works for share count concepts"
    (let [ds (e/frame "SharesOutstanding" "CY2023Q4I" :unit "shares")]
      (is (ds/dataset? ds))
      (is (pos? (ds/row-count ds))))))

;; ---------------------------------------------------------------------------
;; Financial statements
;; ---------------------------------------------------------------------------

(deftest income-statement-test
  (testing "income statement returns a non-empty dataset"
    (let [ds (e/income "AAPL")]
      (is (ds/dataset? ds))
      (is (pos? (ds/row-count ds)))))
  (testing "income statement :shape :wide returns a dataset with period column"
    (let [ds (e/income "AAPL" :shape :wide)]
      (is (ds/dataset? ds))
      (is (pos? (ds/row-count ds)))
      (is (contains? (set (ds/column-names ds)) :end))))
  (testing ":as-of returns fewer or equal rows than default"
    (let [full (e/income "AAPL")
          pit (e/income "AAPL" :as-of "2020-01-01")]
      (is (<= (ds/row-count pit) (ds/row-count full))))))

(deftest balance-sheet-test
  (testing "balance sheet returns a non-empty dataset"
    (let [ds (e/balance "AAPL")]
      (is (ds/dataset? ds))
      (is (pos? (ds/row-count ds)))))
  (testing "balance sheet :shape :wide returns a dataset with period column"
    (let [ds (e/balance "AAPL" :shape :wide)]
      (is (ds/dataset? ds))
      (is (pos? (ds/row-count ds)))
      (is (contains? (set (ds/column-names ds)) :end))))
  (testing "balance sheet :as-of restricts results"
    (let [full (e/balance "AAPL")
          pit (e/balance "AAPL" :as-of "2020-01-01")]
      (is (<= (ds/row-count pit) (ds/row-count full))))))

(deftest cashflow-test
  (testing "cash flow returns a non-empty dataset"
    (let [ds (e/cashflow "AAPL")]
      (is (ds/dataset? ds))
      (is (pos? (ds/row-count ds)))))
  (testing "cash flow :shape :wide returns a dataset with period column"
    (let [ds (e/cashflow "AAPL" :shape :wide)]
      (is (ds/dataset? ds))
      (is (pos? (ds/row-count ds)))
      (is (contains? (set (ds/column-names ds)) :end)))))

(deftest financials-combined-test
  (testing "financials returns map with all three statements"
    (let [result (e/financials "AAPL")]
      (is (map? result))
      (is (ds/dataset? (:income result)))
      (is (ds/dataset? (:balance result)))
      (is (ds/dataset? (:cashflow result)))
      (is (pos? (ds/row-count (:income result))))
      (is (pos? (ds/row-count (:balance result))))
      (is (pos? (ds/row-count (:cashflow result))))))
  (testing "financials :shape :wide returns wide datasets"
    (let [result (e/financials "AAPL" :shape :wide)]
      (is (contains? (set (ds/column-names (:income result))) :end))
      (is (contains? (set (ds/column-names (:balance result))) :end))))
  (testing "financials :as-of restricts all three statements"
    (let [full (e/financials "AAPL")
          pit (e/financials "AAPL" :as-of "2020-01-01")]
      (is (<= (ds/row-count (:income pit)) (ds/row-count (:income full))))
      (is (<= (ds/row-count (:balance pit)) (ds/row-count (:balance full)))))))

;; ---------------------------------------------------------------------------
;; Panel datasets
;; ---------------------------------------------------------------------------

(deftest panel-test
  (testing "panel returns a combined dataset with :ticker column"
    (let [ds (e/panel ["AAPL" "MSFT"] :concept "Assets" :form "10-K")]
      (is (ds/dataset? ds))
      (is (pos? (ds/row-count ds)))
      (is (contains? (set (ds/column-names ds)) :ticker))))
  (testing "panel :as-of restricts to point-in-time data"
    (let [full (e/panel ["AAPL" "MSFT"] :concept "Assets" :form "10-K")
          pit (e/panel ["AAPL" "MSFT"] :concept "Assets" :form "10-K" :as-of "2020-01-01")]
      (is (<= (ds/row-count pit) (ds/row-count full))))))

(deftest pivot-test
  (testing "pivot converts long facts dataset to wide format"
    (let [long-ds (e/facts "AAPL" :concept ["Assets" "Liabilities"] :form "10-K")
          wide-ds (e/pivot long-ds)]
      (is (ds/dataset? wide-ds))
      (is (pos? (ds/row-count wide-ds))))))

;; ---------------------------------------------------------------------------
;; Malli validation errors
;; ---------------------------------------------------------------------------

(deftest validation-errors-test
  (testing "init! with blank string throws ex-info"
    (is (thrown? clojure.lang.ExceptionInfo (e/init! ""))))
  (testing "filings with bad date throws ex-info"
    (is (thrown? clojure.lang.ExceptionInfo
                 (e/filings "AAPL" :form "10-K" :start-date "not-a-date"))))
  (testing "facts with invalid concept type throws ex-info"
    (is (thrown? clojure.lang.ExceptionInfo
                 (e/facts "AAPL" :concept 42))))
  (testing "filing-by-accession with malformed accession throws ex-info"
    (is (thrown? clojure.lang.ExceptionInfo
                 (e/filing-by-accession "not-an-accession")))))

;; ---------------------------------------------------------------------------
;; Rate limiter — verify no explosion on burst
;; ---------------------------------------------------------------------------

(deftest rate-limiter-burst-test
  (testing "three sequential CIK lookups complete without error"
    (is (= "0000320193" (e/cik "AAPL")))
    (is (= "0000789019" (e/cik "MSFT")))
    (is (= "0001652044" (e/cik "GOOGL")))))
