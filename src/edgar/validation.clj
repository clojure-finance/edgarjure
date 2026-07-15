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

(defn- parse-date ^java.time.LocalDate [s]
  (when s
    (try (java.time.LocalDate/parse (str s)) (catch Exception _ nil))))

(defn- days-apart ^long [^java.time.LocalDate a ^java.time.LocalDate b]
  (Math/abs (.between java.time.temporal.ChronoUnit/DAYS a b)))

(defn- window-months
  "Approximate months spanned by a duration row (3/6/9/12), nil for instant
   rows or non-quarter windows. Mirrors edgar.financials/duration-months."
  [row]
  (let [s (parse-date (:start row))
        e (parse-date (:end row))]
    (when (and s e)
      (let [days (.between java.time.temporal.ChronoUnit/DAYS s e)]
        (cond
          (<= 75 days 115) 3
          (<= 160 days 200) 6
          (<= 250 days 290) 9
          (<= 340 days 380) 12
          :else nil)))))

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
     :value-key - which statement column to compare (default :val).
                  Use :val-q to validate single-quarter values from 10-Q data
                  against quarterly benchmarks (e.g. Compustat SALEQ/NIQ).
     :date-tolerance-days - allow the benchmark :end and the statement :end to
                  differ by up to this many days (default 0 = exact match).
                  Commercial databases normalise fiscal period ends to
                  calendar month-end while XBRL carries the exact 52/53-week
                  date (e.g. Compustat 2015-09-30 vs Apple's 2015-09-26) —
                  pass ~10 when validating against such sources. Exact date
                  matches always win; otherwise the closest date within
                  tolerance is used.

   Returns:
     {:match-rate  matched / total benchmark rows (nil when benchmark empty)
      :matched     [{:line-item :end :expected :actual} ...]
      :mismatched  [{:line-item :end :expected :actual :rel-diff} ...]
      :missing     [{:line-item :end :expected} ...]}   ; no edgarjure value"
  [ticker-or-cik benchmark & {:keys [statement form view industry as-of tolerance
                                     value-key date-tolerance-days]
                              :or {statement :income form "10-K"
                                   view :standardized tolerance 0.01
                                   value-key :val date-tolerance-days 0}}]
  (let [stmt-fn (case statement
                  :income financials/income-statement
                  :balance financials/balance-sheet
                  :cash-flow financials/cash-flow)
        stmt (stmt-fn ticker-or-cik :form form :view view
                      :industry industry :as-of as-of)
        stmt-rows (->> (ds/rows stmt {:nil-missing? true})
                       (keep (fn [row]
                               (when-some [v (get row value-key)]
                                 {:line-item (:line-item row)
                                  :end-str (str (:end row))
                                  :end-date (parse-date (:end row))
                                  :months (window-months row)
                                  :val v}))))
        by-line-item (group-by :line-item stmt-rows)
        ;; Candidate ranking: smallest date distance first; among candidates at
        ;; the same distance, the LONGEST duration window wins. This matters
        ;; because 10-K facts include quarterly-footnote observations — a Q4
        ;; 3-month row shares its :end date with the fiscal-year row, and an
        ;; annual benchmark must match the 12-month row, not the quarter.
        lookup (fn [line-item end]
                 (let [end-str (str end)
                       target (parse-date end)
                       tol (long date-tolerance-days)]
                   (some->> (get by-line-item line-item)
                            (keep (fn [cand]
                                    (let [dist (cond
                                                 (= (:end-str cand) end-str) 0

                                                 (and (pos? tol) target (:end-date cand))
                                                 (let [d (days-apart (:end-date cand) target)]
                                                   (when (<= d tol) d))

                                                 :else nil)]
                                      (when dist
                                        [[dist (- (long (or (:months cand) 0)))]
                                         (:val cand)]))))
                            seq
                            (sort-by first)
                            first
                            second)))
        rows (->rows benchmark)
        graded (map (fn [{:keys [line-item end val]}]
                      (let [actual (lookup line-item end)]
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
