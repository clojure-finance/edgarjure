(ns edgar.financials
  "Financial statement extraction with normalization and standardization.

   Three output views for each statement (:view option):
     :as-reported   - raw XBRL observations for the statement's concepts,
                      exactly as filed; no deduplication, no label mapping
     :normalized    - (default) fallback chains map variant tags to canonical
                      labels; restatements deduplicated by most-recent-filed
     :standardized  - :normalized plus derived line items imputed from
                      arithmetic identities (e.g. Gross Profit = Revenue -
                      Cost of Revenue). Derived rows carry :method :derived
                      and :derived-from for auditability.

   Concept fallback chains: each line item is a vector of concept names tried
   in order; the first one present in the facts data wins. Chains are loaded
   from EDN files under resources/edgar/concepts/ and exposed as public vars,
   so power users can inspect them or pass their own via :concepts.

   Industry routing: banks (SIC 6000-6199, 6712) and insurers (SIC 6300-6399,
   6411) use fundamentally different income statement line items. When no
   explicit :industry or :concepts is given, income-statement auto-routes on
   the company's SIC code. Pass :industry :standard to force generic chains.

   Duration vs instant:
     Income statement + cash flow -> duration observations (row has :start date)
     Balance sheet               -> instant observations  (row has no :start date)

   Point-in-time / look-ahead-safe mode:
     Pass :as-of \"YYYY-MM-DD\" to any public function to restrict to filings
     where :filed <= as-of-date.  Without :as-of the latest restated value is
     returned (always-latest behaviour).

   Quarterly and LTM derivation (10-Q only, flow variables only):
     :duration-months - 3/6/9/12 classification of the observation window
     :val-q   - single-quarter value. Derived from actual period dates:
                a ~3-month row is used as-is; a YTD row minus the YTD row one
                quarter shorter (same fiscal-year start) yields the quarter
                ending at the longer row's end. The SEC :fy/:fp fields are
                deliberately NOT used — they describe the filing, not the
                observation, and collide across comparative periods.
     :val-ltm - last-twelve-months: sum of four consecutive derived quarters
                matched on period-end dates (±14 days tolerance for 52/53-week
                fiscal calendars). 10-K annual rows participate in the
                derivation so that Q4 = FY - 9M YTD.

   Unmapped-concept logging: every statement call records us-gaap concepts in
   the company's facts that no active chain matched. See unmapped-concepts,
   clear-unmapped-concepts!, save-unmapped-concepts!."
  (:require [edgar.xbrl :as xbrl]
            [edgar.company :as company]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [tech.v3.dataset :as ds])
  (:import [java.time LocalDate]
           [java.time.temporal ChronoUnit]))

;;; ---------------------------------------------------------------------------
;;; Concept chains — loaded from EDN resources
;;; ---------------------------------------------------------------------------

(defn- load-concept-file [resource-path]
  (with-open [r (java.io.PushbackReader. (io/reader (io/resource resource-path)))]
    (edn/read r)))

(defn- chains-from-map
  "Convert an EDN concept map ({:line-items [{:label ... :concepts [...]}]})
   into the chain format used internally: [[label concept1 concept2 ...] ...]"
  [m]
  (mapv (fn [{:keys [label concepts]}] (into [label] concepts))
        (:line-items m)))

(def income-statement-concept-map
  "Full EDN concept map (with metadata) for the standard income statement.
   Source: resources/edgar/concepts/income-statement.edn"
  (load-concept-file "edgar/concepts/income-statement.edn"))

(def balance-sheet-concept-map
  "Full EDN concept map (with metadata) for the balance sheet.
   Source: resources/edgar/concepts/balance-sheet.edn"
  (load-concept-file "edgar/concepts/balance-sheet.edn"))

(def cash-flow-concept-map
  "Full EDN concept map (with metadata) for the cash flow statement.
   Source: resources/edgar/concepts/cash-flow.edn"
  (load-concept-file "edgar/concepts/cash-flow.edn"))

