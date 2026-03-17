(ns edgar.financials
  "Financial statement extraction with normalization.

   Three output layers for each statement:
     raw-*          - all matching observations, unprocessed
     *-statement    - normalized, restatement-deduplicated, long-format
     get-financials - all three statements, optionally wide-format

   Concept fallback chains: each line item is a vector of concept names tried
   in order; the first one present in the facts data wins.

   Duration vs instant:
     Income statement + cash flow -> duration observations (row has :start date)
     Balance sheet               -> instant observations  (row has no :start date)

   Point-in-time / look-ahead-safe mode:
     Pass :as-of \"YYYY-MM-DD\" to any public function to restrict to filings
     where :filed <= as-of-date.  Without :as-of the latest restated value is
     returned (as-reported / always-latest behaviour).

   Quarterly and LTM derivation (10-Q only, flow variables only):
     :val-q   - single-quarter value derived by subtracting prior YTD from
                current YTD.  Q1 = reported value; Q2 = H1 - Q1; etc.
     :val-ltm - last-twelve-months: sum of four consecutive :val-q values.
                nil when any of the four quarters is missing."
  (:require [edgar.xbrl :as xbrl]
            [edgar.company :as company]
            [tech.v3.dataset :as ds]))

;;; ---------------------------------------------------------------------------
;;; Concept fallback chains
;;; ---------------------------------------------------------------------------

(def income-statement-concepts
  [["Revenue"
    "RevenueFromContractWithCustomerExcludingAssessedTax"
    "Revenues"
    "SalesRevenueNet"
    "SalesRevenueGoodsNet"
    "RevenueFromContractWithCustomerIncludingAssessedTax"]
   ["Cost of Revenue"
    "CostOfRevenue"
    "CostOfGoodsSold"
    "CostOfGoodsAndServicesSold"]
   ["Gross Profit"
    "GrossProfit"]
   ["Operating Expenses"
    "OperatingExpenses"]
   ["Total Costs and Expenses"
    "CostsAndExpenses"]
   ["R&D Expense"
    "ResearchAndDevelopmentExpense"]
   ["SG&A Expense"
    "SellingGeneralAndAdministrativeExpense"]
   ["Operating Income"
    "OperatingIncomeLoss"]
   ["Non-Operating Income"
    "NonoperatingIncomeExpense"]
   ["Pre-Tax Income"
    "IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest"
    "IncomeLossFromContinuingOperationsBeforeIncomeTaxesMinorityInterestAndIncomeLossFromEquityMethodInvestments"]
   ["Income Tax Expense"
    "IncomeTaxExpenseBenefit"]
   ["Net Income"
    "NetIncomeLoss"
    "ProfitLoss"
    "NetIncomeLossAvailableToCommonStockholdersBasic"]
   ["EPS Basic"
    "EarningsPerShareBasic"]
   ["EPS Diluted"
    "EarningsPerShareDiluted"]
   ["Shares Basic"
    "WeightedAverageNumberOfSharesOutstandingBasic"]
   ["Shares Diluted"
    "WeightedAverageNumberOfDilutedSharesOutstanding"]])

(def balance-sheet-concepts
  [["Total Assets"
    "Assets"]
   ["Current Assets"
    "AssetsCurrent"]
   ["Cash and Equivalents"
    "CashAndCashEquivalentsAtCarryingValue"
    "CashCashEquivalentsAndShortTermInvestments"]
   ["Short-Term Investments"
    "ShortTermInvestments"
    "AvailableForSaleSecuritiesCurrent"]
   ["Accounts Receivable"
    "AccountsReceivableNetCurrent"
    "ReceivablesNetCurrent"]
   ["Inventory"
    "InventoryNet"]
   ["Non-Current Assets"
    "AssetsNoncurrent"]
   ["PP&E Net"
    "PropertyPlantAndEquipmentNet"]
   ["Goodwill"
    "Goodwill"]
   ["Intangibles"
    "IntangibleAssetsNetExcludingGoodwill"
    "FiniteLivedIntangibleAssetsNet"]
   ["Total Liabilities"
    "Liabilities"]
   ["Current Liabilities"
    "LiabilitiesCurrent"]
   ["Long-Term Debt"
    "LongTermDebtNoncurrent"
    "LongTermDebt"
    "LongTermNotesPayable"]
   ["Non-Current Liabilities"
    "LiabilitiesNoncurrent"]
   ["Stockholders Equity"
    "StockholdersEquity"
    "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest"]
   ["Retained Earnings"
    "RetainedEarningsAccumulatedDeficit"]
   ["Common Stock"
    "CommonStockValue"]
   ["Total Liabilities and Equity"
    "LiabilitiesAndStockholdersEquity"
    "LiabilitiesAndStockholdersEquityIncludingPortionAttributableToNoncontrollingInterest"]])

