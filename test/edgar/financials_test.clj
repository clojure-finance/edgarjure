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
  (let [f #'edgar.financials/dedup-restatements
        rows [{:concept "Assets" :end "2023-09-30" :filed "2023-11-03" :val 100}
              {:concept "Assets" :end "2023-09-30" :filed "2024-01-15" :val 105}
              {:concept "Assets" :end "2022-09-30" :filed "2022-10-28" :val 90}]]
    (testing "keeps most-recently-filed row per [concept end]"
      (let [result (vec (f rows))]
        (is (= 2 (count result)))
        (is (some #(= 105 (:val %)) result))
        (is (some #(= 90 (:val %)) result))
        (is (not (some #(= 100 (:val %)) result)))))
    (testing "no duplicates → same count"
      (let [unique-rows [{:concept "A" :end "2023-09-30" :filed "2023-11-03" :val 1}
                         {:concept "B" :end "2023-09-30" :filed "2023-11-03" :val 2}]
            result (vec (f unique-rows))]
        (is (= 2 (count result)))))))

;;; ---------------------------------------------------------------------------
;;; dedup-point-in-time
;;; ---------------------------------------------------------------------------

(deftest dedup-point-in-time-test
  (let [f #'edgar.financials/dedup-point-in-time
        rows [{:concept "Assets" :end "2023-09-30" :filed "2023-11-03" :val 100}
              {:concept "Assets" :end "2023-09-30" :filed "2024-01-15" :val 105}
              {:concept "Assets" :end "2022-09-30" :filed "2022-10-28" :val 90}]]
    (testing "as-of nil behaves like dedup-restatements (latest filed wins)"
      (let [result (vec (f rows nil))]
        (is (= 2 (count result)))
        (is (some #(= 105 (:val %)) result))))
    (testing "as-of before the restatement excludes the restatement"
      (let [result (vec (f rows "2023-12-31"))]
        (is (= 2 (count result)))
        (is (some #(= 100 (:val %)) result))
        (is (not (some #(= 105 (:val %)) result)))))
    (testing "as-of before all rows returns empty"
      (let [result (vec (f rows "2022-01-01"))]
        (is (empty? result))))))

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