(def bank-income-concept-map
  "Income statement concept map for banks (SIC 6000-6199, 6712).
   Source: resources/edgar/concepts/bank-income.edn"
  (load-concept-file "edgar/concepts/bank-income.edn"))

(def insurance-income-concept-map
  "Income statement concept map for insurers (SIC 6300-6399, 6411).
   Source: resources/edgar/concepts/insurance-income.edn"
  (load-concept-file "edgar/concepts/insurance-income.edn"))

(def income-statement-concepts
  "Income statement fallback chains: [[label concept1 concept2 ...] ...]"
  (chains-from-map income-statement-concept-map))

(def balance-sheet-concepts
  "Balance sheet fallback chains: [[label concept1 concept2 ...] ...]"
  (chains-from-map balance-sheet-concept-map))

(def cash-flow-concepts
  "Cash flow fallback chains: [[label concept1 concept2 ...] ...]"
  (chains-from-map cash-flow-concept-map))

(def bank-income-concepts
  "Bank income statement fallback chains."
  (chains-from-map bank-income-concept-map))

(def insurance-income-concepts
  "Insurance income statement fallback chains."
  (chains-from-map insurance-income-concept-map))

;;; ---------------------------------------------------------------------------
;;; Industry routing
;;; ---------------------------------------------------------------------------

(defn industry-for-sic
  "Map a SIC code (string or number) to an industry keyword:
     :bank      - SIC 6000-6199 or 6712 (bank holding companies)
     :insurance - SIC 6300-6399 or 6411
     :reit      - SIC 6500-6553 (no dedicated chains yet; treated as :standard)
     :standard  - everything else (or unknown SIC)"
  [sic]
  (let [n (when sic
            (try (Long/parseLong (str sic)) (catch Exception _ nil)))]
    (cond
      (nil? n) :standard
      (or (<= 6000 n 6199) (= n 6712)) :bank
      (or (<= 6300 n 6399) (= n 6411)) :insurance
      (<= 6500 n 6553) :reit
      :else :standard)))

(defn- detect-industry
  "Look up the company's SIC code (submissions endpoint, cached) and route it.
   Falls back to :standard when the lookup fails."
  [ticker-or-cik]
  (try
    (industry-for-sic (:sic (company/company-metadata ticker-or-cik)))
    (catch Exception _ :standard)))

(defn- income-chains-for-industry [industry]
  (case industry
    :bank bank-income-concepts
    :insurance insurance-income-concepts
    income-statement-concepts))

(defn concepts-for
  "Return the active concept chains for a statement, for inspection/extension.
   statement: :income | :balance | :cash-flow
   Options:
     :industry - :standard (default) | :bank | :insurance (income only)
   Returns {:chains [[label c1 c2 ...] ...] :meta <EDN map metadata>}"
  [statement & {:keys [industry] :or {industry :standard}}]
  (case statement
    :income (case industry
              :bank {:chains bank-income-concepts
                     :meta (dissoc bank-income-concept-map :line-items)}
              :insurance {:chains insurance-income-concepts
                          :meta (dissoc insurance-income-concept-map :line-items)}
              {:chains income-statement-concepts
               :meta (dissoc income-statement-concept-map :line-items)})
    :balance {:chains balance-sheet-concepts
              :meta (dissoc balance-sheet-concept-map :line-items)}
    :cash-flow {:chains cash-flow-concepts
                :meta (dissoc cash-flow-concept-map :line-items)}))

;;; ---------------------------------------------------------------------------
;;; Unmapped concept logging (roadmap 4.1e)
;;; ---------------------------------------------------------------------------

(def ^:private unmapped-registry (atom {}))