(def cash-flow-concepts
  [["Operating Cash Flow"
    "NetCashProvidedByUsedInOperatingActivities"]
   ["D&A"
    "DepreciationDepletionAndAmortization"
    "DepreciationAndAmortization"]
   ["Investing Cash Flow"
    "NetCashProvidedByUsedInInvestingActivities"]
   ["Capex"
    "PaymentsToAcquirePropertyPlantAndEquipment"
    "PaymentsForCapitalImprovements"]
   ["Acquisitions"
    "PaymentsToAcquireBusinessesNetOfCashAcquired"
    "PaymentsToAcquireBusinessesGross"]
   ["Financing Cash Flow"
    "NetCashProvidedByUsedInFinancingActivities"]
   ["Share Buybacks"
    "PaymentsForRepurchaseOfCommonStock"]
   ["Dividends Paid"
    "PaymentsOfDividends"
    "PaymentsOfDividendsCommonStock"]
   ["LT Debt Issued"
    "ProceedsFromIssuanceOfLongTermDebt"]
   ["LT Debt Repaid"
    "RepaymentsOfLongTermDebt"]
   ["Net Change in Cash"
    "CashCashEquivalentsRestrictedCashAndRestrictedCashEquivalentsPeriodIncreaseDecreaseIncludingExchangeRateEffect"
    "CashAndCashEquivalentsPeriodIncreaseDecrease"]])

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

   Using :start in the key preserves distinct duration windows (e.g. 3-month
   vs 9-month observations sharing the same :end date).

   as-of-date is an ISO date string (\"YYYY-MM-DD\") or nil (falls back to
   dedup-restatements, i.e. always-latest / as-reported behaviour)."
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
   same chain for the same period (e.g. both CashAndCashEquivalentsAtCarryingValue
   and CashCashEquivalentsAndShortTermInvestments). The chain ordering defines
   preference: index 0 = most preferred."
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

