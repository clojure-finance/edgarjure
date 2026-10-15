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
  (let [restated [{:taxonomy "us-gaap" :concept "Assets" :unit "USD"
                   :start nil :end "2023-12-31" :val 900 :accn "0001-23-000001"
                   :fy 2023 :fp "FY" :form "10-K"
                   :filed "2023-11-01" :frame nil
                   :label "Assets" :description ""}
                  {:taxonomy "us-gaap" :concept "Assets" :unit "USD"
                   :start nil :end "2023-12-31" :val 1000 :accn "0001-24-000001"
                   :fy 2023 :fp "FY" :form "10-K"
                   :filed "2024-01-15" :frame nil
                   :label "Assets" :description ""}]]
    (with-redefs [edgar.company/company-cik (fn [t] t)
                  edgar.xbrl/get-facts-dataset (fn [_ & _] (ds/->dataset restated))]
      (testing "as-of before the second filing: only the first survives the filter"
        (let [result (dataset/multi-company-facts ["AAPL"] :concept "Assets" :as-of "2023-12-31")]
          (is (= 1 (ds/row-count result)))
          (is (= [900] (vec (ds/column result :val))))))
      (testing "as-of after both filings: dedup keeps the most recently filed"
        (let [result (dataset/multi-company-facts ["AAPL"] :concept "Assets" :as-of "2024-12-31")]
          (is (= 1 (ds/row-count result)))
          (is (= [1000] (vec (ds/column result :val))))))
      (testing "dedup is per ticker: the same fact under two tickers is kept twice"
        (let [result (dataset/multi-company-facts ["AAPL" "MSFT"] :concept "Assets"
                                                  :as-of "2024-12-31")]
          (is (= 2 (ds/row-count result)))
          (is (= #{"AAPL" "MSFT"} (set (ds/column result :ticker))))))))
  (testing "as-of nil returns all rows unfiltered"
    (with-redefs [edgar.company/company-cik (fn [t] t)
                  edgar.xbrl/get-facts-dataset
                  (fn [_ & _]
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
                  (fn [_ & _]
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

(deftest add-market-cap-rank-test
  ;; Regression: the old implementation passed an options map where a
  ;; comparator was expected and called ds/add-column with a wrong arity —
  ;; it threw on every invocation.
  (let [ds-in (ds/->dataset [{:company "B" :cap 3}
                             {:company "A" :cap 10}
                             {:company "C" :cap 1}])
        result (dataset/add-market-cap-rank ds-in :cap)
        rows (vec (ds/rows result {:nil-missing? true}))]
    (testing "sorted descending by the size column"
      (is (= [10 3 1] (mapv :cap rows))))
    (testing ":rank column runs 1..n with 1 = largest"
      (is (= [1 2 3] (mapv :rank rows)))
      (is (= "A" (:company (first rows)))))))

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
      (is (= 2 (ds/row-count result))))
    (testing "rows sorted by :end descending"
      (is (= ["2023-09-30" "2022-09-30"] (mapv str (ds/column result :end)))))
    (testing "empty input returns an empty dataset"
      (is (= 0 (ds/row-count (f (ds/->dataset []))))))))

(defn- cell [result end col & [ticker]]
  (get (first (filter #(and (= end (str (:end %)))
                            (or (nil? ticker) (= ticker (:ticker %))))
                      (ds/rows result {:nil-missing? true})))
       col))

(deftest pivot-wide-duration-preference-test
  ;; regression (AAPL live): a 10-K's facts carry the fiscal-year figure and
  ;; quarterly-footnote figures sharing its end date; the old first-row pick
  ;; returned the Q4 value for FY2018 Net Income
  (let [rows [{:concept "NetIncomeLoss" :unit "USD" :start "2018-07-01" :end "2018-09-29"
               :val 14125.0 :filed "2018-11-05"}
              {:concept "NetIncomeLoss" :unit "USD" :start "2017-10-01" :end "2018-09-29"
               :val 59531.0 :filed "2018-11-05"}
              ;; the same fiscal year restated in a later 10-K
              {:concept "NetIncomeLoss" :unit "USD" :start "2017-10-01" :end "2018-09-29"
               :val 59500.0 :filed "2020-10-30"}]
        result (dataset/pivot-wide (ds/->dataset rows))]
    (testing "the longest window wins, and among equal windows the latest filing"
      (is (= 1 (ds/row-count result)))
      (is (= 59500.0 (cell result "2018-09-29" "NetIncomeLoss"))))
    (testing "row order does not matter"
      (is (= 59500.0 (cell (dataset/pivot-wide (ds/->dataset (reverse rows)))
                           "2018-09-29" "NetIncomeLoss"))))))

(deftest pivot-wide-panel-and-units-test
  (testing "panel input keys rows by [ticker end]: no company is dropped"
    ;; regression (live): (e/pivot (e/panel ["AMZN" "JPM"] :concept "Assets"))
    ;; dropped JPM because both firms share December fiscal-year ends
    (let [rows [{:ticker "AMZN" :concept "Assets" :unit "USD" :start nil
                 :end "2024-12-31" :val 624.9 :filed "2025-02-07"}
                {:ticker "JPM" :concept "Assets" :unit "USD" :start nil
                 :end "2024-12-31" :val 4002.8 :filed "2025-02-14"}]
          result (dataset/pivot-wide (ds/->dataset rows))]
      (is (= 2 (ds/row-count result)))
      (is (= ["AMZN" "JPM"] (vec (ds/column result :ticker))))
      (is (= 624.9 (cell result "2024-12-31" "Assets" "AMZN")))
      (is (= 4002.8 (cell result "2024-12-31" "Assets" "JPM")))))
  (testing "a concept reported in several units gets one column per unit"
    (let [rows [{:concept "Revenues" :unit "USD" :start "2023-01-01" :end "2023-12-31"
                 :val 100.0 :filed "2024-02-01"}
                {:concept "Revenues" :unit "EUR" :start "2023-01-01" :end "2023-12-31"
                 :val 92.0 :filed "2024-02-01"}
                {:concept "Assets" :unit "USD" :start nil :end "2023-12-31"
                 :val 500.0 :filed "2024-02-01"}]
          result (dataset/pivot-wide (ds/->dataset rows))]
      (is (= 100.0 (cell result "2023-12-31" "Revenues (USD)")))
      (is (= 92.0 (cell result "2023-12-31" "Revenues (EUR)")))
      (is (= 500.0 (cell result "2023-12-31" "Assets")) "single-unit concepts keep plain names")
      (is (not (contains? (set (ds/column-names result)) "Revenues"))))))

(deftest multi-company-facts-empty-with-as-of-test
  (testing "empty ticker list with :as-of returns an empty dataset instead of throwing"
    (let [result (dataset/multi-company-facts [] :as-of "2024-01-01")]
      (is (= 0 (ds/row-count result)))))
  (testing "all-failing tickers with :as-of also return empty"
    (with-redefs [edgar.company/company-cik (fn [_] (throw (ex-info "not found" {})))]
      (let [result (dataset/multi-company-facts ["BAD"] :as-of "2024-01-01")]
        (is (= 0 (ds/row-count result)))))))