(defn- record-unmapped!
  "Record us-gaap concepts present in facts-ds (for the requested form) that
   no active chain matches. Feeds the concept-coverage feedback loop."
  [cik facts-ds chains form]
  (when (pos? (ds/row-count facts-ds))
    (let [known (set (mapcat rest chains))
          concepts (ds/column facts-ds :concept)
          taxonomies (ds/column facts-ds :taxonomy)
          forms (ds/column facts-ds :form)
          n (ds/row-count facts-ds)
          unmapped (persistent!
                    (reduce (fn [acc i]
                              (let [c (nth concepts i)]
                                (if (and (= "us-gaap" (nth taxonomies i))
                                         (= form (nth forms i))
                                         (not (known c)))
                                  (conj! acc c)
                                  acc)))
                            (transient #{})
                            (range n)))
          today (str (LocalDate/now))]
      (when (seq unmapped)
        (swap! unmapped-registry
               (fn [reg]
                 (reduce (fn [r c]
                           (update r c
                                   (fn [e]
                                     (-> (or e {:count 0 :first-seen today :example-ciks #{}})
                                         (update :count inc)
                                         (update :example-ciks
                                                 #(if (< (count %) 5) (conj % cik) %))))))
                         reg unmapped)))))))

(defn unmapped-concepts
  "Return the unmapped-concept registry accumulated this session:
   {concept {:count n :first-seen \"YYYY-MM-DD\" :example-ciks #{...}}}
   Options:
     :top - return only the n most frequent, as a seq of [concept info] pairs"
  [& {:keys [top]}]
  (let [reg @unmapped-registry]
    (if top
      (take top (sort-by (comp - :count val) reg))
      reg)))

(defn clear-unmapped-concepts!
  "Reset the unmapped-concept registry."
  []
  (reset! unmapped-registry {}))

(defn save-unmapped-concepts!
  "Write the unmapped-concept registry as EDN.
   Options:
     :path - output file (default ~/.edgarjure/unmapped-concepts.edn)
   Returns the path written."
  [& {:keys [path]}]
  (let [out (or path (str (System/getProperty "user.home")
                          "/.edgarjure/unmapped-concepts.edn"))]
    (io/make-parents out)
    (spit out (pr-str @unmapped-registry))
    out))

;;; ---------------------------------------------------------------------------
;;; Internal helpers
;;; ---------------------------------------------------------------------------

(defn- instant? [row]
  (nil? (:start row)))

(defn- duration? [row]
  (some? (:start row)))

(defn- concepts-in-data [facts-ds]
  (if (zero? (ds/row-count facts-ds))
    #{}
    (set (ds/column facts-ds :concept))))

(defn- resolve-fallback [chain available-concepts]
  (let [label (first chain)
        candidates (rest chain)
        winners (filter available-concepts candidates)]
    (when (seq winners)
      [label winners])))

(defn- resolve-all-chains [chains available-concepts]
  (keep #(resolve-fallback % available-concepts) chains))

(defn- dedup-restatements
  "Keep the most recently filed observation per [concept unit start end] tuple.

   Using :start in the key preserves distinct duration windows — e.g. a
   3-month Q3 observation (start=July 1) and a 9-month YTD observation
   (start=January 1) both ending September 30 are kept as separate rows."
  [rows]
  (->> rows
       (group-by (juxt :concept :unit :start :end))
       (map (fn [[_ group]]
              (reduce #(if (pos? (compare (:filed %1) (:filed %2))) %1 %2) group)))))

(defn- dedup-point-in-time
  "Point-in-time (look-ahead-safe) restatement deduplication.

   For each [concept unit start end] tuple, keeps the most recently filed
   observation among those where :filed <= as-of-date. Observations filed
   after as-of-date are excluded entirely.

   as-of-date is an ISO date string (\"YYYY-MM-DD\") or nil (falls back to
   dedup-restatements, i.e. always-latest behaviour)."
  [rows as-of-date]
  (if (nil? as-of-date)
    (dedup-restatements rows)
    (->> rows
         (filter #(not (pos? (compare (:filed %) as-of-date))))
         (group-by (juxt :concept :unit :start :end))
         (map (fn [[_ group]]
                (reduce #(if (pos? (compare (:filed %1) (:filed %2))) %1 %2) group))))))

(defn- dedup-by-priority
  "Within each [line-item unit start end] group, keep only the row whose
   concept has the lowest priority index in its fallback chain.

   This resolves the case where a company files multiple concepts from the
   same chain for the same period. The chain ordering defines preference:
   index 0 = most preferred."
  [rows concept->label concept->priority]
  (->> rows
       (group-by (fn [row]
                   [(get concept->label (:concept row) (:concept row))
                    (:unit row) (:start row) (:end row)]))
       (mapcat (fn [[_ group]]
                 (if (= 1 (count group))
                   group
                   [(reduce (fn [best row]
                              (if (< (get concept->priority (:concept row) Integer/MAX_VALUE)
                                     (get concept->priority (:concept best) Integer/MAX_VALUE))
                                row
                                best))
                            group)])))))

(defn- filter-by-duration-type [rows duration-type]
  (case duration-type
    :instant (filter instant? rows)
    :duration (filter duration? rows)
    :any rows))

;;; ---------------------------------------------------------------------------
;;; Quarterly and LTM derivation — date-window based
;;;
;;; The SEC :fy/:fp fields describe the FILING a row came from, not the row's
;;; own period: a Q2 10-Q carries current 3-month, current YTD, and prior-year
;;; comparative rows all tagged with the same fy/fp. Keying on fy/fp therefore
;;; collides and produces garbage quarters. Everything below works from the
;;; actual :start/:end dates instead.
;;; ---------------------------------------------------------------------------

(defn- parse-date ^LocalDate [s]
  (when s
    (let [s (str s)]
      (when-not (empty? s)
        (try (LocalDate/parse s) (catch Exception _ nil))))))

(defn- days-between ^long [^LocalDate a ^LocalDate b]
  (.between ChronoUnit/DAYS a b))

(defn- duration-months
  "Classify a duration row's window as 3, 6, 9 or 12 months, or nil when the
   row is instant or does not resemble a whole number of fiscal quarters.
   Ranges are tolerant of 52/53-week fiscal calendars."
  [row]
  (let [s (parse-date (:start row))
        e (parse-date (:end row))]
    (when (and s e)
      (let [days (days-between s e)]
        (cond
          (<= 75 days 115) 3
          (<= 160 days 200) 6
          (<= 250 days 290) 9
          (<= 340 days 380) 12
          :else nil)))))

(defn- line-key [row]
  (or (:line-item row) (:concept row)))

(defn- quarter-values
  "Derive single-quarter values from duration rows.
   Returns {[line-item unit] {end-LocalDate quarter-value}}.

   Sources, later entries winning:
     1. YTD differencing — a k-month row minus the (k-3)-month row sharing the
        same fiscal-year :start yields the quarter ending at the longer row's
        end. 12-month 10-K rows participate here, yielding Q4 = FY - 9M.
     2. Directly reported ~3-month rows (used as-is; override diffs)."
  [rows]
  (->> rows
       (keep (fn [row]
               (when-let [m (duration-months row)]
                 (assoc row
                        ::months m
                        ::start-d (parse-date (:start row))
                        ::end-d (parse-date (:end row))))))
       (group-by (fn [row] [(line-key row) (:unit row)]))
       (reduce-kv
        (fn [acc group-key grows]
          (let [by-start-months (into {} (map (fn [r] [[(::start-d r) (::months r)] r]))
                                      grows)
                diffs (keep (fn [r]
                              (when (> (long (::months r)) 3)
                                (when-let [prior (get by-start-months
                                                      [(::start-d r) (- (long (::months r)) 3)])]
                                  (when (and (:val r) (:val prior))
                                    [(::end-d r) (- (:val r) (:val prior))]))))
                            grows)
                directs (keep (fn [r]
                                (when (and (= 3 (::months r)) (:val r))
                                  [(::end-d r) (:val r)]))
                              grows)]
            (assoc acc group-key (into {} (concat diffs directs)))))
        {})))

(defn- lookup-quarter
  "Find the quarter value whose end date is within tol-days of target.
   Ties resolve to the closest date."
  [qmap ^LocalDate target tol-days]
  (when (and qmap target)
    (some->> qmap
             (keep (fn [[^LocalDate d v]]
                     (let [dist (Math/abs (days-between d target))]
                       (when (<= dist (long tol-days)) [dist v]))))
             seq
             (apply min-key first)
             second)))

(defn- row-val-q [row qmap]
  (let [m (duration-months row)]
    (cond
      (nil? m) nil
      (= 3 m) (:val row)
      :else (lookup-quarter qmap (parse-date (:end row)) 5))))

(defn- row-val-ltm [row qmap]
  (when (and qmap (duration-months row))
    (when-let [e (parse-date (:end row))]
      (let [targets (map #(.minusMonths e (long %)) [0 3 6 9])
            qvals (map #(lookup-quarter qmap % 14) targets)]
        (when (every? some? qvals)
          (reduce + qvals))))))

(defn- add-quarterly-and-ltm
  "Add :duration-months, :val-q, and :val-ltm columns to a duration dataset.
   Only applies to 10-Q data; returns the dataset unchanged for other forms.

   annual-rows are the company's 10-K duration observations for the same line
   items; they participate in quarter derivation (Q4 = FY - 9M YTD) so that
   :val-ltm windows crossing a fiscal Q4 are computable."
  [ds form annual-rows]
  (if (or (not= form "10-Q") (zero? (ds/row-count ds)))
    ds
    (let [rows (vec (ds/rows ds {:nil-missing? true}))
          qmaps (quarter-values (concat rows annual-rows))
          out (mapv (fn [row]
                      (let [qmap (get qmaps [(line-key row) (:unit row)])]
                        (assoc row
                               :duration-months (duration-months row)
                               :val-q (row-val-q row qmap)
                               :val-ltm (row-val-ltm row qmap))))
                    rows)]
      (ds/->dataset out))))

;;; ---------------------------------------------------------------------------
;;; Derived line items (imputation) — :view :standardized
;;; ---------------------------------------------------------------------------

(def income-statement-identities
  "Arithmetic identities used by :view :standardized to impute missing income
   statement line items. Applied per [unit start end] period group, only when
   the target is absent and all operands are present."
  [{:target "Gross Profit" :formula [:- "Revenue" "Cost of Revenue"]}
   {:target "Revenue" :formula [:+ "Gross Profit" "Cost of Revenue"]}
   {:target "Cost of Revenue" :formula [:- "Revenue" "Gross Profit"]}
   {:target "Operating Income" :formula [:- "Gross Profit" "Operating Expenses"]}
   {:target "Operating Expenses" :formula [:- "Gross Profit" "Operating Income"]}
   {:target "Pre-Tax Income" :formula [:+ "Net Income" "Income Tax Expense"]}
   {:target "Net Income" :formula [:- "Pre-Tax Income" "Income Tax Expense"]}])

(def balance-sheet-identities
  "Arithmetic identities for balance sheet imputation (:view :standardized)."
  [{:target "Total Assets" :formula [:= "Total Liabilities and Equity"]}
   {:target "Total Liabilities and Equity" :formula [:= "Total Assets"]}
   {:target "Total Liabilities" :formula [:- "Total Liabilities and Equity" "Stockholders Equity"]}
   {:target "Stockholders Equity" :formula [:- "Total Liabilities and Equity" "Total Liabilities"]}
   {:target "Non-Current Assets" :formula [:- "Total Assets" "Current Assets"]}
   {:target "Non-Current Liabilities" :formula [:- "Total Liabilities" "Current Liabilities"]}])

(def cash-flow-identities
  "Arithmetic identities for cash flow imputation (:view :standardized).
   Free Cash Flow is a derived-only item (never reported directly in XBRL)."
  [{:target "Free Cash Flow" :formula [:- "Operating Cash Flow" "Capex"]}])

(defn- eval-formula [op vals]
  (case op
    :+ (apply + vals)
    :- (apply - vals)
    := (first vals)))

(defn- apply-identities
  "Synthesize missing line items per [unit start end] period group from
   arithmetic identities. Derived rows copy period fields from their first
   operand and carry :method :derived, :derived-from [operand labels] and a
   nil :concept. Iterates so chained identities resolve (capped at 3 passes)."
  [rows identities]
  (->> rows
       (group-by (juxt :unit :start :end))
       (mapcat
        (fn [[_ grows]]
          (loop [pass 0
                 acc (vec grows)]
            (if (>= pass 3)
              acc
              (let [by-li (into {} (map (juxt :line-item identity)) acc)
                    new-rows
                    (keep (fn [{:keys [target formula]}]
                            (when-not (contains? by-li target)
                              (let [[op & operands] formula
                                    vals (map #(:val (get by-li %)) operands)]
                                (when (every? some? vals)
                                  (-> (get by-li (first operands))
                                      (assoc :line-item target
                                             :val (eval-formula op vals)
                                             :concept nil
                                             :method :derived
                                             :derived-from (vec operands)))))))
                          identities)]
                (if (empty? new-rows)
                  acc
                  (recur (inc pass) (into acc new-rows))))))))))

;;; ---------------------------------------------------------------------------
;;; Statement builders
;;; ---------------------------------------------------------------------------

(defn- as-reported-statement
  "Layer 1 (:view :as-reported): observations exactly as filed for the
   statement's candidate concepts + form + duration type. No deduplication,
   no label mapping, no derived rows."
  [facts-ds chains form duration-type]
  (let [all-concepts (set (mapcat rest chains))
        rows (-> facts-ds
                 (ds/filter-column :concept #(contains? all-concepts %))
                 (ds/filter-column :form #(= % form))
                 (ds/rows {:nil-missing? true})
                 (filter-by-duration-type duration-type))]
    (ds/->dataset (vec rows))))

(defn- statement-rows
  "Filter, dedup, and label facts rows for one statement/form combination.
   Returns a seq of row maps with :line-item and :method :direct."
  [facts-ds winning-concepts concept->label concept->priority form duration-type as-of]
  (let [filtered (-> facts-ds
                     (ds/filter-column :concept #(contains? winning-concepts %))
                     (ds/filter-column :form #(= % form))
                     (ds/rows {:nil-missing? true}))
        duration-filtered (filter-by-duration-type filtered duration-type)
        deduped (dedup-point-in-time duration-filtered as-of)
        priority-deduped (dedup-by-priority deduped concept->label concept->priority)]
    (map #(assoc %
                 :line-item (get concept->label (:concept %) (:concept %))
                 :method :direct)
         priority-deduped)))

(defn- normalized-statement
  "Build a normalized long-format statement dataset (:view :normalized), or
   the standardized variant when identities is non-nil (:view :standardized).

   Steps:
     1. Resolve fallback chains -> collect ALL present candidates per line item
     2. Filter facts to winning concepts + form + duration type
     3. Deduplicate restatements (dedup-point-in-time honours :as-of)
     4. Deduplicate overlapping chain candidates via chain priority
     5. Attach :line-item and :method :direct
     6. When identities given: impute missing items per period (:method :derived)
     7. For 10-Q duration statements: derive :duration-months/:val-q/:val-ltm,
        blending the company's 10-K annual rows for Q4/LTM computation
     8. Sort :end descending, :line-item ascending within each period"
  [facts-ds chains form duration-type as-of identities]
  (let [available (concepts-in-data facts-ds)
        resolved (resolve-all-chains chains available)
        concept->label (into {} (mapcat (fn [[label winners]]
                                          (map (fn [w] [w label]) winners))
                                        resolved))
        winning-concepts (set (keys concept->label))
        concept->priority (into {}
                                (for [chain chains
                                      :let [candidates (rest chain)]
                                      [idx concept] (map-indexed vector candidates)
                                      :when (contains? winning-concepts concept)]
                                  [concept idx]))]
    (if (empty? winning-concepts)
      (ds/->dataset [])
      (let [build-rows (fn [f]
                         (cond-> (statement-rows facts-ds winning-concepts
                                                 concept->label concept->priority
                                                 f duration-type as-of)
                           identities (apply-identities identities)))
            rows (build-rows form)
            annual-rows (when (and (= form "10-Q") (= duration-type :duration))
                          (build-rows "10-K"))
            result-ds (ds/->dataset (vec rows))]
        (if (zero? (ds/row-count result-ds))
          result-ds
          (-> result-ds
              (add-quarterly-and-ltm form annual-rows)
              (ds/sort-by
               (fn [row] [(:end row) (:line-item row)])
               (fn [a b]
                 (let [c (compare (first b) (first a))]
                   (if (zero? c)
                     (compare (second a) (second b))
                     c))))))))))

;;; ---------------------------------------------------------------------------
;;; Wide-format pivot
;;; ---------------------------------------------------------------------------

(defn- to-wide
  "Pivot a long-format statement dataset to wide format.
   One row per period (:end), one column per line item.

   When both a ~3-month and a YTD row exist for the same [end line-item]
   (10-Q flow statements), the longest-duration row supplies the plain value
   (the reported YTD figure) and the derived :val-q/:val-ltm — which are
   period-level, not row-level — are taken from whichever row has them.
   They appear as \"<line-item> (Q)\" and \"<line-item> (LTM)\" columns."
  [ds]
  (if (zero? (ds/row-count ds))
    ds
    (let [has-q? (boolean (some #{:val-q} (ds/column-names ds)))
          has-ltm? (boolean (some #{:val-ltm} (ds/column-names ds)))
          reps (->> (ds/rows ds {:nil-missing? true})
                    (group-by (juxt :end :line-item))
                    vals
                    (map (fn [grows]
                           (-> (apply max-key #(or (:duration-months %) 0) grows)
                               (assoc :val-q (some :val-q grows)
                                      :val-ltm (some :val-ltm grows))))))]
      (ds/->dataset
       (->> reps
            (group-by :end)
            (sort-by key #(compare %2 %1))
            (map (fn [[period rows]]
                   (reduce (fn [m r]
                             (let [li (:line-item r)]
                               (cond-> (assoc m li (:val r))
                                 has-q? (assoc (str li " (Q)") (:val-q r))
                                 has-ltm? (assoc (str li " (LTM)") (:val-ltm r)))))
                           {:end period}
                           rows))))))))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn- shape-result [result shape]
  (if (= shape :wide) (to-wide result) result))

(defn- statement-identities [statement-key]
  (case statement-key
    :income income-statement-identities
    :balance balance-sheet-identities
    :cash-flow cash-flow-identities))

(defn- build-statement
  [ticker-or-cik statement-key duration-type default-chains
   {:keys [form concepts shape as-of view industry]
    :or {form "10-K" shape :long view :normalized}}]
  (let [cik (company/company-cik ticker-or-cik)
        chains (or concepts
                   (if (= statement-key :income)
                     (income-chains-for-industry
                      (or industry (detect-industry cik)))
                     default-chains))
        facts (xbrl/get-facts-dataset cik)]
    (record-unmapped! cik facts chains form)
    (shape-result
     (case view
       :as-reported (as-reported-statement facts chains form duration-type)
       :normalized (normalized-statement facts chains form duration-type as-of nil)
       :standardized (normalized-statement facts chains form duration-type as-of
                                           (statement-identities statement-key)))
     shape)))

(defn income-statement
  "Return normalized income statement as a long-format dataset.

   Options:
     :form     - \"10-K\" (default) or \"10-Q\"
     :concepts - override the fallback chains (disables industry routing)
     :shape    - :long (default) or :wide
     :view     - :normalized (default) | :as-reported | :standardized
                 :as-reported skips dedup and label mapping entirely;
                 :standardized additionally imputes missing line items from
                 arithmetic identities (rows carry :method :derived)
     :industry - :standard | :bank | :insurance. When omitted, auto-detected
                 from the company's SIC code (banks and insurers get
                 industry-specific chains)
     :as-of    - ISO date string \"YYYY-MM-DD\" (default nil).
                 When set, excludes filings where :filed > as-of-date,
                 giving point-in-time / look-ahead-safe results suitable
                 for backtesting and event studies.

   For 10-Q queries, long-format output includes :duration-months, :val-q
   (single-quarter value) and :val-ltm (trailing twelve months) columns
   derived from actual period-date windows."
  [ticker-or-cik & {:as opts}]
  (build-statement ticker-or-cik :income :duration income-statement-concepts opts))

(defn balance-sheet
  "Return normalized balance sheet as a long-format dataset.

   Options:
     :form     - \"10-K\" (default) or \"10-Q\"
     :concepts - override balance-sheet-concepts
     :shape    - :long (default) or :wide
     :view     - :normalized (default) | :as-reported | :standardized
     :as-of    - ISO date string \"YYYY-MM-DD\" (default nil).
                 When set, excludes filings where :filed > as-of-date,
                 giving point-in-time / look-ahead-safe results."
  [ticker-or-cik & {:as opts}]
  (build-statement ticker-or-cik :balance :instant balance-sheet-concepts opts))

(defn cash-flow
  "Return normalized cash flow statement as a long-format dataset.

   Options:
     :form     - \"10-K\" (default) or \"10-Q\"
     :concepts - override cash-flow-concepts
     :shape    - :long (default) or :wide
     :view     - :normalized (default) | :as-reported | :standardized
                 (:standardized adds a derived \"Free Cash Flow\" line item)
     :as-of    - ISO date string \"YYYY-MM-DD\" (default nil).
                 When set, excludes filings where :filed > as-of-date,
                 giving point-in-time / look-ahead-safe results.

   For 10-Q queries, long-format output includes :duration-months, :val-q
   (single-quarter value) and :val-ltm (trailing twelve months) columns
   derived from actual period-date windows."
  [ticker-or-cik & {:as opts}]
  (build-statement ticker-or-cik :cash-flow :duration cash-flow-concepts opts))

(defn get-financials
  "Return all three normalized statements for a company.

   Returns {:income-statement ds :balance-sheet ds :cash-flow ds}

   Options:
     :form     - \"10-K\" (default) or \"10-Q\"
     :shape    - :long (default) or :wide
     :view     - :normalized (default) | :as-reported | :standardized
     :industry - :standard | :bank | :insurance (income statement only;
                 auto-detected from SIC when omitted)
     :as-of    - ISO date string \"YYYY-MM-DD\" (default nil).
                 All three statements use point-in-time deduplication:
                 filings where :filed > as-of-date are excluded.

   For 10-Q queries, long-format income and cash flow include :val-q and
   :val-ltm columns. Balance sheet is unaffected (instant observations)."
  [ticker-or-cik & {:keys [form shape as-of view industry]
                    :or {form "10-K" shape :long view :normalized}}]
  (let [opts {:form form :shape shape :as-of as-of :view view :industry industry}]
    {:income-statement (build-statement ticker-or-cik :income :duration
                                        income-statement-concepts opts)
     :balance-sheet (build-statement ticker-or-cik :balance :instant
                                     balance-sheet-concepts opts)
     :cash-flow (build-statement ticker-or-cik :cash-flow :duration
                                 cash-flow-concepts opts)}))
