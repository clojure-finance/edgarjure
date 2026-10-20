(ns edgar.financials
  "Financial statement extraction with normalization and standardization.

   Four output views for each statement (:view option):
     :as-reported   - raw XBRL observations for the statement's concepts,
                      exactly as filed; no deduplication, no label mapping
                      (:as-of still excludes filings made after the date)
     :normalized    - (default) fallback chains map variant tags to canonical
                      labels; restatements of the same concept deduplicated
                      by most-recent-filed, and where several concepts of one
                      chain report the same period, chain priority (the
                      earlier concept) wins
     :standardized  - :normalized plus derived line items imputed from
                      arithmetic identities (e.g. Gross Profit = Revenue -
                      Cost of Revenue). Derived rows carry :method :derived
                      and :derived-from for auditability.
     :compustat     - :standardized plus line items reclassified to
                      approximate Compustat definitions (D&A out of COGS,
                      R&D folded into XSGA, special items added back to
                      operating income, ...). Reclassified rows carry
                      :method :reclassified, :rule and :derived-from.
                      Rules live in resources/edgar/reclass/ (edgar.reclass);
                      income statement and balance sheet (cash flow equals
                      :standardized). With the FSDS cache enabled
                      (edgar.fsds/enable-cache!), 10-K income periods gain
                      statement-placement guards and extension-tag operands.

   Concept fallback chains: each line item is a vector of concept names tried
   in order; the first one present in the facts data wins. Chains are loaded
   from EDN files under resources/edgar/concepts/ and exposed as public vars,
   so power users can inspect them or pass their own via :concepts.

   Industry routing: banks (SIC 6000-6199, 6712), insurers (SIC 6300-6399,
   6411) and REITs (SIC 6798, 6500-6553) use fundamentally different income
   statement line items. When no explicit :industry or :concepts is given,
   income-statement auto-routes on the company's SIC code. Pass :industry
   :standard to force generic chains.

   Duration vs instant:
     Income statement + cash flow -> duration observations (row has :start date)
     Balance sheet               -> instant observations  (row has no :start date)

   10-K duration statements: the facts filed with a 10-K include quarterly-
   footnote observations (e.g. fourth-quarter or selected quarterly data)
   alongside the fiscal-year figures. Long output keeps every window and
   carries a :duration-months column (3/6/9/12, nil for irregular windows)
   so callers can select (= 12 :duration-months); :shape :wide keeps only the
   annual (non-quarterly) windows. Under :view :compustat only the annual
   (and irregular) windows are reclassified: the quarterly-footnote windows
   lack most rule operands (cash-flow D&A, special items), so a reclassified
   row there would be a relabeled as-reported value.

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
     :val-ltm - last-twelve-months: sum of four consecutive derived quarters,
                chained by adjacency (each quarter ends within a few days of
                the day before the next one starts) and spanning a 12-month
                window, so uneven fiscal calendars (52/53-week years, a
                12/12/12/16-week split) chain as well as calendar quarters.
                The company's 10-K fiscal-year (12-month) rows participate in
                the derivation so that Q4 = FY - 9M YTD; the 10-K's own
                quarterly-footnote rows do not (the 10-Q's own quarters and
                YTD differences are the quarterly source).
     Non-additive units — share counts (\"shares\") and per-share amounts
     (\"USD/shares\") — are never differenced or summed: their :val-q is the
     directly reported 3-month value when one exists (else nil) and their
     :val-ltm is always nil.
     Under :view :compustat the 10-K rows feeding Q4 = FY - 9M are built
     WITHOUT the FSDS operands even when the FSDS cache is enabled: 10-Q
     periods never receive FSDS operands, and mixing the two rule regimes
     would push a full year's reclassification effect into the derived Q4.

   Unmapped-concept logging: every statement call records the us-gaap
   concepts in the company's facts (for the requested form) that no built-in
   chain of any statement or industry — nor a custom :concepts chain — maps.
   Each company counts once per concept. See unmapped-concepts,
   clear-unmapped-concepts!, save-unmapped-concepts!."
  (:require [edgar.xbrl :as xbrl]
            [edgar.company :as company]
            [edgar.reclass :as reclass]
            [edgar.fsds :as fsds]
            [edgar.util :as util]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [tech.v3.dataset :as ds])
  (:import [java.time LocalDate]))

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

(def reit-income-concept-map
  "Income statement concept map for REITs (SIC 6798, 6500-6553).
   Source: resources/edgar/concepts/reit-income.edn"
  (load-concept-file "edgar/concepts/reit-income.edn"))

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

(def reit-income-concepts
  "REIT income statement fallback chains."
  (chains-from-map reit-income-concept-map))

;;; ---------------------------------------------------------------------------
;;; Industry routing
;;; ---------------------------------------------------------------------------

(defn industry-for-sic
  "Map a SIC code (string or number) to an industry keyword:
     :bank      - SIC 6000-6199 or 6712 (bank holding companies)
     :insurance - SIC 6300-6399 or 6411
     :reit      - SIC 6798 (REITs) or 6500-6553 (real estate operators)
     :standard  - everything else (or unknown SIC)"
  [sic]
  (let [n (when sic
            (try (Long/parseLong (str sic)) (catch Exception _ nil)))]
    (cond
      (nil? n) :standard
      (or (<= 6000 n 6199) (= n 6712)) :bank
      (or (<= 6300 n 6399) (= n 6411)) :insurance
      (or (= n 6798) (<= 6500 n 6553)) :reit
      :else :standard)))

(defn- detect-industry
  "Look up the company's SIC code (submissions endpoint, cached) and route it.
   Falls back to :standard when the lookup fails."
  [ticker-or-cik]
  (try
    (industry-for-sic (:sic (company/company-metadata ticker-or-cik)))
    (catch Exception _ :standard)))

(def ^:private statement-concept-maps
  "The one statement/industry -> EDN concept map table; concepts-for and the
   income-statement routing both read it."
  {[:income :standard] income-statement-concept-map
   [:income :bank] bank-income-concept-map
   [:income :insurance] insurance-income-concept-map
   [:income :reit] reit-income-concept-map
   [:balance :standard] balance-sheet-concept-map
   [:cash-flow :standard] cash-flow-concept-map})

(def ^:private statement-chains
  (update-vals statement-concept-maps chains-from-map))

(defn- income-chains-for-industry [industry]
  (get statement-chains [:income industry]
       (statement-chains [:income :standard])))

(defn concepts-for
  "Return the active concept chains for a statement, for inspection/extension.
   statement: :income | :balance | :cash-flow
   Options:
     :industry - :standard (default) | :bank | :insurance | :reit (income only;
                 an unknown industry falls back to :standard)
   Returns {:chains [[label c1 c2 ...] ...] :meta <EDN map metadata>}"
  [statement & {:keys [industry] :or {industry :standard}}]
  (let [k (if (= :income statement) [:income industry] [statement :standard])
        k (if (contains? statement-concept-maps k) k [statement :standard])]
    (when-not (contains? statement-concept-maps k)
      (throw (ex-info (str "Unknown statement: " statement
                           " (expected :income :balance :cash-flow)")
                      {:statement statement})))
    {:chains (statement-chains k)
     :meta (dissoc (statement-concept-maps k) :line-items)}))

;;; ---------------------------------------------------------------------------
;;; Unmapped concept logging
;;; ---------------------------------------------------------------------------

(def ^:private builtin-concepts
  "Every concept named by any built-in chain (all statements, all industries)."
  (into #{} (comp (mapcat val) (mapcat rest)) statement-chains))

;; {concept {:count n :first-seen date :example-ciks #{...}}}; :count is the
;; number of distinct companies, tracked through unmapped-seen
(def ^:private unmapped-registry (atom {}))
(def ^:private unmapped-seen (atom #{}))

(defn- record-unmapped!
  "Record us-gaap concepts present in facts-ds (for the requested form) that
   no built-in chain (any statement, any industry) and no custom chain in
   chains maps. Each company counts once per concept, however many statement
   calls see it. Feeds the concept-coverage feedback loop."
  [cik facts-ds chains form]
  (when (pos? (ds/row-count facts-ds))
    (let [known (into builtin-concepts (mapcat rest) chains)
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
        (let [seen @unmapped-seen
              fresh (remove #(contains? seen [% cik]) unmapped)]
          (when (seq fresh)
            (swap! unmapped-seen into (map #(vector % cik)) fresh)
            (swap! unmapped-registry
                   (fn [reg]
                     (reduce (fn [r c]
                               (update r c
                                       (fn [e]
                                         (-> (or e {:count 0 :first-seen today :example-ciks #{}})
                                             (update :count inc)
                                             (update :example-ciks
                                                     #(if (< (count %) 5) (conj % cik) %))))))
                             reg fresh)))))))))

(defn unmapped-concepts
  "Return the unmapped-concept registry accumulated this session:
   {concept {:count n :first-seen \"YYYY-MM-DD\" :example-ciks #{...}}}
   :count is the number of distinct companies (CIKs) whose facts carried the
   concept; :example-ciks holds up to five of them.
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
  (reset! unmapped-seen #{})
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
  (util/latest-filed (juxt :concept :unit :start :end) rows))

(defn- dedup-point-in-time
  "Point-in-time (look-ahead-safe) restatement deduplication.

   For each [concept unit start end] tuple, keeps the most recently filed
   observation among those where :filed <= as-of-date. Observations filed
   after as-of-date are excluded entirely.

   as-of-date is an ISO date string (\"YYYY-MM-DD\") or nil (falls back to
   dedup-restatements, i.e. always-latest behaviour)."
  [rows as-of-date]
  (dedup-restatements
   (if as-of-date
     (filter #(util/filed-on-or-before? as-of-date %) rows)
     rows)))

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

(defn duration-months
  "Classify a duration row's window as 3, 6, 9 or 12 months, or nil when the
   row is instant or does not resemble a whole number of fiscal quarters.
   Ranges are tolerant of 52/53-week fiscal calendars. The single source of
   truth for window classification — edgar.validation, edgar.dataset's pivot
   and the FSDS aux matching use this same definition."
  [row]
  (util/duration-months row))

(defn- line-key [row]
  (or (:line-item row) (:concept row)))

(defn- additive-unit?
  "True when values in this unit add up over time (currency amounts).
   Share counts (\"shares\"), per-share amounts (\"USD/shares\" and any other
   ratio unit) and pure numbers do not: a year's weighted-average share count
   or EPS is not the sum of its quarters', and a YTD-minus-YTD difference of
   either is meaningless. A nil unit counts as additive."
  [unit]
  (or (nil? unit)
      (not (or (str/includes? (str unit) "/")
               (contains? #{"shares" "pure"} unit)))))

(defn- quarter-values
  "Derive single-quarter values from duration rows.
   Returns {[line-item unit] {end-LocalDate {:val v :start LocalDate
                                             :end LocalDate}}}.

   Sources, later entries winning:
     1. YTD differencing — a k-month row minus the (k-3)-month row sharing the
        same fiscal-year :start yields the quarter running from the day after
        the shorter row's end to the longer row's end. 12-month 10-K rows
        participate here, yielding Q4 = FY - 9M. Skipped for non-additive
        units (see additive-unit?).
     2. Directly reported ~3-month rows (used as-is; override diffs). For
        non-additive units (shares, EPS) these are the only source."
  [rows]
  (->> rows
       (keep (fn [row]
               (when-let [m (duration-months row)]
                 (assoc row
                        ::months m
                        ::start-d (util/parse-date (:start row))
                        ::end-d (util/parse-date (:end row))))))
       (group-by (fn [row] [(line-key row) (:unit row)]))
       (reduce-kv
        (fn [acc group-key grows]
          (let [by-start-months (into {} (map (fn [r] [[(::start-d r) (::months r)] r]))
                                      grows)
                diffs (when (additive-unit? (second group-key))
                        (keep (fn [r]
                                (when (> (long (::months r)) 3)
                                  (when-let [prior (get by-start-months
                                                        [(::start-d r) (- (long (::months r)) 3)])]
                                    (when (and (:val r) (:val prior))
                                      [(::end-d r)
                                       {:val (- (:val r) (:val prior))
                                        :start (.plusDays ^LocalDate (::end-d prior) 1)
                                        :end (::end-d r)}]))))
                              grows))
                directs (keep (fn [r]
                                (when (and (= 3 (::months r)) (:val r))
                                  [(::end-d r)
                                   {:val (:val r) :start (::start-d r) :end (::end-d r)}]))
                              grows)]
            (assoc acc group-key (into {} (concat diffs directs)))))
        {})))

(defn- lookup-quarter
  "Find the quarter ({:val :start :end}) whose end date is within tol-days of
   target. Ties resolve to the closest date."
  [qmap ^LocalDate target tol-days]
  (when (and qmap target)
    (some->> qmap
             (keep (fn [[^LocalDate d q]]
                     (let [dist (Math/abs (util/days-between d target))]
                       (when (<= dist (long tol-days)) [dist q]))))
             seq
             (apply min-key first)
             second)))

(defn- row-val-q [row qmap]
  (let [m (duration-months row)]
    (cond
      (nil? m) nil
      (= 3 m) (:val row)
      :else (:val (lookup-quarter qmap (util/parse-date (:end row)) 5)))))

(defn- row-val-ltm
  "Trailing-twelve-month sum of four derived quarters; nil for non-additive
   units (summing four quarters' share counts or EPS is meaningless).
   Quarters chain by adjacency: starting from the quarter ending at the
   row's end, each predecessor is the quarter ending within 7 days of the
   day before its successor starts. The chain must span a 12-month window
   (see duration-months), which keeps uneven fiscal calendars such as
   12/12/12/16-week years chaining while rejecting gaps."
  [row qmap]
  (when (and qmap (additive-unit? (:unit row)) (duration-months row))
    (when-let [q0 (lookup-quarter qmap (util/parse-date (:end row)) 5)]
      (let [qs (reduce (fn [qs _]
                         (if-let [prev (lookup-quarter qmap
                                                       (.minusDays ^LocalDate (:start (peek qs)) 1)
                                                       7)]
                           (conj qs prev)
                           (reduced nil)))
                       [q0]
                       (range 3))]
        (when (and qs (= 12 (util/duration-months {:start (:start (peek qs))
                                                   :end (:end q0)})))
          (reduce + (map :val qs)))))))

(defn- add-quarterly-and-ltm
  "Add :duration-months, :val-q, and :val-ltm columns to a duration dataset.
   Only applies to 10-Q data; returns the dataset unchanged for other forms.
   Callers skip it for instant (balance sheet) statements.

   annual-rows are the company's 10-K fiscal-year (12-month) observations for
   the same line items; they participate in quarter derivation
   (Q4 = FY - 9M YTD) so that :val-ltm windows crossing a fiscal Q4 are
   computable."
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
   {:target "Net Income" :formula [:- "Pre-Tax Income" "Income Tax Expense"]}
   ;; Banks (operands only exist on bank chains). Gross revenue — interest
   ;; income plus noninterest income — is how Compustat constructs REVT for
   ;; banks; no single XBRL tag carries it.
   {:target "Total Gross Revenue" :formula [:+ "Interest Income" "Noninterest Income"]}])

(def balance-sheet-identities
  "Arithmetic identities for balance sheet imputation (:view :standardized).
   Working Capital is derived-only (never a reported XBRL line).
   Ordering matters twice over: identities for one target are ordered
   fallbacks (first match wins), and the equity identities precede the
   liability ones so that within a pass Total Liabilities can use the
   NCI-clean subtrahend (L&E − Total Equity). \"Stockholders Equity\" always
   means PARENT equity: filers tagging only the including-NCI equity concept
   get it labeled \"Total Equity\" by the chains, and parent equity is
   re-derived by stripping the equity-section NCI. Total Liabilities also
   strips the mezzanine (temporary equity) items — redeemable NCI and
   redeemable preferred stock sit inside Total Liabilities and Equity but
   are neither liabilities nor equity — and Non-Current Liabilities inherits
   that."
  [{:target "Total Assets" :formula [:= "Total Liabilities and Equity"]}
   {:target "Total Liabilities and Equity" :formula [:= "Total Assets"]}
   ;; SE+NCI fires first when NCI is tagged; otherwise Total Equity equals
   ;; parent equity (true whenever NCI is zero/untagged)
   {:target "Total Equity" :formula [:+ "Stockholders Equity" "Noncontrolling Interest"]}
   {:target "Total Equity" :formula [:= "Stockholders Equity"]}
   {:target "Stockholders Equity" :formula [:- "Total Equity" "Noncontrolling Interest"]}
   {:target "Stockholders Equity" :formula [:= "Total Equity"]}
   {:target "Total Liabilities" :formula [:- "Total Liabilities and Equity" "Total Equity"
                                          [:opt "Redeemable Noncontrolling Interest"]
                                          [:opt "Redeemable Preferred Stock"]]}
   {:target "Total Liabilities" :formula [:- "Total Liabilities and Equity" "Stockholders Equity"
                                          [:opt "Redeemable Noncontrolling Interest"]
                                          [:opt "Redeemable Preferred Stock"]]}
   {:target "Stockholders Equity" :formula [:- "Total Liabilities and Equity" "Total Liabilities" "Noncontrolling Interest"]}
   {:target "Stockholders Equity" :formula [:- "Total Liabilities and Equity" "Total Liabilities"]}
   {:target "Non-Current Assets" :formula [:- "Total Assets" "Current Assets"]}
   {:target "Non-Current Liabilities" :formula [:- "Total Liabilities" "Current Liabilities"]}
   {:target "Working Capital" :formula [:- "Current Assets" "Current Liabilities"]}
   ;; Compustat DLC = DD1 + NP is a sum, not a fallback (holds at 100%
   ;; empirically); when no combined DebtCurrent tag exists, build Current
   ;; Debt from the components, falling back to whichever one is tagged
   {:target "Current Debt" :formula [:+ "Current Portion of Long-Term Debt" "Short-Term Borrowings"]}
   {:target "Current Debt" :formula [:= "Current Portion of Long-Term Debt"]}
   {:target "Current Debt" :formula [:= "Short-Term Borrowings"]}])

(def cash-flow-identities
  "Arithmetic identities for cash flow imputation (:view :standardized).
   Free Cash Flow is a derived-only item (never reported directly in XBRL).
   D&A is imputed for filers that tag depreciation and intangible
   amortization separately (e.g. MSFT, IBM) instead of a combined tag."
  [{:target "Free Cash Flow" :formula [:- "Operating Cash Flow" "Capex"]}
   {:target "D&A" :formula [:+ "Depreciation" "Amortization of Intangibles"]}])

(defn- apply-identities
  "Synthesize missing line items per [unit start end] period group from
   arithmetic identities. Derived rows copy period fields from their first
   operand (edgar.reclass/derived-row: per-fact metadata such as :label,
   :accn, :frame, :fy/:fp is dropped, :filed is the latest operand filing)
   and carry :method :derived, :derived-from [operand labels] and a nil
   :concept. Iterates so chained identities resolve (capped at 3 passes).
   Formulas are evaluated by edgar.reclass/eval-formula — the same engine as
   the reclassification rules, so identities may also use [:opt x] operands
   and the :neg-sum op should the need arise."
  [rows identities]
  (->> rows
       (group-by (juxt :unit :start :end))
       (mapcat
        (fn [[_ grows]]
          (loop [pass 0
                 acc (vec grows)]
            (if (>= pass 3)
              acc
              (let [;; identities apply sequentially: the first identity to
                    ;; produce a target wins and later identities for the same
                    ;; target see it as present (ordered fallbacks emit once)
                    [acc' _ emitted?]
                    (reduce (fn [[rows by-li emitted?] {:keys [target formula]}]
                              (if (contains? by-li target)
                                [rows by-li emitted?]
                                (if-let [{:keys [val used]}
                                         (reclass/eval-formula formula by-li)]
                                  (let [row (-> (reclass/derived-row by-li used)
                                                (assoc :line-item target
                                                       :val val
                                                       :method :derived
                                                       :derived-from (vec used)))]
                                    [(conj rows row) (assoc by-li target row) true])
                                  [rows by-li emitted?])))
                            [acc
                             (into {} (map (juxt :line-item identity)) acc)
                             false]
                            identities)]
                (if emitted?
                  (recur (inc pass) acc')
                  acc'))))))))

;;; ---------------------------------------------------------------------------
;;; Statement builders
;;; ---------------------------------------------------------------------------

(defn- as-reported-statement
  "Layer 1 (:view :as-reported): observations exactly as filed for the
   statement's candidate concepts + form + duration type. No deduplication,
   no label mapping, no derived rows. as-of (ISO date string or nil) still
   applies: observations filed after it are excluded, as in every view."
  ([facts-ds chains form duration-type]
   (as-reported-statement facts-ds chains form duration-type nil))
  ([facts-ds chains form duration-type as-of]
   (let [all-concepts (set (mapcat rest chains))
         rows (-> facts-ds
                  (ds/filter-column :concept #(contains? all-concepts %))
                  (ds/filter-column :form #(= % form))
                  (ds/rows {:nil-missing? true})
                  (filter-by-duration-type duration-type))
         rows (if as-of
                (filter #(util/filed-on-or-before? as-of %) rows)
                rows)]
     ;; most recent period first, as in the other views (the facts arrive
     ;; unsorted)
     (ds/->dataset (vec (sort-by (comp str :end) #(compare %2 %1) rows))))))

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

(defn- build-statement-rows
  "Resolve chains and produce deduped, labeled rows for one form, applying
   imputation identities when given. Returns a row seq (possibly empty)."
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
    (when (seq winning-concepts)
      (cond-> (statement-rows facts-ds winning-concepts
                              concept->label concept->priority
                              form duration-type as-of)
        identities (apply-identities identities)))))

(defn- close-dates?
  "True when two ISO/LocalDate dates are within tol days (FSDS ddate is
   month-end normalized while XBRL carries exact 52/53-week dates)."
  [a b tol]
  (let [pa (util/parse-date a)
        pb (util/parse-date b)]
    (boolean (and pa pb (<= (Math/abs (util/days-between pa pb)) (long tol))))))

(defn- fsds-aux-fn
  "Return (fn [form rows] aux-rows) injecting FSDS-derived operand rows for
   the reclassification pass, per the ruleset's :fsds spec:
     - annual values of extension tags presented on the income statement,
       relabeled via :extension-labels pattern pairs (e.g. AMZN's
       FulfillmentExpense -> \"Fulfillment Expense\")
     - a marker row (:da-marker) for periods whose filing presents one of
       :da-tags on the income statement - the signal that reported expense
       lines already exclude D&A.
   Rows join period groups by copying [unit start end] from the statement's
   own rows (FSDS dates are month-end normalized, so matching is by ±10-day
   proximity). Only fires for 10-K rows with the FSDS cache enabled; every
   FSDS lookup failure degrades to no rows."
  [cik ruleset]
  (when-let [spec (:fsds ruleset)]
    (let [patterns (mapv (fn [[p l]] [(re-pattern p) l]) (:extension-labels spec))
          da-tags (vec (:da-tags spec))]
      (fn [form rows]
        (when (and (= form "10-K") (fsds/cache-enabled?))
          (->> (map (juxt :unit :start :end :filed) rows)
               distinct
               ;; annual windows only: 10-K facts also carry quarterly
               ;; windows sharing the fiscal-year end date, and the FSDS
               ;; operand values are annual (qtrs=4) — a transition-period
               ;; fiscal year must not receive 4-quarter operands.
               ;; USD only: the FSDS values are filtered to uom=USD, so they
               ;; must not be injected into a non-USD period group.
               (filter (fn [[unit start end _]]
                         (and (= "USD" unit)
                              (= 12 (duration-months {:start start :end end})))))
               (mapcat
                (fn [[unit start end filed]]
                  (when (and end filed)
                    (when-let [ctx (try (fsds/annual-filing-context cik filed)
                                        (catch Exception _ nil))]
                      (let [base {:unit unit :start start :end end
                                  :form form :filed filed :concept nil}
                            ext (->> (:is-extension-values ctx)
                                     (keep (fn [{:keys [tag value] :as v}]
                                             (when-let [label (some (fn [[re l]]
                                                                      (when (re-find re tag) l))
                                                                    patterns)]
                                               (when (and value (:end v)
                                                          (close-dates? (:end v) end 10))
                                                 (assoc base :line-item label :val value
                                                        :method :fsds :fsds-tag tag)))))
                                     ;; one row per label per period
                                     (reduce (fn [m r] (if (m (:line-item r)) m
                                                           (assoc m (:line-item r) r))) {})
                                     vals)
                            marker (when (some #(contains? (get (:placement ctx) % #{}) "IS")
                                               da-tags)
                                     [(assoc base :line-item (:da-marker spec) :val 1.0
                                             :method :fsds)])]
                        (concat ext marker))))))
               doall))))))

(defn- reclass-aux-fn
  "Return (fn [form] rows) supplying cross-statement operand rows for
   reclassification, per the ruleset's :aux spec. Currently supports
   {:aux {:cash-flow [line-items]}}: builds the standardized cash-flow rows
   for the same form/as-of (from the same facts dataset, no extra fetch) and
   keeps only the listed line items."
  [facts-ds as-of ruleset]
  (when-let [cf-items (seq (get-in ruleset [:aux :cash-flow]))]
    (let [wanted (set cf-items)]
      (fn [form]
        (->> (build-statement-rows facts-ds cash-flow-concepts form :duration
                                   as-of cash-flow-identities)
             (filter #(wanted (:line-item %))))))))

(def ^:private long-column-order
  "Leading columns of long-format statement output; any other columns (the
   remaining companyfacts fields) follow alphabetically."
  [:line-item :start :end :val :val-q :val-ltm :duration-months :unit
   :method :rule :derived-from :concept :form :filed])

(defn- order-long-columns [d]
  (let [present (set (ds/column-names d))
        lead (filterv present long-column-order)]
    (ds/select-columns d (into lead (sort-by str (remove (set lead) present))))))

(defn- normalized-statement
  "Build a normalized long-format statement dataset (:view :normalized), or
   the standardized variant when identities is non-nil (:view :standardized),
   or the reclassified variant when reclass is also non-nil (:view :compustat).

   Steps:
     1. Resolve fallback chains -> collect ALL present candidates per line item
     2. Filter facts to winning concepts + form + duration type
     3. Deduplicate restatements (dedup-point-in-time honours :as-of)
     4. Deduplicate overlapping chain candidates via chain priority
     5. Attach :line-item and :method :direct
     6. When identities given: impute missing items per period (:method :derived)
     7. When reclass given ({:ruleset rs :aux-fn f :fsds-aux-fn g}): add
        reclassified line items per period (:method :reclassified), with
        cross-statement operands supplied by aux-fn and FSDS operands by
        fsds-aux-fn
     8. For 10-Q duration statements: derive :duration-months/:val-q/:val-ltm,
        blending the company's 10-K fiscal-year (12-month) rows for Q4/LTM
        computation. Those annual rows are reclassified WITHOUT fsds-aux-fn,
        so Q4 = FY - 9M never mixes an FSDS-assisted fiscal year with a
        non-FSDS 9M YTD; the 10-K's quarterly-footnote rows are left out so
        they never displace the 10-Q's own quarters.
        For 10-K duration statements: add :duration-months (10-K facts carry
        quarterly-footnote windows alongside the fiscal-year ones); the
        reclassification of step 7 skips those quarterly windows
     9. Sort :end descending, :line-item ascending within each period; columns
        in long-column-order"
  ([facts-ds chains form duration-type as-of identities]
   (normalized-statement facts-ds chains form duration-type as-of identities nil))
  ([facts-ds chains form duration-type as-of identities reclass]
   (let [build-rows (fn [f fsds?]
                      (let [rows (build-statement-rows facts-ds chains f
                                                       duration-type as-of identities)
                            ;; 10-K quarterly-footnote windows lack most rule
                            ;; operands (no quarterly cash flow or special
                            ;; items): pass them through unreclassified
                            {footnote true annual false}
                            (if (= f "10-K")
                              (group-by (comp boolean util/quarterly-window?) rows)
                              {false rows})]
                        (if (and reclass (seq annual))
                          (concat
                           (reclass/apply-ruleset annual
                                                  (concat
                                                   (when-let [aux-fn (:aux-fn reclass)]
                                                     (aux-fn f))
                                                   (when fsds?
                                                     (when-let [ff (:fsds-aux-fn reclass)]
                                                       (ff f annual))))
                                                  (:ruleset reclass))
                           footnote)
                          rows)))
         rows (build-rows form true)
         annual-rows (when (and (= form "10-Q") (= duration-type :duration))
                       (filter #(= 12 (duration-months %)) (build-rows "10-K" false)))
         result-ds (ds/->dataset (vec rows))]
     (if (zero? (ds/row-count result-ds))
       result-ds
       (-> result-ds
           (cond-> (= duration-type :duration)
             (add-quarterly-and-ltm form annual-rows))
           (cond-> (and (= form "10-K") (= duration-type :duration))
             (ds/add-or-update-column :duration-months
                                      (mapv duration-months
                                            (ds/rows result-ds {:nil-missing? true}))))
           (ds/sort-by
            (fn [row] [(:end row) (:line-item row)])
            (fn [a b]
              (let [c (compare (first b) (first a))]
                (if (zero? c)
                  (compare (second a) (second b))
                  c))))
           (order-long-columns))))))

;;; ---------------------------------------------------------------------------
;;; Wide-format pivot
;;; ---------------------------------------------------------------------------

(defn- to-wide
  "Pivot a long-format statement dataset to wide format.
   One row per period (:end), one column per line item (:line-item; the
   :concept for :view :as-reported rows, which carry no label).

   For 10-K statements (form \"10-K\") duration rows classified as 3, 6 or 9
   months are dropped first: 10-K facts carry quarterly-footnote windows that
   share the fiscal-year end date (or end on a quarter end) and must not
   surface as annual values or as extra annual periods. Instant rows and
   irregular windows (e.g. transition periods) are kept.

   When several windows exist for the same [end line-item] (10-Q flow
   statements: a ~3-month and a YTD row), the longest window — by its
   :start/:end dates — supplies the plain value (the reported YTD figure;
   among equal windows, e.g. unduplicated :as-reported restatements, the
   latest :filed) and the derived :val-q/:val-ltm — which are period-level,
   not row-level — are taken from whichever row has them. They appear as
   \"<line-item> (Q)\" and \"<line-item> (LTM)\" columns.

   Columns: :end first, then the line items in line-order (a seq of labels
   or concepts, e.g. chain order; items it does not name follow
   alphabetically), each followed by its (Q)/(LTM) columns."
  ([ds] (to-wide ds nil nil))
  ([ds form] (to-wide ds form nil))
  ([ds form line-order]
   (if (zero? (ds/row-count ds))
     ds
     (let [has-q? (boolean (some #{:val-q} (ds/column-names ds)))
           has-ltm? (boolean (some #{:val-ltm} (ds/column-names ds)))
           reps (->> (ds/rows ds {:nil-missing? true})
                     (remove #(and (= form "10-K") (util/quarterly-window? %)))
                     (group-by (juxt :end line-key))
                     vals
                     (map (fn [grows]
                            (-> (reduce #(if (util/preferred-observation? %2 %1) %2 %1) grows)
                                (assoc :val-q (some :val-q grows)
                                       :val-ltm (some :val-ltm grows))))))
           rank (into {} (map-indexed (fn [i li] [li i])) (distinct line-order))
           lines (sort-by (fn [li] [(get rank li Long/MAX_VALUE) (str li)])
                          (distinct (map line-key reps)))
           cols (into [:end]
                      (mapcat (fn [li]
                                (cond-> [li]
                                  has-q? (conj (str li " (Q)"))
                                  has-ltm? (conj (str li " (LTM)")))))
                      lines)]
       (util/ordered-dataset
        (->> reps
             (group-by :end)
             (sort-by key #(compare %2 %1))
             (map (fn [[period rows]]
                    (reduce (fn [m r]
                              (let [li (line-key r)]
                                (cond-> (assoc m li (:val r))
                                  has-q? (assoc (str li " (Q)") (:val-q r))
                                  has-ltm? (assoc (str li " (LTM)") (:val-ltm r)))))
                            {:end period}
                            rows))))
        cols)))))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn- statement-identities [statement-key]
  (case statement-key
    :income income-statement-identities
    :balance balance-sheet-identities
    :cash-flow cash-flow-identities))

(def ^:private statement-specs
  {:income {:duration-type :duration :default-chains income-statement-concepts}
   :balance {:duration-type :instant :default-chains balance-sheet-concepts}
   :cash-flow {:duration-type :duration :default-chains cash-flow-concepts}})

(defn- build-statement
  "Build one statement for a company. facts is the company's companyfacts
   dataset when the caller already holds it (get-financials fetches it once
   for all three statements); nil fetches it here. A nil :view means
   :normalized, like an omitted one."
  [cik facts statement-key {:keys [form concepts shape as-of view industry]}]
  (let [form (or form "10-K")
        view (or view :normalized)
        {:keys [duration-type default-chains]} (statement-specs statement-key)
        resolved-industry (when (and (= statement-key :income) (nil? concepts))
                            (or industry (detect-industry cik)))
        chains (or concepts
                   (if (= statement-key :income)
                     (income-chains-for-industry resolved-industry)
                     default-chains))
        ;; the income reclassification rules were fitted on :standard chains;
        ;; bank/insurance/REIT chains lack most of their operand labels, so
        ;; :view :compustat on those industries equals :standardized rather
        ;; than emitting a Compustat-branded view the rules cannot support
        reclass? (or (not= statement-key :income)
                     (nil? resolved-industry)
                     (= :standard resolved-industry))
        facts (or facts (xbrl/get-facts-dataset cik :sort nil))
        identities (when (#{:standardized :compustat} view)
                     (statement-identities statement-key))
        ruleset (when (and (= view :compustat) reclass?)
                  (reclass/ruleset-for statement-key))
        result (case view
                 :as-reported (as-reported-statement facts chains form duration-type as-of)
                 (:normalized :standardized :compustat)
                 (normalized-statement facts chains form duration-type as-of
                                       identities
                                       (when ruleset
                                         {:ruleset ruleset
                                          :aux-fn (reclass-aux-fn facts as-of ruleset)
                                          :fsds-aux-fn (fsds-aux-fn cik ruleset)}))
                 (throw (ex-info (str "Unknown :view " view " (expected :as-reported"
                                      " :normalized :standardized :compustat)")
                                 {:view view})))]
    (record-unmapped! cik facts chains form)
    (if (= shape :wide)
      (to-wide result form (concat (map first chains)
                                   (mapcat rest chains)
                                   (map :target identities)
                                   (map :target (:rules ruleset))))
      result)))

(defn income-statement
  "Return normalized income statement as a long-format dataset.

   Options:
     :form     - \"10-K\" (default) or \"10-Q\"
     :concepts - override the fallback chains (disables industry routing)
     :shape    - :long (default) or :wide
     :view     - :normalized (default) | :as-reported | :standardized
                 | :compustat
                 :as-reported skips dedup and label mapping entirely;
                 :standardized additionally imputes missing line items from
                 arithmetic identities (rows carry :method :derived);
                 :compustat additionally adds line items reclassified to
                 approximate Compustat definitions - \"COGS (Compustat)\"
                 (D&A stripped), \"XSGA (Compustat)\" (R&D folded in),
                 \"OIADP (Compustat)\", \"OIBDP (Compustat)\", \"XOPR
                 (Compustat)\", \"DP (Compustat)\", \"Special Items
                 (Compustat)\", \"Gross Profit (Compustat)\", \"Revenue
                 (Compustat)\", and \"XRD (Compustat)\" (the reported R&D
                 line; only the variant built from extension tags such as
                 AMZN's technology-and-content expense needs the FSDS cache)
                 - alongside the originals (rows carry
                 :method :reclassified, :rule and :derived-from; see
                 edgar.reclass and resources/edgar/reclass/)
     :industry - :standard | :bank | :insurance | :reit. When omitted, auto-detected
                 from the company's SIC code (banks, insurers and REITs get
                 industry-specific chains). The :compustat reclassification
                 rules were fitted on :standard chains and only apply there;
                 for :bank/:insurance/:reit, :view :compustat equals
                 :standardized
     :as-of    - ISO date string \"YYYY-MM-DD\" (default nil).
                 When set, excludes filings where :filed > as-of-date,
                 giving point-in-time / look-ahead-safe results suitable
                 for backtesting and event studies. Applies to every view,
                 :as-reported included.

   For 10-Q queries, long-format output includes :duration-months, :val-q
   (single-quarter value) and :val-ltm (trailing twelve months) columns
   derived from actual period-date windows. Share counts and per-share
   amounts are not differenced or summed: their :val-q is the reported
   3-month value (nil when only YTD is reported) and :val-ltm is nil.

   For 10-K queries, long-format output keeps the quarterly-footnote windows
   filed with the 10-K (e.g. fourth-quarter figures sharing the fiscal-year
   end date) and carries :duration-months (3/6/9/12) to tell them apart;
   :shape :wide keeps only the annual windows.

   :view :as-reported rows are the raw companyfacts observations: they carry
   none of the derived columns (:line-item, :duration-months, :val-q,
   :val-ltm); classify their windows with duration-months."
  [ticker-or-cik & {:as opts}]
  (build-statement (company/company-cik ticker-or-cik) nil :income opts))

(defn balance-sheet
  "Return normalized balance sheet as a long-format dataset.

   Options:
     :form     - \"10-K\" (default) or \"10-Q\"
     :concepts - override balance-sheet-concepts
     :shape    - :long (default) or :wide
     :view     - :normalized (default) | :as-reported | :standardized
                 | :compustat
                 (:standardized imputes e.g. Total Liabilities, Total Equity
                 = SE + NCI, and a derived-only \"Working Capital\" item;
                 :compustat adds reclassified items approximating Compustat
                 constructions - see resources/edgar/reclass/
                 compustat-balance.edn)
     :as-of    - ISO date string \"YYYY-MM-DD\" (default nil).
                 When set, excludes filings where :filed > as-of-date,
                 giving point-in-time / look-ahead-safe results."
  [ticker-or-cik & {:as opts}]
  (build-statement (company/company-cik ticker-or-cik) nil :balance opts))

(defn cash-flow
  "Return normalized cash flow statement as a long-format dataset.

   Options:
     :form     - \"10-K\" (default) or \"10-Q\"
     :concepts - override cash-flow-concepts
     :shape    - :long (default) or :wide
     :view     - :normalized (default) | :as-reported | :standardized
                 | :compustat
                 (:standardized adds a derived \"Free Cash Flow\" line item
                 and imputes \"D&A\" for filers tagging depreciation and
                 intangible amortization separately; :compustat currently
                 equals :standardized - no cash flow reclassification rules
                 exist yet)
     :as-of    - ISO date string \"YYYY-MM-DD\" (default nil).
                 When set, excludes filings where :filed > as-of-date,
                 giving point-in-time / look-ahead-safe results.

   For 10-Q queries, long-format output includes :duration-months, :val-q
   (single-quarter value) and :val-ltm (trailing twelve months) columns
   derived from actual period-date windows.

   For 10-K queries, long-format output carries :duration-months so that
   quarterly-footnote windows filed with the 10-K can be told apart from the
   fiscal-year ones; :shape :wide keeps only the annual windows.

   :view :as-reported rows are the raw companyfacts observations without
   those derived columns."
  [ticker-or-cik & {:as opts}]
  (build-statement (company/company-cik ticker-or-cik) nil :cash-flow opts))

(defn get-financials
  "Return all three normalized statements for a company.

   Returns {:income-statement ds :balance-sheet ds :cash-flow ds}

   Options:
     :form     - \"10-K\" (default) or \"10-Q\"
     :shape    - :long (default) or :wide
     :view     - :normalized (default) | :as-reported | :standardized
                 | :compustat (reclassified line items on the income statement
                 and balance sheet; cash flow equals :standardized)
     :industry - :standard | :bank | :insurance | :reit (income statement only;
                 auto-detected from SIC when omitted)
     :as-of    - ISO date string \"YYYY-MM-DD\" (default nil).
                 All three statements use point-in-time deduplication:
                 filings where :filed > as-of-date are excluded.

   For 10-Q queries, long-format income and cash flow include :val-q and
   :val-ltm columns. Balance sheet is unaffected (instant observations).
   The company's facts are fetched once and shared by the three statements."
  [ticker-or-cik & {:keys [form shape as-of view industry]}]
  (let [cik (company/company-cik ticker-or-cik)
        ;; one companyfacts fetch serves all three statements
        facts (xbrl/get-facts-dataset cik :sort nil)
        opts {:form form :shape shape :as-of as-of :view view :industry industry}]
    {:income-statement (build-statement cik facts :income opts)
     :balance-sheet (build-statement cik facts :balance opts)
     :cash-flow (build-statement cik facts :cash-flow opts)}))
