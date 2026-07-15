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

(deftest as-reported-statement-test
  (let [f #'edgar.financials/as-reported-statement
        facts-ds (ds/->dataset [{:concept "Assets" :form "10-K" :val 100 :start nil}
                                {:concept "Liabilities" :form "10-K" :val 50 :start nil}
                                {:concept "Revenue" :form "10-K" :val 200 :start nil}
                                {:concept "Assets" :form "10-Q" :val 90 :start nil}])
        concepts [["Total Assets" "Assets"] ["Total Liabilities" "Liabilities"]]]
    (testing "filters to matching concepts and form"
      (let [result (f facts-ds concepts "10-K" :any)]
        (is (= 2 (ds/row-count result)))
        (is (every? #{"Assets" "Liabilities"} (ds/column result :concept)))))
    (testing "excludes non-matching form"
      (let [result (f facts-ds concepts "10-K" :any)]
        (is (every? #{"10-K"} (ds/column result :form)))))
    (testing "does not map labels or add :line-item / :method"
      (let [result (f facts-ds concepts "10-K" :any)
            cols (set (ds/column-names result))]
        (is (not (contains? cols :line-item)))
        (is (not (contains? cols :method)))))
    (testing "does NOT deduplicate restatements — all filed rows survive"
      (let [dup-ds (ds/->dataset [{:concept "Assets" :form "10-K" :val 100 :start nil
                                   :unit "USD" :end "2023-09-30" :filed "2023-11-01"}
                                  {:concept "Assets" :form "10-K" :val 105 :start nil
                                   :unit "USD" :end "2023-09-30" :filed "2024-11-01"}])
            result (f dup-ds concepts "10-K" :instant)]
        (is (= 2 (ds/row-count result))
            "as-reported view keeps both the original and the restated row")))
    (testing "returns empty dataset when no concepts match"
      (is (= 0 (ds/row-count (f facts-ds [["X" "NonExistentConcept"]] "10-K" :any)))))))

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
      (let [result (f facts-ds chains "10-K" :duration nil nil)]
        (is (= 2 (ds/row-count result)) "pre-2018 SalesRevenueNet and post-2018 Revenues must both appear")))
    (testing "both rows carry the same :line-item label"
      (let [result (f facts-ds chains "10-K" :duration nil nil)
            labels (set (ds/column result :line-item))]
        (is (= #{"Revenue"} labels))))))

(deftest normalized-statement-empty-concepts-test
  (let [f #'edgar.financials/normalized-statement
        facts-ds (ds/->dataset [{:concept "Assets" :form "10-K" :val 100
                                 :end "2023-09-30" :filed "2023-11-01" :start nil}])
        chains [["Missing" "ConceptNotInData"]]]
    (testing "returns empty dataset when no chains resolve"
      (let [result (f facts-ds chains "10-K" :instant nil nil)]
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
        result (f facts-ds chains "10-K" :instant nil nil)
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
      (let [result (f facts-ds chains "10-K" :instant nil nil)]
        (is (= 2 (ds/row-count result)) "one row per period, not two")
        (is (= #{100 90} (set (ds/column result :val)))
            "values come from CashAndCashEquivalentsAtCarryingValue only")
        (is (= #{"Cash and Equivalents"} (set (ds/column result :line-item))))))
    (testing "works with :as-of too"
      (let [result (f facts-ds chains "10-K" :instant "2023-06-01" nil)]
        (is (= 1 (ds/row-count result)) "only 2022 period visible before as-of")
        (is (= 90 (first (ds/column result :val))))))))

;;; ---------------------------------------------------------------------------
;;; Quarterly and LTM derivation — date-window based
;;; The SEC :fy/:fp fields describe the filing, not the observation period,
;;; so the derivation works purely from :start/:end dates.
;;; ---------------------------------------------------------------------------

(deftest duration-months-test
  (let [f #'edgar.financials/duration-months]
    (testing "classifies calendar quarters"
      (is (= 3 (f {:start "2023-01-01" :end "2023-03-31"}))))
    (testing "classifies 13-week fiscal quarters (e.g. Apple)"
      (is (= 3 (f {:start "2025-09-28" :end "2025-12-27"}))))
    (testing "classifies 6/9/12-month YTD windows"
      (is (= 6 (f {:start "2023-01-01" :end "2023-06-30"})))
      (is (= 9 (f {:start "2023-01-01" :end "2023-09-30"})))
      (is (= 12 (f {:start "2023-01-01" :end "2023-12-31"})))
      (is (= 12 (f {:start "2024-09-29" :end "2025-09-27"})) "52-week fiscal year"))
    (testing "nil for instant rows"
      (is (nil? (f {:end "2023-03-31"})))
      (is (nil? (f {:start nil :end "2023-03-31"}))))
    (testing "nil for windows that are not whole quarters"
      (is (nil? (f {:start "2023-01-01" :end "2023-01-31"}))))))

(deftest add-quarterly-and-ltm-test
  (let [f #'edgar.financials/add-quarterly-and-ltm]
    (testing "10-K data is returned unchanged — no :val-q or :val-ltm columns"
      (let [ds (ds/->dataset [{:line-item "Revenue" :val 400
                               :unit "USD" :start "2023-10-01" :end "2024-09-30"}])
            result (f ds "10-K" nil)]
        (is (= ds result))
        (is (not (some #{:val-q} (ds/column-names result))))))
    (testing "10-Q data gets :duration-months, :val-q and :val-ltm columns"
      (let [ds (ds/->dataset
                [{:line-item "Revenue" :val 100 :unit "USD" :concept "Revenues"
                  :start "2024-01-01" :end "2024-03-31"}
                 {:line-item "Revenue" :val 210 :unit "USD" :concept "Revenues"
                  :start "2024-01-01" :end "2024-06-30"}])
            result (f ds "10-Q" nil)
            rows (->> (ds/rows result {:nil-missing? true}) (sort-by :end) vec)]
        (is (some #{:duration-months} (ds/column-names result)))
        (is (some #{:val-q} (ds/column-names result)))
        (is (some #{:val-ltm} (ds/column-names result)))
        (is (= 100 (:val-q (first rows))) "3-month row: val-q = val")
        (is (= 110 (:val-q (second rows))) "6-month YTD row: val-q = 210 - 100")))
    (testing "empty dataset returns empty dataset"
      (let [result (f (ds/->dataset []) "10-Q" nil)]
        (is (= 0 (ds/row-count result)))))))

(deftest val-q-comparative-period-safety-test
  ;; Regression test for the fy/fp collision bug: a Q2 10-Q carries current
  ;; 3-month, current YTD, AND prior-year comparative rows, all sharing the
  ;; same :fy/:fp. The old fy/fp-keyed derivation mixed the windows and
  ;; produced garbage (negative revenue). Date-window keying must not.
  (let [f #'edgar.financials/add-quarterly-and-ltm
        rows [;; current-year Q1
              {:line-item "Revenue" :val 190 :unit "USD"
               :start "2023-10-01" :end "2023-12-31" :fy 2024 :fp "Q1"}
              ;; current 3-month Q2
              {:line-item "Revenue" :val 210 :unit "USD"
               :start "2024-01-01" :end "2024-03-31" :fy 2024 :fp "Q2"}
              ;; current 6-month YTD — same fy/fp as the 3-month row
              {:line-item "Revenue" :val 400 :unit "USD"
               :start "2023-10-01" :end "2024-03-31" :fy 2024 :fp "Q2"}
              ;; prior-year comparative 3-month — ALSO fy 2024 fp Q2
              {:line-item "Revenue" :val 110 :unit "USD"
               :start "2023-01-01" :end "2023-03-31" :fy 2024 :fp "Q2"}
              ;; prior-year comparative 6-month — ALSO fy 2024 fp Q2
              {:line-item "Revenue" :val 210 :unit "USD"
               :start "2022-10-01" :end "2023-03-31" :fy 2024 :fp "Q2"}
              ;; prior-year Q1 (from last year's Q1 filing)
              {:line-item "Revenue" :val 100 :unit "USD"
               :start "2022-10-01" :end "2022-12-31" :fy 2023 :fp "Q1"}]
        result (f (ds/->dataset rows) "10-Q" nil)
        by-window (into {} (map (fn [r] [[(str (:start r)) (str (:end r))] (:val-q r)])
                                (ds/rows result {:nil-missing? true})))]
    (testing "current 3-month row keeps its own value"
      (is (= 210 (get by-window ["2024-01-01" "2024-03-31"]))))
    (testing "current YTD row derives the same quarter via differencing"
      (is (= 210 (get by-window ["2023-10-01" "2024-03-31"]))
          "400 YTD - 190 Q1 = 210, not corrupted by comparative rows"))
    (testing "comparative 3-month row keeps its own (prior-year) value"
      (is (= 110 (get by-window ["2023-01-01" "2023-03-31"]))))
    (testing "comparative YTD row derives prior-year quarter correctly"
      (is (= 110 (get by-window ["2022-10-01" "2023-03-31"]))
          "210 prior YTD - 100 prior Q1 = 110"))
    (testing "no negative quarterly revenue anywhere (the old failure mode)"
      (is (every? #(or (nil? %) (pos? %)) (vals by-window))))))

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
      (let [result (f facts-ds chains "10-Q" :duration nil nil)
            rows (->> (ds/rows result {:nil-missing? true})
                      (sort-by :end)
                      vec)]
        (is (some #{:val-q} (ds/column-names result)))
        (is (= 100 (:val-q (nth rows 0))) "Q1 val-q = reported (3-month window)")
        (is (= 110 (:val-q (nth rows 1))) "Q2 val-q = 210 - 100 (YTD differencing)")
        (is (= 120 (:val-q (nth rows 2))) "Q3 val-q = 330 - 210")))
    (testing "10-K normalized statement does NOT include :val-q"
      (let [facts-10k (ds/->dataset
                       [{:concept "Revenues" :form "10-K" :val 400
                         :unit "USD" :start "2023-10-01" :end "2024-09-30"
                         :filed "2024-11-01" :fy 2024 :fp "FY" :frame nil}])
            result (f facts-10k chains "10-K" :duration nil nil)]
        (is (not (some #{:val-q} (ds/column-names result))))))))

(deftest normalized-statement-ltm-test
  ;; LTM requires a fiscal Q4, which never appears in 10-Q filings.
  ;; The 10-K FY row must participate: Q4 = FY - 9M YTD.
  (let [f #'edgar.financials/normalized-statement
        facts-ds (ds/->dataset
                  [;; FY2023 quarters via 10-Q filings
                   {:concept "Revenues" :form "10-Q" :val 100
                    :unit "USD" :start "2022-10-01" :end "2022-12-31"
                    :filed "2023-02-01" :fy 2023 :fp "Q1" :frame nil}
                   {:concept "Revenues" :form "10-Q" :val 210
                    :unit "USD" :start "2022-10-01" :end "2023-03-31"
                    :filed "2023-05-01" :fy 2023 :fp "Q2" :frame nil}
                   {:concept "Revenues" :form "10-Q" :val 330
                    :unit "USD" :start "2022-10-01" :end "2023-06-30"
                    :filed "2023-08-01" :fy 2023 :fp "Q3" :frame nil}
                   ;; FY2023 annual — 10-K (only source of Q4 information)
                   {:concept "Revenues" :form "10-K" :val 460
                    :unit "USD" :start "2022-10-01" :end "2023-09-30"
                    :filed "2023-11-15" :fy 2023 :fp "FY" :frame nil}
                   ;; FY2024 Q1 10-Q
                   {:concept "Revenues" :form "10-Q" :val 140
                    :unit "USD" :start "2023-10-01" :end "2023-12-31"
                    :filed "2024-02-01" :fy 2024 :fp "Q1" :frame nil}])
        chains [["Revenue" "Revenues"]]]
    (testing "LTM computed across the fiscal Q4 using the 10-K annual row"
      (let [result (f facts-ds chains "10-Q" :duration nil nil)
            rows (->> (ds/rows result {:nil-missing? true})
                      (sort-by :end)
                      vec)
            q1-fy24-row (last rows)]
        (is (= "2023-12-31" (str (:end q1-fy24-row))))
        (is (= 500 (:val-ltm q1-fy24-row))
            "Q1 FY24 LTM = 140 + Q4(460-330=130) + Q3(120) + Q2(110) = 500")))
    (testing "10-K annual rows are NOT included as output rows of the 10-Q statement"
      (let [result (f facts-ds chains "10-Q" :duration nil nil)]
        (is (= 4 (ds/row-count result))
            "only the four 10-Q observations appear")))
    (testing "LTM is nil when the trailing window is incomplete"
      (let [result (f facts-ds chains "10-Q" :duration nil nil)
            rows (->> (ds/rows result {:nil-missing? true})
                      (sort-by :end)
                      vec)]
        (is (nil? (:val-ltm (first rows)))
            "Q1 FY2023 has no prior three quarters")))))

(deftest to-wide-ytd-preference-test
  ;; When a 3-month and a YTD row share the same [end line-item], the wide
  ;; pivot must deterministically use the YTD (longest duration) row for the
  ;; plain value, and the period-level :val-q from whichever row has it.
  (let [f #'edgar.financials/to-wide
        rows [{:end "2024-03-31" :line-item "Revenue" :val 210
               :duration-months 3 :val-q 210 :val-ltm nil}
              {:end "2024-03-31" :line-item "Revenue" :val 400
               :duration-months 6 :val-q 210 :val-ltm nil}]
        result (f (ds/->dataset rows))
        row (first (ds/rows result {:nil-missing? true}))]
    (testing "one output row for the period"
      (is (= 1 (ds/row-count result))))
    (testing "plain value comes from the YTD (6-month) row"
      (is (= 400 (get row "Revenue"))))
    (testing "(Q) value is the derived quarter"
      (is (= 210 (get row "Revenue (Q)"))))))

;;; ---------------------------------------------------------------------------
;;; Derived line items (imputation) — :view :standardized
;;; ---------------------------------------------------------------------------

(deftest apply-identities-test
  (let [f #'edgar.financials/apply-identities
        base {:unit "USD" :start "2023-01-01" :end "2023-12-31"
              :filed "2024-02-01" :method :direct}]
    (testing "derives Gross Profit = Revenue - Cost of Revenue when absent"
      (let [rows [(assoc base :line-item "Revenue" :val 100 :concept "Revenues")
                  (assoc base :line-item "Cost of Revenue" :val 60 :concept "CostOfRevenue")]
            result (vec (f rows fin/income-statement-identities))
            gp (first (filter #(= "Gross Profit" (:line-item %)) result))]
        (is (some? gp) "Gross Profit row must be synthesized")
        (is (= 40 (:val gp)))
        (is (= :derived (:method gp)))
        (is (= ["Revenue" "Cost of Revenue"] (:derived-from gp)))
        (is (nil? (:concept gp)))))
    (testing "does not derive when the target is already present"
      (let [rows [(assoc base :line-item "Revenue" :val 100)
                  (assoc base :line-item "Cost of Revenue" :val 60)
                  (assoc base :line-item "Gross Profit" :val 39)]
            result (vec (f rows fin/income-statement-identities))
            gps (filter #(= "Gross Profit" (:line-item %)) result)]
        (is (= 1 (count gps)))
        (is (= 39 (:val (first gps))) "reported value wins over the identity")))
    (testing "does not derive when an operand is missing"
      (let [rows [(assoc base :line-item "Revenue" :val 100)]
            result (vec (f rows fin/income-statement-identities))]
        (is (not (some #(= "Gross Profit" (:line-item %)) result)))))
    (testing "periods are independent — no cross-period mixing"
      (let [rows [(assoc base :line-item "Revenue" :val 100)
                  (assoc base :line-item "Cost of Revenue" :val 60
                         :end "2022-12-31" :start "2022-01-01")]
            result (vec (f rows fin/income-statement-identities))]
        (is (not (some #(= "Gross Profit" (:line-item %)) result))
            "operands from different periods must not combine")))
    (testing ":= identity copies the operand (Total Assets from L+E)"
      (let [rows [{:unit "USD" :start nil :end "2023-12-31" :method :direct
                   :line-item "Total Liabilities and Equity" :val 500}]
            result (vec (f rows fin/balance-sheet-identities))
            ta (first (filter #(= "Total Assets" (:line-item %)) result))]
        (is (= 500 (:val ta)))
        (is (= :derived (:method ta)))))
    (testing "Free Cash Flow = OCF - Capex"
      (let [rows [(assoc base :line-item "Operating Cash Flow" :val 80)
                  (assoc base :line-item "Capex" :val 30)]
            result (vec (f rows fin/cash-flow-identities))
            fcf (first (filter #(= "Free Cash Flow" (:line-item %)) result))]
        (is (= 50 (:val fcf)))))
    (testing "D&A imputed from separately tagged components"
      (let [rows [(assoc base :line-item "Depreciation" :val 40)
                  (assoc base :line-item "Amortization of Intangibles" :val 10)]
            result (vec (f rows fin/cash-flow-identities))
            dna (first (filter #(= "D&A" (:line-item %)) result))]
        (is (= 50 (:val dna)))
        (is (= :derived (:method dna)))))
    (testing "Total Gross Revenue = Interest Income + Noninterest Income (banks)"
      (let [rows [(assoc base :line-item "Interest Income" :val 60)
                  (assoc base :line-item "Noninterest Income" :val 40)]
            result (vec (f rows fin/income-statement-identities))
            tgr (first (filter #(= "Total Gross Revenue" (:line-item %)) result))]
        (is (= 100 (:val tgr)))))
    (testing "Total Equity = SE + NCI when NCI is tagged"
      (let [rows [{:unit "USD" :start nil :end "2023-12-31" :method :direct
                   :line-item "Stockholders Equity" :val 90}
                  {:unit "USD" :start nil :end "2023-12-31" :method :direct
                   :line-item "Noncontrolling Interest" :val 10}]
            result (vec (f rows fin/balance-sheet-identities))
            te (first (filter #(= "Total Equity" (:line-item %)) result))]
        (is (= 100 (:val te)))
        (is (= ["Stockholders Equity" "Noncontrolling Interest"] (:derived-from te)))))
    (testing "Total Equity falls back to parent equity when NCI is untagged"
      (let [rows [{:unit "USD" :start nil :end "2023-12-31" :method :direct
                   :line-item "Stockholders Equity" :val 90}]
            result (vec (f rows fin/balance-sheet-identities))
            te (first (filter #(= "Total Equity" (:line-item %)) result))]
        (is (= 90 (:val te)))
        (is (= ["Stockholders Equity"] (:derived-from te)))))
    (testing "Working Capital = Current Assets - Current Liabilities (derived-only)"
      (let [rows [{:unit "USD" :start nil :end "2023-12-31" :method :direct
                   :line-item "Current Assets" :val 70}
                  {:unit "USD" :start nil :end "2023-12-31" :method :direct
                   :line-item "Current Liabilities" :val 45}]
            result (vec (f rows fin/balance-sheet-identities))
            wc (first (filter #(= "Working Capital" (:line-item %)) result))]
        (is (= 25 (:val wc)))))))

;;; ---------------------------------------------------------------------------
;;; Industry routing
;;; ---------------------------------------------------------------------------

(deftest industry-for-sic-test
  (testing "banks: SIC 6000-6199 and 6712"
    (is (= :bank (fin/industry-for-sic "6021")))
    (is (= :bank (fin/industry-for-sic 6022)))
    (is (= :bank (fin/industry-for-sic "6712"))))
  (testing "insurers: SIC 6300-6399 and 6411"
    (is (= :insurance (fin/industry-for-sic "6311")))
    (is (= :insurance (fin/industry-for-sic "6411"))))
  (testing "REITs: SIC 6500-6553"
    (is (= :reit (fin/industry-for-sic "6500")))
    (is (= :reit (fin/industry-for-sic "6552"))))
  (testing "everything else is :standard"
    (is (= :standard (fin/industry-for-sic "3571")))
    (is (= :standard (fin/industry-for-sic "6200"))) ; brokers, no chains yet
    (is (= :standard (fin/industry-for-sic nil)))
    (is (= :standard (fin/industry-for-sic "not-a-sic")))))

;;; ---------------------------------------------------------------------------
;;; EDN concept files
;;; ---------------------------------------------------------------------------

(deftest concept-chains-loaded-from-edn-test
  (testing "standard chains have the expected shape [[label c1 c2 ...] ...]"
    (is (vector? fin/income-statement-concepts))
    (is (every? vector? fin/income-statement-concepts))
    (is (every? #(and (string? (first %)) (every? string? (rest %)))
                fin/income-statement-concepts)))
  (testing "standard income chains include Revenue with fallbacks"
    (let [rev (first (filter #(= "Revenue" (first %)) fin/income-statement-concepts))]
      (is (some? rev))
      (is (some #{"Revenues"} (rest rev)))))
  (testing "bank chains include bank-specific line items"
    (let [labels (set (map first fin/bank-income-concepts))]
      (is (contains? labels "Net Interest Income"))
      (is (contains? labels "Noninterest Income"))))
  (testing "insurance chains include insurance-specific line items"
    (let [labels (set (map first fin/insurance-income-concepts))]
      (is (contains? labels "Premiums Earned"))))
  (testing "concept maps carry metadata"
    (is (= :bank (:industry fin/bank-income-concept-map)))
    (is (string? (:version fin/income-statement-concept-map)))))

(deftest concepts-for-test
  (testing "returns chains and metadata for each statement"
    (let [{:keys [chains meta]} (fin/concepts-for :income)]
      (is (= fin/income-statement-concepts chains))
      (is (= :standard (:industry meta)))))
  (testing "industry variants"
    (is (= fin/bank-income-concepts
           (:chains (fin/concepts-for :income :industry :bank))))
    (is (= fin/insurance-income-concepts
           (:chains (fin/concepts-for :income :industry :insurance)))))
  (testing "balance and cash-flow"
    (is (= fin/balance-sheet-concepts (:chains (fin/concepts-for :balance))))
    (is (= fin/cash-flow-concepts (:chains (fin/concepts-for :cash-flow))))))

;;; ---------------------------------------------------------------------------
;;; Unmapped concept logging
;;; ---------------------------------------------------------------------------

(deftest unmapped-concepts-test
  (fin/clear-unmapped-concepts!)
  (let [record! #'edgar.financials/record-unmapped!
        facts-ds (ds/->dataset
                  [{:taxonomy "us-gaap" :concept "Revenues" :form "10-K" :val 1}
                   {:taxonomy "us-gaap" :concept "SomeExoticConcept" :form "10-K" :val 2}
                   {:taxonomy "us-gaap" :concept "OtherFormConcept" :form "10-Q" :val 3}
                   {:taxonomy "dei" :concept "EntityCommonStockSharesOutstanding"
                    :form "10-K" :val 4}])
        chains [["Revenue" "Revenues"]]]
    (record! "0000000001" facts-ds chains "10-K")
    (let [reg (fin/unmapped-concepts)]
      (testing "unmatched us-gaap concept for the form is recorded"
        (is (contains? reg "SomeExoticConcept"))
        (is (= 1 (get-in reg ["SomeExoticConcept" :count])))
        (is (contains? (get-in reg ["SomeExoticConcept" :example-ciks]) "0000000001")))
      (testing "chain-matched concepts are not recorded"
        (is (not (contains? reg "Revenues"))))
      (testing "other-form concepts are not recorded"
        (is (not (contains? reg "OtherFormConcept"))))
      (testing "non-us-gaap taxonomies are not recorded"
        (is (not (contains? reg "EntityCommonStockSharesOutstanding")))))
    (record! "0000000002" facts-ds chains "10-K")
    (testing "repeat sightings increment :count and collect example CIKs"
      (let [entry (get (fin/unmapped-concepts) "SomeExoticConcept")]
        (is (= 2 (:count entry)))
        (is (= #{"0000000001" "0000000002"} (:example-ciks entry)))))
    (testing ":top returns most frequent first"
      (let [top (fin/unmapped-concepts :top 1)]
        (is (= "SomeExoticConcept" (ffirst top)))))
    (fin/clear-unmapped-concepts!)
    (testing "clear! empties the registry"
      (is (empty? (fin/unmapped-concepts))))))

(deftest new-line-items-present-test
  ;; Items added by the Compustat validation passes (2026-07).
  (testing "balance sheet chains include the common research variables"
    (let [labels (set (map first fin/balance-sheet-concepts))]
      (is (contains? labels "Accounts Payable"))
      (is (contains? labels "Current Debt"))
      (is (contains? labels "Income Taxes Payable"))
      (is (contains? labels "Preferred Stock"))
      (is (contains? labels "Noncontrolling Interest"))
      (is (contains? labels "Total Equity"))))
  (testing "cash flow chains include issuance/deferred-tax items and D&A components"
    (let [labels (set (map first fin/cash-flow-concepts))]
      (is (contains? labels "Stock Issued"))
      (is (contains? labels "Deferred Taxes (CF)"))
      (is (contains? labels "Depreciation"))
      (is (contains? labels "Amortization of Intangibles"))))
  (testing "standard income chain includes Interest Expense"
    (let [ie (first (filter #(= "Interest Expense" (first %)) fin/income-statement-concepts))]
      (is (some? ie))
      (is (some #{"InterestExpenseDebt"} (rest ie)))))
  (testing "bank/insurance chains include weighted share counts"
    (is (contains? (set (map first fin/bank-income-concepts)) "Shares Basic"))
    (is (contains? (set (map first fin/insurance-income-concepts)) "Shares Diluted"))))
