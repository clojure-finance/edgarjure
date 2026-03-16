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
     returned (as-reported / always-latest behaviour)."
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
    "OperatingExpenses"
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
    "LongTermDebt"
    "LongTermDebtNoncurrent"
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
    "CashAndCashEquivalentsPeriodIncreaseDecrease"
    "CashCashEquivalentsRestrictedCashAndRestrictedCashEquivalentsPeriodIncreaseDecreaseIncludingExchangeRateEffect"]])

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
        candidates (rest chain)]
    (when-let [winner (first (filter available-concepts candidates))]
      [label winner])))

(defn- resolve-all-chains [chains available-concepts]
  (keep #(resolve-fallback % available-concepts) chains))

(defn- dedup-restatements
  "Keep the most recently filed observation per [concept end] pair."
  [rows]
  (->> rows
       (group-by (juxt :concept :end))
       (mapcat (fn [[_ group]]
                 [(reduce #(if (pos? (compare (:filed %1) (:filed %2))) %1 %2) group)]))))

(defn- dedup-point-in-time
  "Point-in-time (look-ahead-safe) restatement deduplication.

   For each [concept end] pair, keeps the most recently filed observation
   among those where :filed <= as-of-date. Observations filed after
   as-of-date are excluded entirely — they represent information the market
   could not have had at that date.

   as-of-date is an ISO date string (\"YYYY-MM-DD\") or nil (falls back to
   dedup-restatements, i.e. always-latest / as-reported behaviour)."
  [rows as-of-date]
  (if (nil? as-of-date)
    (dedup-restatements rows)
    (->> rows
         (filter #(not (pos? (compare (:filed %) as-of-date))))
         (group-by (juxt :concept :end))
         (mapcat (fn [[_ group]]
                   [(reduce #(if (pos? (compare (:filed %1) (:filed %2))) %1 %2) group)])))))

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
     1. Resolve fallback chains -> pick winning concept per line item
     2. Filter facts to winning concepts + form type
     3. Filter to the correct duration type (instant vs duration)
     4. Deduplicate via dedup-point-in-time:
          as-of nil  => latest restated value (as-reported)
          as-of date => point-in-time; excludes filings filed after as-of
     5. Add :line-item column
     6. Sort :end descending"
  [facts-ds chains form duration-type as-of]
  (let [available (concepts-in-data facts-ds)
        resolved (resolve-all-chains chains available)
        winning-concepts (set (map second resolved))
        concept->label (into {} resolved)]
    (if (empty? winning-concepts)
      (ds/->dataset [])
      (let [filtered (-> facts-ds
                         (ds/filter-column :concept #(contains? winning-concepts %))
                         (ds/filter-column :form #(= % form))
                         (ds/rows {:nil-missing? true}))
            duration-filtered (filter-by-duration-type filtered duration-type)
            deduped (dedup-point-in-time duration-filtered as-of)
            result-ds (ds/->dataset (vec deduped))]
        (if (zero? (ds/row-count result-ds))
          result-ds
          (-> result-ds
              (add-line-item-col concept->label)
              (ds/sort-by (fn [row] [(:end row) (:line-item row)])
                          #(compare %2 %1))))))))

;;; ---------------------------------------------------------------------------
;;; Wide-format pivot
;;; ---------------------------------------------------------------------------

(defn- to-wide [ds]
  (if (zero? (ds/row-count ds))
    ds
    (let [deduped (ds/unique-by ds (fn [row] [(:end row) (:line-item row)]))]
      (ds/->dataset
       (->> (ds/rows deduped {:nil-missing? true})
            (group-by :end)
            (sort-by key #(compare %2 %1))
            (map (fn [[period rows]]
                   (into {:end period}
                         (map (fn [r] [(:line-item r) (:val r)]) rows)))))))))

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
                 for backtesting and event studies."
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
                 giving point-in-time / look-ahead-safe results."
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
               filings where :filed > as-of-date are excluded."
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
