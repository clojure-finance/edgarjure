(ns edgar.validation
  "Validation harness — quantify how close edgarjure's statement output is to
   a benchmark (Compustat/Capital IQ extract, hand-collected figures, academic
   reconciliation datasets).

   Usage:
     (require '[edgar.validation :as validation])
     (validation/compare-to-benchmark \"AAPL\"
       [{:line-item \"Revenue\"    :end \"2023-09-30\" :val 383285000000}
        {:line-item \"Net Income\" :end \"2023-09-30\" :val 96995000000}])
     ;=> {:match-rate 1.0 :matched [...] :mismatched [] :missing []}

   Track :match-rate over time as concept files and identities improve."
  (:require [edgar.financials :as financials]
            [tech.v3.dataset :as ds]))

(defn- ->rows [benchmark]
  (if (ds/dataset? benchmark)
    (ds/rows benchmark {:nil-missing? true})
    benchmark))

(defn- close-enough? [expected actual tolerance]
  (cond
    (or (nil? expected) (nil? actual)) false
    (zero? (double expected)) (< (Math/abs (double actual)) 1e-9)
    :else (<= (Math/abs (/ (- (double actual) (double expected))
                           (double expected)))
              (double tolerance))))

(defn compare-to-benchmark
  "Compare a company's statement line items against benchmark values.

   benchmark — a dataset or seq of maps with keys :line-item :end :val
               (:end as an ISO date string matching the fiscal period end)

   Options:
     :statement - :income (default) | :balance | :cash-flow
     :form      - \"10-K\" (default) or \"10-Q\"
     :view      - statement view to validate (default :standardized)
     :industry, :as-of - passed through to the statement function
     :tolerance - relative tolerance for a match (default 0.01 = 1%)

   Returns:
     {:match-rate  matched / total benchmark rows (nil when benchmark empty)
      :matched     [{:line-item :end :expected :actual} ...]
      :mismatched  [{:line-item :end :expected :actual :rel-diff} ...]
      :missing     [{:line-item :end :expected} ...]}   ; no edgarjure value"
  [ticker-or-cik benchmark & {:keys [statement form view industry as-of tolerance]
                              :or {statement :income form "10-K"
                                   view :standardized tolerance 0.01}}]
  (let [stmt-fn (case statement
                  :income financials/income-statement
                  :balance financials/balance-sheet
                  :cash-flow financials/cash-flow)
        stmt (stmt-fn ticker-or-cik :form form :view view
                      :industry industry :as-of as-of)
        actuals (reduce (fn [m row]
                          ;; first row per key wins: rows are sorted :end desc
                          ;; and, within a period, direct rows come before any
                          ;; conflicting duplicates after dedup
                          (let [k [(:line-item row) (str (:end row))]]
                            (if (contains? m k) m (assoc m k (:val row)))))
                        {}
                        (ds/rows stmt {:nil-missing? true}))
        rows (->rows benchmark)
        graded (map (fn [{:keys [line-item end val]}]
                      (let [actual (get actuals [line-item (str end)])]
                        (cond
                          (nil? actual)
                          {:status :missing
                           :entry {:line-item line-item :end end :expected val}}

                          (close-enough? val actual tolerance)
                          {:status :matched
                           :entry {:line-item line-item :end end
                                   :expected val :actual actual}}

                          :else
                          {:status :mismatched
                           :entry {:line-item line-item :end end
                                   :expected val :actual actual
                                   :rel-diff (when-not (zero? (double val))
                                               (double (/ (- actual val) val)))}})))
                    rows)
        by-status (group-by :status graded)
        total (count rows)]
    {:match-rate (when (pos? total)
                   (double (/ (count (:matched by-status)) total)))
     :matched (mapv :entry (:matched by-status))
     :mismatched (mapv :entry (:mismatched by-status))
     :missing (mapv :entry (:missing by-status))}))