(defn- add-line-item-col [ds concept->label]
  (ds/add-or-update-column
   ds :line-item
   (mapv #(get concept->label % %) (ds/column ds :concept))))

;;; ---------------------------------------------------------------------------
;;; Quarterly and LTM derivation
;;; ---------------------------------------------------------------------------

(def ^:private fp-order
  {"Q1" 0 "Q2" 1 "Q3" 2 "Q4" 3})

(defn- prior-quarter
  "Return [fy fp] for the quarter preceding the given [fy fp].
   E.g. [2024 \"Q1\"] -> [2023 \"Q4\"], [2024 \"Q3\"] -> [2024 \"Q2\"]."
  [fy fp]
  (case fp
    "Q1" [(dec fy) "Q4"]
    "Q2" [fy "Q1"]
    "Q3" [fy "Q2"]
    "Q4" [fy "Q3"]
    nil))

(defn- quarter-seq
  "Return a lazy seq of [fy fp] pairs going backwards from (but not including)
   the given quarter. E.g. (take 3 (quarter-seq 2024 \"Q3\"))
   => ([2024 \"Q2\"] [2024 \"Q1\"] [2023 \"Q4\"])"
  [fy fp]
  (let [prev (prior-quarter fy fp)]
    (when prev
      (lazy-seq (cons prev (quarter-seq (first prev) (second prev)))))))

(defn- build-ytd-lookup
  "Build a lookup map {[line-item unit fy fp] -> val} from rows.
   Only includes rows with valid :fy and :fp (Q1-Q4)."
  [rows]
  (into {}
        (keep (fn [row]
                (when-let [fp (get fp-order (:fp row))]
                  (when (:fy row)
                    [[(or (:line-item row) (:concept row))
                      (:unit row)
                      (long (:fy row))
                      (:fp row)]
                     (:val row)]))))
        rows))

(defn- compute-val-q
  "Compute single-quarter value from YTD data.
   Q1 -> reported value (already single quarter).
   Q2 -> H1 YTD - Q1 YTD.  Q3 -> 9M YTD - H1 YTD.  Q4 -> FY YTD - 9M YTD.
   Returns nil if prior YTD is not available."
  [row ytd-lookup]
  (let [fp (:fp row)
        fy (when (:fy row) (long (:fy row)))
        line-item (or (:line-item row) (:concept row))
        unit (:unit row)
        val (:val row)]
    (cond
      (nil? fy) nil
      (nil? (get fp-order fp)) nil
      (= fp "Q1") val
      :else
      (let [[prior-fy prior-fp] (prior-quarter fy fp)
            prior-val (get ytd-lookup [line-item unit prior-fy prior-fp])]
        (when (and val prior-val)
          (- val prior-val))))))

(defn- compute-val-ltm
  "Compute LTM (last twelve months) as sum of four consecutive quarterly values.
   Returns nil if any of the four quarters is missing."
  [row val-q-lookup]
  (let [fp (:fp row)
        fy (when (:fy row) (long (:fy row)))
        line-item (or (:line-item row) (:concept row))
        unit (:unit row)]
    (when (and fy (get fp-order fp))
      (let [current-q (get val-q-lookup [line-item unit fy fp])
            prior-qs (take 3 (quarter-seq fy fp))
            prior-vals (map (fn [[qfy qfp]]
                              (get val-q-lookup [line-item unit qfy qfp]))
                            prior-qs)]
        (when (and current-q
                   (= 3 (count prior-qs))
                   (every? some? prior-vals))
          (+ current-q (apply + prior-vals)))))))

(defn- add-quarterly-and-ltm
  "Add :duration-months, :val-q, and :val-ltm columns to a duration dataset.
   Only applies to 10-Q data; returns the dataset unchanged for other forms.

   :val-q is the single-quarter value derived from YTD subtraction.
   :val-ltm is the trailing twelve months (sum of 4 consecutive :val-q)."
  [ds form]
  (if (not= form "10-Q")
    ds
    (if (zero? (ds/row-count ds))
      ds
      (let [rows (vec (ds/rows ds {:nil-missing? true}))
            ytd-lookup (build-ytd-lookup rows)
            rows-with-q (mapv (fn [row]
                                (assoc row :val-q (compute-val-q row ytd-lookup)))
                              rows)
            val-q-lookup (into {}
                               (keep (fn [row]
                                       (when (and (:val-q row) (:fy row)
                                                  (get fp-order (:fp row)))
                                         [[(or (:line-item row) (:concept row))
                                           (:unit row)
                                           (long (:fy row))
                                           (:fp row)]
                                          (:val-q row)])))
                               rows-with-q)
            rows-with-ltm (mapv (fn [row]
                                  (assoc row :val-ltm
                                         (compute-val-ltm row val-q-lookup)))
                                rows-with-q)]
        (ds/->dataset rows-with-ltm)))))

;;; ---------------------------------------------------------------------------
;;; Raw statement (no normalization — backward compatible)
;;; ---------------------------------------------------------------------------

(defn- raw-statement [facts-ds concepts form]
  (let [all-concepts (set (mapcat rest concepts))]
    (-> facts-ds
        (ds/filter-column :concept #(contains? all-concepts %))
        (ds/filter-column :form #(= % form)))))

;;; ---------------------------------------------------------------------------
;;; Normalized statement builder
;;; ---------------------------------------------------------------------------

(defn- normalized-statement
  "Build a normalized long-format statement dataset.

   Steps:
     1. Resolve fallback chains -> collect ALL present candidates per line item
     2. Build concept->label and concept->priority maps
     3. Filter facts to all winning concepts + form type
     4. Filter to the correct duration type (instant vs duration)
     5. Deduplicate restatements via dedup-point-in-time
     6. Deduplicate overlapping chain candidates via dedup-by-priority:
          When multiple concepts from the same chain co-exist for a period,
          keep only the highest-priority (lowest chain index) concept
     7. Add :line-item column
     8. For 10-Q duration statements: add :val-q and :val-ltm columns
     9. Sort :end descending, :line-item ascending within each period

   Multi-candidate handling:
     All present candidates are fetched so that historical periods using
     older XBRL tags are not dropped. The dedup-by-priority step then
     resolves any same-period overlaps using the chain ordering."
  [facts-ds chains form duration-type as-of]
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
      (let [filtered (-> facts-ds
                         (ds/filter-column :concept #(contains? winning-concepts %))
                         (ds/filter-column :form #(= % form))
                         (ds/rows {:nil-missing? true}))
            duration-filtered (filter-by-duration-type filtered duration-type)
            deduped (dedup-point-in-time duration-filtered as-of)
            priority-deduped (dedup-by-priority deduped concept->label concept->priority)
            result-ds (ds/->dataset (vec priority-deduped))]
        (if (zero? (ds/row-count result-ds))
          result-ds
          (-> result-ds
              (add-line-item-col concept->label)
              (add-quarterly-and-ltm form)
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

   For 10-Q flow statements the long format includes :val-q and :val-ltm
   columns.  These are preserved in wide format as \"<line-item> (Q)\" and
   \"<line-item> (LTM)\" columns respectively alongside the plain YTD value."
  [ds]
  (if (zero? (ds/row-count ds))
    ds
    (let [has-q? (boolean (some #{:val-q} (ds/column-names ds)))
          has-ltm? (boolean (some #{:val-ltm} (ds/column-names ds)))
          deduped (ds/unique-by ds (fn [row] [(:end row) (:line-item row)]))]
      (ds/->dataset
       (->> (ds/rows deduped {:nil-missing? true})
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

(defn income-statement
  "Return normalized income statement as a long-format dataset.

   Options:
     :form     - \"10-K\" (default) or \"10-Q\"
     :concepts - override income-statement-concepts
     :shape    - :long (default) or :wide
     :as-of    - ISO date string \"YYYY-MM-DD\" (default nil).
                 When set, excludes filings where :filed > as-of-date,
                 giving point-in-time / look-ahead-safe results suitable
                 for backtesting and event studies.

   For 10-Q queries, long-format output includes :val-q (single-quarter value)
   and :val-ltm (trailing twelve months) columns derived from YTD subtraction."
  [ticker-or-cik & {:keys [form concepts shape as-of]
                    :or {form "10-K" shape :long}}]
  (let [chains (or concepts income-statement-concepts)
        ds (xbrl/get-facts-dataset (company/company-cik ticker-or-cik))
        result (normalized-statement ds chains form :duration as-of)]
    (if (= shape :wide) (to-wide result) result)))

(defn balance-sheet
  "Return normalized balance sheet as a long-format dataset.

   Options:
     :form     - \"10-K\" (default) or \"10-Q\"
     :concepts - override balance-sheet-concepts
     :shape    - :long (default) or :wide
     :as-of    - ISO date string \"YYYY-MM-DD\" (default nil).
                 When set, excludes filings where :filed > as-of-date,
                 giving point-in-time / look-ahead-safe results."
  [ticker-or-cik & {:keys [form concepts shape as-of]
                    :or {form "10-K" shape :long}}]
  (let [chains (or concepts balance-sheet-concepts)
        ds (xbrl/get-facts-dataset (company/company-cik ticker-or-cik))
        result (normalized-statement ds chains form :instant as-of)]
    (if (= shape :wide) (to-wide result) result)))

(defn cash-flow
  "Return normalized cash flow statement as a long-format dataset.

   Options:
     :form     - \"10-K\" (default) or \"10-Q\"
     :concepts - override cash-flow-concepts
     :shape    - :long (default) or :wide
     :as-of    - ISO date string \"YYYY-MM-DD\" (default nil).
                 When set, excludes filings where :filed > as-of-date,
                 giving point-in-time / look-ahead-safe results.

   For 10-Q queries, long-format output includes :val-q (single-quarter value)
   and :val-ltm (trailing twelve months) columns derived from YTD subtraction."
  [ticker-or-cik & {:keys [form concepts shape as-of]
                    :or {form "10-K" shape :long}}]
  (let [chains (or concepts cash-flow-concepts)
        ds (xbrl/get-facts-dataset (company/company-cik ticker-or-cik))
        result (normalized-statement ds chains form :duration as-of)]
    (if (= shape :wide) (to-wide result) result)))

(defn get-financials
  "Return all three normalized statements for a company.

   Returns {:income-statement ds :balance-sheet ds :cash-flow ds}

   Options:
     :form  - \"10-K\" (default) or \"10-Q\"
     :shape - :long (default) or :wide
     :as-of - ISO date string \"YYYY-MM-DD\" (default nil).
               All three statements use point-in-time deduplication:
               filings where :filed > as-of-date are excluded.

   For 10-Q queries, long-format income and cash flow include :val-q and
   :val-ltm columns. Balance sheet is unaffected (instant observations)."
  [ticker-or-cik & {:keys [form shape as-of]
                    :or {form "10-K" shape :long}}]
  (let [cik (company/company-cik ticker-or-cik)
        facts-ds (xbrl/get-facts-dataset cik)]
    {:income-statement (let [r (normalized-statement facts-ds income-statement-concepts form :duration as-of)]
                         (if (= shape :wide) (to-wide r) r))
     :balance-sheet (let [r (normalized-statement facts-ds balance-sheet-concepts form :instant as-of)]
                      (if (= shape :wide) (to-wide r) r))
     :cash-flow (let [r (normalized-statement facts-ds cash-flow-concepts form :duration as-of)]
                  (if (= shape :wide) (to-wide r) r))}))
