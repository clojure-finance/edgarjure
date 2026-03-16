(ns edgar.financials-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.financials :as fin]
            [tech.v3.dataset :as ds]))

;;; ---------------------------------------------------------------------------
;;; instant? / duration?
;;; ---------------------------------------------------------------------------

(deftest instant-test
  (let [instant? #'edgar.financials/instant?]
    (testing "row without :start is instant (balance sheet)"
      (is (instant? {:end "2023-09-30" :val 100})))
    (testing "row with :start is not instant (duration)"
      (is (not (instant? {:start "2023-07-01" :end "2023-09-30" :val 100}))))
    (testing "row with nil :start is instant"
      (is (instant? {:start nil :end "2023-09-30" :val 100})))))

(deftest duration-test
  (let [duration? #'edgar.financials/duration?]
    (testing "row with :start is duration (income/cashflow)"
      (is (duration? {:start "2023-07-01" :end "2023-09-30" :val 100})))
    (testing "row without :start is not duration"
      (is (not (duration? {:end "2023-09-30" :val 100}))))
    (testing "row with nil :start is not duration"
      (is (not (duration? {:start nil :end "2023-09-30" :val 100}))))))

;;; ---------------------------------------------------------------------------
;;; resolve-fallback
;;; ---------------------------------------------------------------------------

(deftest resolve-fallback-test
  (let [f #'edgar.financials/resolve-fallback]
    (testing "returns [label winner] when first candidate is available"
      (is (= ["Revenue" "Revenues"]
             (f ["Revenue" "Revenues" "SalesRevenueNet"]
                #{"Revenues" "SalesRevenueNet"}))))
    (testing "falls back to second candidate when first is absent"
      (is (= ["Revenue" "SalesRevenueNet"]
             (f ["Revenue" "Revenues" "SalesRevenueNet"]
                #{"SalesRevenueNet"}))))
    (testing "returns nil when no candidate is available"
      (is (nil? (f ["Revenue" "Revenues" "SalesRevenueNet"] #{}))))
    (testing "returns nil when available set is empty"
      (is (nil? (f ["Revenue" "Revenues"] #{}))))))

;;; ---------------------------------------------------------------------------
;;; resolve-all-chains
;;; ---------------------------------------------------------------------------

(deftest resolve-all-chains-test
  (let [f #'edgar.financials/resolve-all-chains
        chains [["Revenue" "Revenues" "SalesRevenueNet"]
                ["Net Income" "NetIncomeLoss" "ProfitLoss"]
                ["Missing" "ConceptNotPresent"]]]
    (testing "resolved chains for available concepts"
      (let [result (f chains #{"Revenues" "NetIncomeLoss"})]
        (is (= 2 (count result)))
        (is (some #(= ["Revenue" "Revenues"] %) result))
        (is (some #(= ["Net Income" "NetIncomeLoss"] %) result))))
    (testing "chain with no available concepts is omitted"
      (let [result (f chains #{"Revenues" "NetIncomeLoss"})]
        (is (every? #(not= "Missing" (first %)) result))))))

;;; ---------------------------------------------------------------------------
;;; dedup-restatements
;;; ---------------------------------------------------------------------------

(deftest dedup-restatements-test
  (let [f #'edgar.financials/dedup-restatements]
    (testing "keeps most-recently-filed row per [concept unit start end]"
      (let [rows [{:concept "Assets" :unit "USD" :start nil :end "2023-09-30" :filed "2023-11-03" :val 100}
                  {:concept "Assets" :unit "USD" :start nil :end "2023-09-30" :filed "2024-01-15" :val 105}
                  {:concept "Assets" :unit "USD" :start nil :end "2022-09-30" :filed "2022-10-28" :val 90}]
            result (vec (f rows))]
        (is (= 2 (count result)))
        (is (some #(= 105 (:val %)) result))
        (is (some #(= 90 (:val %)) result))
        (is (not (some #(= 100 (:val %)) result)))))
    (testing "distinct duration windows preserved — same concept+end, different :start"
      (let [rows [{:concept "Revenue" :unit "USD" :start "2023-07-01" :end "2023-09-30" :filed "2023-11-03" :val 300}
                  {:concept "Revenue" :unit "USD" :start "2023-01-01" :end "2023-09-30" :filed "2023-11-03" :val 900}]
            result (vec (f rows))]
        (is (= 2 (count result)) "3-month and 9-month windows must not be collapsed")))
    (testing "no duplicates → same count"
      (let [unique-rows [{:concept "A" :unit "USD" :start nil :end "2023-09-30" :filed "2023-11-03" :val 1}
                         {:concept "B" :unit "USD" :start nil :end "2023-09-30" :filed "2023-11-03" :val 2}]
            result (vec (f unique-rows))]
        (is (= 2 (count result)))))))

;;; ---------------------------------------------------------------------------
;;; dedup-point-in-time
;;; ---------------------------------------------------------------------------

(deftest dedup-point-in-time-test
  (let [f #'edgar.financials/dedup-point-in-time]
    (testing "as-of nil behaves like dedup-restatements (latest filed wins)"
      (let [rows [{:concept "Assets" :unit "USD" :start nil :end "2023-09-30" :filed "2023-11-03" :val 100}
                  {:concept "Assets" :unit "USD" :start nil :end "2023-09-30" :filed "2024-01-15" :val 105}
                  {:concept "Assets" :unit "USD" :start nil :end "2022-09-30" :filed "2022-10-28" :val 90}]
            result (vec (f rows nil))]
        (is (= 2 (count result)))
        (is (some #(= 105 (:val %)) result))))
    (testing "as-of before the restatement excludes the restatement"
      (let [rows [{:concept "Assets" :unit "USD" :start nil :end "2023-09-30" :filed "2023-11-03" :val 100}
                  {:concept "Assets" :unit "USD" :start nil :end "2023-09-30" :filed "2024-01-15" :val 105}
                  {:concept "Assets" :unit "USD" :start nil :end "2022-09-30" :filed "2022-10-28" :val 90}]
            result (vec (f rows "2023-12-31"))]
        (is (= 2 (count result)))
        (is (some #(= 100 (:val %)) result))
        (is (not (some #(= 105 (:val %)) result)))))
    (testing "as-of before all rows returns empty"
      (let [rows [{:concept "Assets" :unit "USD" :start nil :end "2023-09-30" :filed "2023-11-03" :val 100}]
            result (vec (f rows "2022-01-01"))]
        (is (empty? result))))
    (testing "distinct duration windows preserved under point-in-time"
      (let [rows [{:concept "Revenue" :unit "USD" :start "2023-07-01" :end "2023-09-30" :filed "2023-11-03" :val 300}
                  {:concept "Revenue" :unit "USD" :start "2023-01-01" :end "2023-09-30" :filed "2023-11-03" :val 900}]
            result (vec (f rows "2024-01-01"))]
        (is (= 2 (count result)) "3-month and 9-month windows must not be collapsed")))))

;;; ---------------------------------------------------------------------------
;;; to-wide
;;; ---------------------------------------------------------------------------

(deftest to-wide-test
  (let [f #'edgar.financials/to-wide
        rows [{:end "2023-09-30" :line-item "Revenue" :val 100}
              {:end "2023-09-30" :line-item "Net Income" :val 20}
              {:end "2022-09-30" :line-item "Revenue" :val 90}
              {:end "2022-09-30" :line-item "Net Income" :val 15}]
        long-ds (ds/->dataset rows)]
    (testing "returns a dataset"
      (is (instance? tech.v3.dataset.impl.dataset.Dataset (f long-ds))))
    (testing "one row per period"
      (is (= 2 (ds/row-count (f long-ds)))))
    (testing "columns include :end plus one per line-item"
      (let [cols (set (map name (ds/column-names (f long-ds))))]
        (is (contains? cols "end"))
        (is (contains? cols "Revenue"))
        (is (contains? cols "Net Income"))))
    (testing "empty dataset returns empty dataset"
      (is (= 0 (ds/row-count (f (ds/->dataset []))))))))

(deftest to-wide-nil-columns-test
  (let [f #'edgar.financials/to-wide
        rows [{:end "2023-09-30" :line-item "Total Assets" :val 100 :start nil :frame nil}
              {:end "2023-09-30" :line-item "Current Assets" :val 40 :start nil :frame nil}
              {:end "2022-09-30" :line-item "Total Assets" :val 90 :start nil :frame nil}]
        long-ds (ds/->dataset rows)]
    (testing "nil-valued columns do not cause missing-key errors"
      (is (= 2 (ds/row-count (f long-ds)))))
    (testing "line-item columns are present when nil columns exist"
      (let [cols (set (map name (ds/column-names (f long-ds))))]
        (is (contains? cols "Total Assets"))
        (is (contains? cols "Current Assets"))))))

(deftest concepts-in-data-test
  (let [f #'edgar.financials/concepts-in-data]
    (testing "returns set of concept strings"
      (let [ds (ds/->dataset [{:concept "Assets" :val 1}
                              {:concept "Assets" :val 2}
                              {:concept "Liabilities" :val 3}])]
        (is (= #{"Assets" "Liabilities"} (f ds)))))
    (testing "returns empty set for empty dataset"
      (is (= #{} (f (ds/->dataset [])))))))

(deftest filter-by-duration-type-test
  (let [f #'edgar.financials/filter-by-duration-type
        instant-row {:end "2023-09-30" :val 100}
        duration-row {:start "2023-07-01" :end "2023-09-30" :val 200}
        rows [instant-row duration-row]]
    (testing ":instant keeps only rows without :start"
      (is (= [instant-row] (vec (f rows :instant)))))
    (testing ":duration keeps only rows with :start"
      (is (= [duration-row] (vec (f rows :duration)))))
    (testing ":any keeps all rows"
      (is (= rows (vec (f rows :any)))))))

(deftest add-line-item-col-test
  (let [f #'edgar.financials/add-line-item-col
        input-ds (ds/->dataset [{:concept "Assets" :val 100}
                                {:concept "Liabilities" :val 50}
                                {:concept "Unknown" :val 10}])
        concept->label {"Assets" "Total Assets" "Liabilities" "Total Liabilities"}
        result (f input-ds concept->label)]
    (testing "adds :line-item column"
      (is (contains? (set (ds/column-names result)) :line-item)))
    (testing "maps known concepts to labels"
      (is (= ["Total Assets" "Total Liabilities" "Unknown"]
             (vec (ds/column result :line-item)))))))

(deftest raw-statement-test
  (let [f #'edgar.financials/raw-statement
        facts-ds (ds/->dataset [{:concept "Assets" :form "10-K" :val 100}
                                {:concept "Liabilities" :form "10-K" :val 50}
                                {:concept "Revenue" :form "10-K" :val 200}
                                {:concept "Assets" :form "10-Q" :val 90}])
        concepts [["Total Assets" "Assets"] ["Total Liabilities" "Liabilities"]]]
    (testing "filters to matching concepts and form"
      (let [result (f facts-ds concepts "10-K")]
        (is (= 2 (ds/row-count result)))
        (is (every? #{"Assets" "Liabilities"} (ds/column result :concept)))))
    (testing "excludes non-matching form"
      (let [result (f facts-ds concepts "10-K")]
        (is (every? #{"10-K"} (ds/column result :form)))))
    (testing "returns empty dataset when no concepts match"
      (is (= 0 (ds/row-count (f facts-ds [["X" "NonExistentConcept"]] "10-K")))))))

(deftest normalized-statement-empty-concepts-test
  (let [f #'edgar.financials/normalized-statement
        facts-ds (ds/->dataset [{:concept "Assets" :form "10-K" :val 100
                                 :end "2023-09-30" :filed "2023-11-01" :start nil}])
        chains [["Missing" "ConceptNotInData"]]]
    (testing "returns empty dataset when no chains resolve"
      (let [result (f facts-ds chains "10-K" :instant nil)]
        (is (= 0 (ds/row-count result)))))))

(deftest normalized-statement-sort-order-test
  (let [f #'edgar.financials/normalized-statement
        facts-ds (ds/->dataset
                  [{:concept "Assets" :form "10-K" :val 100
                    :end "2022-09-30" :filed "2022-10-28" :start nil :frame nil}
                   {:concept "Assets" :form "10-K" :val 200
                    :end "2023-09-30" :filed "2023-11-03" :start nil :frame nil}
                   {:concept "LiabilitiesCurrent" :form "10-K" :val 50
                    :end "2022-09-30" :filed "2022-10-28" :start nil :frame nil}
                   {:concept "LiabilitiesCurrent" :form "10-K" :val 80
                    :end "2023-09-30" :filed "2023-11-03" :start nil :frame nil}])
        chains [["Total Assets" "Assets"]
                ["Current Liabilities" "LiabilitiesCurrent"]]
        result (f facts-ds chains "10-K" :instant nil)
        ends (vec (ds/column result :end))
        labels (vec (ds/column result :line-item))]
    (testing "most recent period comes first (:end descending)"
      (is (= "2023-09-30" (first ends)))
      (is (every? #(>= (compare (first ends) %) 0) ends)))
    (testing ":line-item sorted ascending within each period"
      (let [period-2023 (->> (ds/rows result {:nil-missing? true})
                             (filter #(= "2023-09-30" (:end %)))
                             (map :line-item))]
        (is (= (sort period-2023) period-2023))))))
