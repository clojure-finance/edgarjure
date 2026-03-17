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
    (testing "returns [label [winner]] when only first candidate is available"
      (is (= ["Revenue" ["Revenues"]]
             (f ["Revenue" "Revenues" "SalesRevenueNet"]
                #{"Revenues"}))))
    (testing "returns all present candidates when multiple match"
      (is (= ["Revenue" ["Revenues" "SalesRevenueNet"]]
             (f ["Revenue" "Revenues" "SalesRevenueNet"]
                #{"Revenues" "SalesRevenueNet"}))))
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
    (testing "resolved chains include all present candidates"
      (let [result (f chains #{"Revenues" "SalesRevenueNet" "NetIncomeLoss"})]
        (is (= 2 (count result)))
        (is (some #(= ["Revenue" ["Revenues" "SalesRevenueNet"]] %) result))
        (is (some #(= ["Net Income" ["NetIncomeLoss"]] %) result))))
    (testing "chain with no available concepts is omitted"
      (let [result (f chains #{"Revenues" "NetIncomeLoss"})]
        (is (every? #(not= "Missing" (first %)) result))))
    (testing "concept->label map derived from result covers all candidates"
      (let [result (f chains #{"Revenues" "SalesRevenueNet" "NetIncomeLoss"})
            concept->label (into {} (mapcat (fn [[label winners]]
                                              (map (fn [w] [w label]) winners))
                                            result))]
        (is (= "Revenue" (get concept->label "Revenues")))
        (is (= "Revenue" (get concept->label "SalesRevenueNet")))
        (is (= "Net Income" (get concept->label "NetIncomeLoss")))))))

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

(deftest dedup-restatements-returns-flat-seq-test
  ;; Regression clarity test for Issue #11:
  ;; The old code used (mapcat (fn [[_ g]] [(reduce ...)]) ...) — wrapping
  ;; the reduce result in a vector purely to unwrap it via mapcat.
  ;; Fixed to (map (fn [[_ g]] (reduce ...)) ...) — same result, clearer intent.
  ;; This test explicitly verifies the output is a flat seq of maps, not a
  ;; seq of single-element vectors.
  (let [f #'edgar.financials/dedup-restatements
        rows [{:concept "Assets" :unit "USD" :start nil :end "2023-09-30"
               :filed "2023-11-03" :val 100}
              {:concept "Assets" :unit "USD" :start nil :end "2023-09-30"
               :filed "2024-01-15" :val 105}
              {:concept "Liabilities" :unit "USD" :start nil :end "2023-09-30"
               :filed "2023-11-03" :val 50}]
        result (vec (f rows))]
    (testing "result is a seq of plain maps, not a seq of vectors"
      (is (every? map? result)
          "each element must be a plain map, not a single-element vector"))
    (testing "count is one per unique [concept unit start end] group"
      (is (= 2 (count result))))
    (testing "values are accessible directly as map keys (not via first)"
      (let [assets-row (first (filter #(= "Assets" (:concept %)) result))]
        (is (= 105 (:val assets-row))
            "latest-filed value must be accessible directly via :val")))))

(deftest dedup-by-priority-test
  (let [f #'edgar.financials/dedup-by-priority
        concept->label {"CashAndCashEquivalentsAtCarryingValue" "Cash and Equivalents"
                        "CashCashEquivalentsAndShortTermInvestments" "Cash and Equivalents"
                        "LongTermDebt" "Long-Term Debt"
                        "LongTermDebtNoncurrent" "Long-Term Debt"}
        concept->priority {"CashAndCashEquivalentsAtCarryingValue" 0
                           "CashCashEquivalentsAndShortTermInvestments" 1
                           "LongTermDebt" 0
                           "LongTermDebtNoncurrent" 1}]
    (testing "keeps highest-priority concept when both co-exist for same period"
      (let [rows [{:concept "CashAndCashEquivalentsAtCarryingValue"
                   :unit "USD" :start nil :end "2023-12-31" :filed "2024-02-01" :val 100}
                  {:concept "CashCashEquivalentsAndShortTermInvestments"
                   :unit "USD" :start nil :end "2023-12-31" :filed "2024-02-01" :val 300}]
            result (vec (f rows concept->label concept->priority))]
        (is (= 1 (count result)))
        (is (= "CashAndCashEquivalentsAtCarryingValue" (:concept (first result))))
        (is (= 100 (:val (first result))))))
    (testing "different line items are not collapsed"
      (let [rows [{:concept "CashAndCashEquivalentsAtCarryingValue"
                   :unit "USD" :start nil :end "2023-12-31" :filed "2024-02-01" :val 100}
                  {:concept "LongTermDebt"
                   :unit "USD" :start nil :end "2023-12-31" :filed "2024-02-01" :val 500}]
            result (vec (f rows concept->label concept->priority))]
        (is (= 2 (count result)))))
    (testing "single-concept groups pass through unchanged"
      (let [rows [{:concept "CashAndCashEquivalentsAtCarryingValue"
                   :unit "USD" :start nil :end "2023-12-31" :filed "2024-02-01" :val 100}]
            result (vec (f rows concept->label concept->priority))]
        (is (= 1 (count result)))
        (is (= 100 (:val (first result))))))
    (testing "different periods for same chain are kept separately"
      (let [rows [{:concept "CashAndCashEquivalentsAtCarryingValue"
                   :unit "USD" :start nil :end "2023-12-31" :filed "2024-02-01" :val 100}
                  {:concept "CashCashEquivalentsAndShortTermInvestments"
                   :unit "USD" :start nil :end "2022-12-31" :filed "2023-02-01" :val 250}]
            result (vec (f rows concept->label concept->priority))]
        (is (= 2 (count result)) "different periods must both survive")))))

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

(deftest dedup-point-in-time-returns-flat-seq-test
  ;; Same class of fix as dedup-restatements — verifies flat seq output.
  (let [f #'edgar.financials/dedup-point-in-time
        rows [{:concept "Assets" :unit "USD" :start nil :end "2023-09-30"
               :filed "2023-11-03" :val 100}
              {:concept "Assets" :unit "USD" :start nil :end "2023-09-30"
               :filed "2024-01-15" :val 105}
              {:concept "Liabilities" :unit "USD" :start nil :end "2023-09-30"
               :filed "2023-11-03" :val 50}]
        result (vec (f rows nil))]
    (testing "result (as-of nil path) is a seq of plain maps"
      (is (every? map? result)))
    (testing "count is one per group"
      (is (= 2 (count result))))
    (testing "values accessible directly"
      (is (= 105 (:val (first (filter #(= "Assets" (:concept %)) result))))))
    (testing "result (as-of path) is also a seq of plain maps"
      (let [result (vec (f rows "2023-12-31"))]
        (is (every? map? result))
        (is (= 2 (count result)))
        (is (= 100 (:val (first (filter #(= "Assets" (:concept %)) result)))))))))

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

(deftest to-wide-quarterly-columns-test
  (let [f #'edgar.financials/to-wide
        ;; Simulate 10-Q long output: two periods, two line items, with :val-q and :val-ltm
        rows [{:end "2024-03-31" :line-item "Revenue" :val 210 :val-q 110 :val-ltm 460}
              {:end "2024-03-31" :line-item "Net Income" :val 42 :val-q 22 :val-ltm 90}
              {:end "2023-12-31" :line-item "Revenue" :val 100 :val-q 100 :val-ltm nil}
              {:end "2023-12-31" :line-item "Net Income" :val 20 :val-q 20 :val-ltm nil}]
        long-ds (ds/->dataset rows)
        result (f long-ds)
        col-names (set (map name (ds/column-names result)))]
    (testing "one row per period"
      (is (= 2 (ds/row-count result))))
    (testing "plain :val columns present"
      (is (contains? col-names "Revenue"))
      (is (contains? col-names "Net Income")))
    (testing ":val-q columns present as \"<line-item> (Q)\""
      (is (contains? col-names "Revenue (Q)"))
      (is (contains? col-names "Net Income (Q)")))
    (testing ":val-ltm columns present as \"<line-item> (LTM)\""
      (is (contains? col-names "Revenue (LTM)"))
      (is (contains? col-names "Net Income (LTM)")))
    (testing "correct values in wide rows"
      (let [rows-out (->> (ds/rows result {:nil-missing? true})
                          (sort-by :end #(compare %2 %1))
                          vec)
            latest (first rows-out)]
        (is (= 210 (get latest "Revenue")))
        (is (= 110 (get latest "Revenue (Q)")))
        (is (= 460 (get latest "Revenue (LTM)")))))))

(deftest to-wide-no-quarterly-columns-test
  (let [f #'edgar.financials/to-wide
        ;; 10-K long output: no :val-q or :val-ltm columns
        rows [{:end "2024-09-30" :line-item "Revenue" :val 400}
              {:end "2024-09-30" :line-item "Net Income" :val 100}
              {:end "2023-09-30" :line-item "Revenue" :val 350}
              {:end "2023-09-30" :line-item "Net Income" :val 90}]
        long-ds (ds/->dataset rows)
        result (f long-ds)
        col-names (set (map name (ds/column-names result)))]
    (testing "no spurious (Q) or (LTM) columns when source has none"
      (is (not (some #(clojure.string/ends-with? % " (Q)") col-names)))
      (is (not (some #(clojure.string/ends-with? % " (LTM)") col-names))))
    (testing "plain columns still present"
      (is (contains? col-names "Revenue"))
      (is (contains? col-names "Net Income")))))

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

(deftest normalized-statement-multi-candidate-test
  (let [f #'edgar.financials/normalized-statement
        facts-ds (ds/->dataset
                  [{:concept "SalesRevenueNet" :form "10-K" :val 100
                    :unit "USD" :start "2015-01-01" :end "2015-12-31"
                    :filed "2016-02-01" :frame nil}
                   {:concept "Revenues" :form "10-K" :val 200
                    :unit "USD" :start "2020-01-01" :end "2020-12-31"
                    :filed "2021-02-01" :frame nil}])
        chains [["Revenue" "Revenues" "SalesRevenueNet" "SalesRevenueGoodsNet"]]]
    (testing "both present candidates are included — no historical periods dropped"
      (let [result (f facts-ds chains "10-K" :duration nil)]
        (is (= 2 (ds/row-count result)) "pre-2018 SalesRevenueNet and post-2018 Revenues must both appear")))
    (testing "both rows carry the same :line-item label"
      (let [result (f facts-ds chains "10-K" :duration nil)
            labels (set (ds/column result :line-item))]
        (is (= #{"Revenue"} labels))))))

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

(deftest normalized-statement-overlapping-concepts-test
  (let [f #'edgar.financials/normalized-statement
        facts-ds (ds/->dataset
                  [{:concept "CashAndCashEquivalentsAtCarryingValue" :form "10-K" :val 100
                    :unit "USD" :start nil :end "2023-12-31" :filed "2024-02-01" :frame nil}
                   {:concept "CashCashEquivalentsAndShortTermInvestments" :form "10-K" :val 300
                    :unit "USD" :start nil :end "2023-12-31" :filed "2024-02-01" :frame nil}
                   {:concept "CashAndCashEquivalentsAtCarryingValue" :form "10-K" :val 90
                    :unit "USD" :start nil :end "2022-12-31" :filed "2023-02-01" :frame nil}
                   {:concept "CashCashEquivalentsAndShortTermInvestments" :form "10-K" :val 270
                    :unit "USD" :start nil :end "2022-12-31" :filed "2023-02-01" :frame nil}])
        chains [["Cash and Equivalents"
                 "CashAndCashEquivalentsAtCarryingValue"
                 "CashCashEquivalentsAndShortTermInvestments"]]]
    (testing "only the primary (index 0) concept survives when both are present"
      (let [result (f facts-ds chains "10-K" :instant nil)]
        (is (= 2 (ds/row-count result)) "one row per period, not two")
        (is (= #{100 90} (set (ds/column result :val)))
            "values come from CashAndCashEquivalentsAtCarryingValue only")
        (is (= #{"Cash and Equivalents"} (set (ds/column result :line-item))))))
    (testing "works with :as-of too"
      (let [result (f facts-ds chains "10-K" :instant "2023-06-01")]
        (is (= 1 (ds/row-count result)) "only 2022 period visible before as-of")
        (is (= 90 (first (ds/column result :val))))))))

;;; ---------------------------------------------------------------------------
;;; Quarterly and LTM derivation
;;; ---------------------------------------------------------------------------

(deftest prior-quarter-test
  (let [f #'edgar.financials/prior-quarter]
    (testing "Q1 wraps to prior year Q4"
      (is (= [2023 "Q4"] (f 2024 "Q1"))))
    (testing "Q2 -> Q1 same year"
      (is (= [2024 "Q1"] (f 2024 "Q2"))))
    (testing "Q3 -> Q2 same year"
      (is (= [2024 "Q2"] (f 2024 "Q3"))))
    (testing "Q4 -> Q3 same year"
      (is (= [2024 "Q3"] (f 2024 "Q4"))))
    (testing "non-quarter fp returns nil"
      (is (nil? (f 2024 "FY"))))))

(deftest quarter-seq-test
  (let [f #'edgar.financials/quarter-seq]
    (testing "generates backward sequence crossing fiscal year boundary"
      (is (= [[2024 "Q2"] [2024 "Q1"] [2023 "Q4"] [2023 "Q3"]]
             (take 4 (f 2024 "Q3")))))
    (testing "from Q1 goes to prior year"
      (is (= [[2023 "Q4"] [2023 "Q3"] [2023 "Q2"]]
             (take 3 (f 2024 "Q1")))))))

(deftest build-ytd-lookup-test
  (let [f #'edgar.financials/build-ytd-lookup]
    (testing "builds lookup from rows with valid fy/fp"
      (let [rows [{:line-item "Revenue" :unit "USD" :fy 2024 :fp "Q1" :val 100}
                  {:line-item "Revenue" :unit "USD" :fy 2024 :fp "Q2" :val 210}]
            lookup (f rows)]
        (is (= 100 (get lookup ["Revenue" "USD" 2024 "Q1"])))
        (is (= 210 (get lookup ["Revenue" "USD" 2024 "Q2"])))))
    (testing "skips rows with FY or nil fy"
      (let [rows [{:line-item "Revenue" :unit "USD" :fy 2024 :fp "FY" :val 400}
                  {:line-item "Revenue" :unit "USD" :fy nil :fp "Q1" :val 100}]
            lookup (f rows)]
        (is (empty? lookup))))
    (testing "falls back to :concept when :line-item absent"
      (let [rows [{:concept "Revenues" :unit "USD" :fy 2024 :fp "Q1" :val 100}]
            lookup (f rows)]
        (is (= 100 (get lookup ["Revenues" "USD" 2024 "Q1"])))))))

(deftest compute-val-q-test
  (let [f #'edgar.financials/compute-val-q
        ytd-lookup {["Revenue" "USD" 2024 "Q1"] 100
                    ["Revenue" "USD" 2024 "Q2"] 210
                    ["Revenue" "USD" 2024 "Q3"] 330}]
    (testing "Q1 returns reported value (already single quarter)"
      (is (= 100 (f {:line-item "Revenue" :unit "USD" :fy 2024 :fp "Q1" :val 100}
                    ytd-lookup))))
    (testing "Q2 subtracts Q1 YTD"
      (is (= 110 (f {:line-item "Revenue" :unit "USD" :fy 2024 :fp "Q2" :val 210}
                    ytd-lookup))))
    (testing "Q3 subtracts Q2 YTD"
      (is (= 120 (f {:line-item "Revenue" :unit "USD" :fy 2024 :fp "Q3" :val 330}
                    ytd-lookup))))
    (testing "Q4 subtracts Q3 YTD"
      (is (= 170 (f {:line-item "Revenue" :unit "USD" :fy 2024 :fp "Q4" :val 500}
                    ytd-lookup))))
    (testing "returns nil when prior YTD missing"
      (is (nil? (f {:line-item "Revenue" :unit "USD" :fy 2025 :fp "Q2" :val 200}
                   ytd-lookup))))
    (testing "returns nil for FY rows"
      (is (nil? (f {:line-item "Revenue" :unit "USD" :fy 2024 :fp "FY" :val 400}
                   ytd-lookup))))
    (testing "returns nil when fy is nil"
      (is (nil? (f {:line-item "Revenue" :unit "USD" :fy nil :fp "Q1" :val 100}
                   ytd-lookup))))))

(deftest compute-val-ltm-test
  (let [f #'edgar.financials/compute-val-ltm
        val-q-lookup {["Revenue" "USD" 2023 "Q2"] 100
                      ["Revenue" "USD" 2023 "Q3"] 110
                      ["Revenue" "USD" 2023 "Q4"] 120
                      ["Revenue" "USD" 2024 "Q1"] 130
                      ["Revenue" "USD" 2024 "Q2"] 140}]
    (testing "sums four consecutive quarters"
      (is (= (+ 130 120 110 100)
             (f {:line-item "Revenue" :unit "USD" :fy 2024 :fp "Q1"}
                val-q-lookup))))
    (testing "sums four quarters crossing fiscal year"
      (is (= (+ 140 130 120 110)
             (f {:line-item "Revenue" :unit "USD" :fy 2024 :fp "Q2"}
                val-q-lookup))))
    (testing "returns nil when a prior quarter is missing"
      (is (nil? (f {:line-item "Revenue" :unit "USD" :fy 2023 :fp "Q2"}
                   val-q-lookup))))
    (testing "returns nil for non-quarter fp"
      (is (nil? (f {:line-item "Revenue" :unit "USD" :fy 2024 :fp "FY"}
                   val-q-lookup))))))

(deftest add-quarterly-and-ltm-test
  (let [f #'edgar.financials/add-quarterly-and-ltm]
    (testing "10-K data is returned unchanged — no :val-q or :val-ltm columns"
      (let [ds (ds/->dataset [{:line-item "Revenue" :val 400 :fy 2024 :fp "FY"
                               :unit "USD" :end "2024-09-30"}])
            result (f ds "10-K")]
        (is (= ds result))
        (is (not (some #{:val-q} (ds/column-names result))))))
    (testing "10-Q data gets :val-q and :val-ltm columns"
      (let [ds (ds/->dataset
                [{:line-item "Revenue" :val 100 :fy 2024 :fp "Q1"
                  :unit "USD" :end "2024-03-31" :concept "Revenues"}
                 {:line-item "Revenue" :val 210 :fy 2024 :fp "Q2"
                  :unit "USD" :end "2024-06-30" :concept "Revenues"}])
            result (f ds "10-Q")
            rows (vec (ds/rows result {:nil-missing? true}))]
        (is (some #{:val-q} (ds/column-names result)))
        (is (some #{:val-ltm} (ds/column-names result)))
        (is (= 100 (:val-q (first rows))))
        (is (= 110 (:val-q (second rows))))))
    (testing "empty dataset returns empty dataset"
      (let [result (f (ds/->dataset []) "10-Q")]
        (is (= 0 (ds/row-count result)))))))

(deftest normalized-statement-quarterly-test
  (let [f #'edgar.financials/normalized-statement
        facts-ds (ds/->dataset
                  [{:concept "Revenues" :form "10-Q" :val 100
                    :unit "USD" :start "2023-10-01" :end "2023-12-31"
                    :filed "2024-02-01" :fy 2024 :fp "Q1" :frame nil}
                   {:concept "Revenues" :form "10-Q" :val 210
                    :unit "USD" :start "2023-10-01" :end "2024-03-31"
                    :filed "2024-05-01" :fy 2024 :fp "Q2" :frame nil}
                   {:concept "Revenues" :form "10-Q" :val 330
                    :unit "USD" :start "2023-10-01" :end "2024-06-30"
                    :filed "2024-08-01" :fy 2024 :fp "Q3" :frame nil}])
        chains [["Revenue" "Revenues"]]]
    (testing "10-Q normalized statement includes :val-q column"
      (let [result (f facts-ds chains "10-Q" :duration nil)
            rows (->> (ds/rows result {:nil-missing? true})
                      (sort-by :end)
                      vec)]
        (is (some #{:val-q} (ds/column-names result)))
        (is (= 100 (:val-q (nth rows 0))) "Q1 val-q = reported")
        (is (= 110 (:val-q (nth rows 1))) "Q2 val-q = 210 - 100")
        (is (= 120 (:val-q (nth rows 2))) "Q3 val-q = 330 - 210")))
    (testing "10-K normalized statement does NOT include :val-q"
      (let [facts-10k (ds/->dataset
                       [{:concept "Revenues" :form "10-K" :val 400
                         :unit "USD" :start "2023-10-01" :end "2024-09-30"
                         :filed "2024-11-01" :fy 2024 :fp "FY" :frame nil}])
            result (f facts-10k chains "10-K" :duration nil)]
        (is (not (some #{:val-q} (ds/column-names result))))))))

(deftest normalized-statement-ltm-test
  (let [f #'edgar.financials/normalized-statement
        facts-ds (ds/->dataset
                  [{:concept "Revenues" :form "10-Q" :val 100
                    :unit "USD" :start "2022-10-01" :end "2022-12-31"
                    :filed "2023-02-01" :fy 2023 :fp "Q1" :frame nil}
                   {:concept "Revenues" :form "10-Q" :val 210
                    :unit "USD" :start "2022-10-01" :end "2023-03-31"
                    :filed "2023-05-01" :fy 2023 :fp "Q2" :frame nil}
                   {:concept "Revenues" :form "10-Q" :val 330
                    :unit "USD" :start "2022-10-01" :end "2023-06-30"
                    :filed "2023-08-01" :fy 2023 :fp "Q3" :frame nil}
                   {:concept "Revenues" :form "10-Q" :val 460
                    :unit "USD" :start "2022-10-01" :end "2023-09-30"
                    :filed "2023-11-01" :fy 2023 :fp "Q4" :frame nil}
                   {:concept "Revenues" :form "10-Q" :val 140
                    :unit "USD" :start "2023-10-01" :end "2023-12-31"
                    :filed "2024-02-01" :fy 2024 :fp "Q1" :frame nil}])
        chains [["Revenue" "Revenues"]]]
    (testing "LTM computed when four consecutive quarters available"
      (let [result (f facts-ds chains "10-Q" :duration nil)
            rows (->> (ds/rows result {:nil-missing? true})
                      (sort-by :end)
                      vec)
            q4-row (nth rows 3)
            q1-fy24-row (nth rows 4)]
        (is (= (+ 100 110 120 130) (:val-ltm q4-row))
            "Q4 FY2023 LTM = Q1+Q2+Q3+Q4 = 100+110+120+130")
        (is (= (+ 140 130 120 110) (:val-ltm q1-fy24-row))
            "Q1 FY2024 LTM = Q1(140)+Q4(130)+Q3(120)+Q2(110)")))
    (testing "LTM is nil when prior quarters are missing"
      (let [result (f facts-ds chains "10-Q" :duration nil)
            rows (->> (ds/rows result {:nil-missing? true})
                      (sort-by :end)
                      vec)
            q1-row (first rows)]
        (is (nil? (:val-ltm q1-row)) "Q1 FY2023 has no prior 3 quarters")))))
