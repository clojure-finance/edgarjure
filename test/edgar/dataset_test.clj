(ns edgar.dataset-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.dataset :as dataset]
            [edgar.company :as company]
            [edgar.xbrl :as xbrl]
            [tech.v3.dataset :as ds]))

;;; ---------------------------------------------------------------------------
;;; multi-company-facts — guard against empty / failing tickers
;;; These tests stub out the HTTP layer by patching company/company-cik
;;; and xbrl/get-facts-dataset via with-redefs.
;;; ---------------------------------------------------------------------------

(deftest multi-company-facts-empty-tickers
  (testing "returns empty dataset for empty tickers vector"
    (let [result (dataset/multi-company-facts [])]
      (is (= 0 (ds/row-count result))))))

(deftest multi-company-facts-all-tickers-fail
  (testing "returns empty dataset when every ticker throws"
    (with-redefs [edgar.company/company-cik (fn [_] (throw (ex-info "not found" {})))]
      (let [result (dataset/multi-company-facts ["BADINPUT" "ALSOBAD"])]
        (is (= 0 (ds/row-count result)))))))

(deftest multi-company-facts-partial-failure
  (testing "returns rows only for tickers that succeed; failed tickers are skipped"
    (with-redefs [edgar.company/company-cik (fn [t] t)
                  edgar.xbrl/get-facts-dataset
                  (fn [cik & _]
                    (if (= cik "GOOD")
                      (ds/->dataset [{:taxonomy "us-gaap" :concept "Assets"
                                      :unit "USD" :end "2023-12-31"
                                      :val 1000 :accn "0001-23-000001"
                                      :fy 2023 :fp "FY" :form "10-K"
                                      :filed "2024-01-15" :frame "CY2023Q4I"
                                      :label "Assets" :description "Total assets"}])
                      (throw (ex-info "fetch failed" {}))))]
      (let [result (dataset/multi-company-facts ["GOOD" "BAD"])]
        (is (= 1 (ds/row-count result)))
        (is (= ["GOOD"] (vec (get result :ticker))))))))

(deftest multi-company-facts-as-of-dedup-test
  (testing "as-of path deduplicates per [ticker concept unit start end] keeping most recently filed"
    (with-redefs [edgar.company/company-cik (fn [t] t)
                  edgar.xbrl/get-facts-dataset
                  (fn [cik & _]
                    (ds/->dataset
                     [{:taxonomy "us-gaap" :concept "Assets" :unit "USD"
                       :start nil :end "2023-12-31" :val 900 :accn "0001-23-000001"
                       :fy 2023 :fp "FY" :form "10-K"
                       :filed "2023-11-01" :frame nil
                       :label "Assets" :description ""}
                      {:taxonomy "us-gaap" :concept "Assets" :unit "USD"
                       :start nil :end "2023-12-31" :val 1000 :accn "0001-24-000001"
                       :fy 2023 :fp "FY" :form "10-K"
                       :filed "2024-01-15" :frame nil
                       :label "Assets" :description ""}]))]
      ;; as-of before the second filing: only the first should survive
      (let [result (dataset/multi-company-facts ["AAPL"] :concept "Assets" :as-of "2023-12-31")]
        (is (= 1 (ds/row-count result)))
        (is (= [900] (vec (ds/column result :val)))))))
  (testing "as-of nil returns all rows unfiltered"
    (with-redefs [edgar.company/company-cik (fn [t] t)
                  edgar.xbrl/get-facts-dataset
                  (fn [cik & _]
                    (ds/->dataset
                     [{:taxonomy "us-gaap" :concept "Assets" :unit "USD"
                       :start nil :end "2022-12-31" :val 800 :accn "0001-22-000001"
                       :fy 2022 :fp "FY" :form "10-K"
                       :filed "2022-11-01" :frame nil
                       :label "Assets" :description ""}
                      {:taxonomy "us-gaap" :concept "Assets" :unit "USD"
                       :start nil :end "2023-12-31" :val 1000 :accn "0001-23-000001"
                       :fy 2023 :fp "FY" :form "10-K"
                       :filed "2023-11-01" :frame nil
                       :label "Assets" :description ""}]))]
      (let [result (dataset/multi-company-facts ["AAPL"] :concept "Assets")]
        (is (= 2 (ds/row-count result))))))
  (testing "distinct duration windows (same concept+end, different :start) are preserved"
    (with-redefs [edgar.company/company-cik (fn [t] t)
                  edgar.xbrl/get-facts-dataset
                  (fn [cik & _]
                    (ds/->dataset
                     [{:taxonomy "us-gaap" :concept "Revenue" :unit "USD"
                       :start "2023-07-01" :end "2023-09-30" :val 300 :accn "0001-23-000001"
                       :fy 2023 :fp "Q3" :form "10-Q"
                       :filed "2023-11-01" :frame nil
                       :label "Revenue" :description ""}
                      {:taxonomy "us-gaap" :concept "Revenue" :unit "USD"
                       :start "2023-01-01" :end "2023-09-30" :val 900 :accn "0001-23-000001"
                       :fy 2023 :fp "Q3" :form "10-Q"
                       :filed "2023-11-01" :frame nil
                       :label "Revenue" :description ""}]))]
      (let [result (dataset/multi-company-facts ["AAPL"] :concept "Revenue" :form "10-Q" :as-of "2024-01-01")]
        (is (= 2 (ds/row-count result)) "3-month and 9-month windows must not be collapsed")))))

(deftest pivot-wide-test
  (let [f dataset/pivot-wide
        rows [{:end "2023-09-30" :concept "Assets" :val 100 :frame nil :start nil}
              {:end "2023-09-30" :concept "Liabilities" :val 50 :frame nil :start nil}
              {:end "2022-09-30" :concept "Assets" :val 90 :frame nil :start nil}]
        ds-in (ds/->dataset rows)
        result (f ds-in)]
    (testing "returns a dataset"
      (is (instance? tech.v3.dataset.impl.dataset.Dataset result)))
    (testing "one row per :end period"
      (is (= 2 (ds/row-count result))))
    (testing "concept names become columns"
      (let [cols (set (map name (ds/column-names result)))]
        (is (contains? cols "end"))
        (is (contains? cols "Assets"))
        (is (contains? cols "Liabilities"))))
    (testing "nil-valued columns (frame, start) do not cause missing-key errors"
      (is (= 2 (ds/row-count result))))))
