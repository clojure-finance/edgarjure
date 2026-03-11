(ns edgar.financials
  "Financial statement extraction with normalization.

   Three output layers for each statement:
     raw-*          — all matching observations, unprocessed
     *-statement    — normalized, restatement-deduplicated, long-format
     get-financials — all three statements, optionally wide-format

   Concept fallback chains: each line item is a vector of concept names tried
   in order; the first one present in the facts data wins.

   Duration vs instant:
     Income statement + cash flow → duration observations (:frame does NOT end in \"I\")
     Balance sheet               → instant observations  (:frame ends in \"I\")"
  (:require [edgar.xbrl :as xbrl]
            [edgar.company :as company]
            [tech.v3.dataset :as ds]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; Concept fallback chains
;;; Each entry is [label & concepts-in-priority-order].
;;; label   — human-readable line item name added as a :line-item column
;;; concepts — tried in order; first one present in the facts data is used
;;;
;;; These are public vars so callers can merge in company-specific overrides:
;;;   (def my-income (assoc edgar.financials/income-statement-concepts
;;;                    :revenue [["Revenue" "MyCustomRevenueConcept"]]))
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

(defn- instant? [frame]
  (and (string? frame) (str/ends-with? frame "I")))

(defn- duration? [frame]
  (and (string? frame) (not (str/ends-with? frame "I")) (not (str/blank? frame))))

(defn- concepts-in-data
  "Return the set of concept strings present in a facts dataset."
  [facts-ds]
  (set (ds/column facts-ds :concept)))

(defn- resolve-fallback
  "Given a fallback-chain entry [label concept1 concept2 ...] and the set of
   concepts present in the data, return [label winning-concept] or nil."
  [chain available-concepts]
  (let [label (first chain)
        candidates (rest chain)]
    (when-let [winner (first (filter available-concepts candidates))]
      [label winner])))

(defn- resolve-all-chains
  "Resolve all fallback chains against the available concepts.
   Returns a seq of [label concept] pairs for concepts that were found."
  [chains available-concepts]
  (keep #(resolve-fallback % available-concepts) chains))

(defn- dedup-restatements
  "For each [concept end] pair, keep only the observation with the latest
   :filed date (i.e., the most recently filed/restated value)."
  [rows]
  (->> rows
       (group-by (juxt :concept :end))
       (mapcat (fn [[_ group]]
                 [(apply max-key #(compare (:filed %) "") group)]))))

(defn- filter-by-duration-type
  "Filter rows to the appropriate duration type for a statement.
   :instant — balance sheet (frame ends with \"I\")
   :duration — income statement / cash flow (frame does NOT end with \"I\")
   :any — no filter"
  [rows duration-type]
  (case duration-type
    :instant (filter #(instant? (:frame %)) rows)
    :duration (filter #(duration? (:frame %)) rows)
    :any rows))

(defn- add-line-item-col
  "Add a :line-item column using a concept→label lookup map."
  [ds concept->label]
  (ds/add-or-update-column
   ds :line-item
   (mapv #(get concept->label % %) (ds/column ds :concept))))

;;; ---------------------------------------------------------------------------
;;; Raw statement (unfiltered, no normalization — backward compatible)
;;; ---------------------------------------------------------------------------

(defn- raw-statement
  "Extract raw statement rows from a facts dataset.
   Filters by concept set and form type only — no deduplication, no duration filter.
   Backward-compatible with the old build-statement behaviour."
  [facts-ds concepts form]
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
     1. Resolve fallback chains → pick winning concept per line item
     2. Filter facts to winning concepts + form type
     3. Filter to the correct duration type (instant vs duration)
     4. Deduplicate restatements: keep most-recently-filed per [concept end]
     5. Add :line-item column using resolved label
     6. Sort by :end descending, then :line-item

   Returns a long-format tech.ml.dataset."
  [facts-ds chains form duration-type]
  (let [available (concepts-in-data facts-ds)
        resolved (resolve-all-chains chains available)
        winning-concepts (set (map second resolved))
        concept->label (into {} resolved)]
    (if (empty? winning-concepts)
      (ds/->dataset [])
      (let [filtered (-> facts-ds
                         (ds/filter-column :concept #(contains? winning-concepts %))
                         (ds/filter-column :form #(= % form))
                         ds/rows)
            duration-filtered (filter-by-duration-type filtered duration-type)
            deduped (dedup-restatements duration-filtered)
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

(defn- to-wide
  "Pivot a normalized long-format statement to wide format.
   Rows = :end, columns = :line-item (human label), values = :val."
  [ds]
  (if (zero? (ds/row-count ds))
    ds
    (let [deduped (ds/unique-by ds (fn [row] [(:end row) (:line-item row)]))]
      (ds/->dataset
       (->> (ds/rows deduped)
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
   Applies concept fallback chains, duration filtering, and restatement
   deduplication. Adds :line-item column with human-readable labels.

   Options:
     :form       - \"10-K\" (default) or \"10-Q\"
     :concepts   - override income-statement-concepts (vector of fallback chains)
     :shape      - :long (default) or :wide"
  [ticker-or-cik & {:keys [form concepts shape] :or {form "10-K" shape :long}}]
  (let [chains (or concepts income-statement-concepts)
        ds (xbrl/get-facts-dataset (company/company-cik ticker-or-cik))
        result (normalized-statement ds chains form :duration)]
    (if (= shape :wide) (to-wide result) result)))

(defn balance-sheet
  "Return normalized balance sheet as a long-format dataset.
   Applies concept fallback chains, instant-observation filtering, and
   restatement deduplication. Adds :line-item column with human-readable labels.

   Options:
     :form       - \"10-K\" (default) or \"10-Q\"
     :concepts   - override balance-sheet-concepts (vector of fallback chains)
     :shape      - :long (default) or :wide"
  [ticker-or-cik & {:keys [form concepts shape] :or {form "10-K" shape :long}}]
  (let [chains (or concepts balance-sheet-concepts)
        ds (xbrl/get-facts-dataset (company/company-cik ticker-or-cik))
        result (normalized-statement ds chains form :instant)]
    (if (= shape :wide) (to-wide result) result)))

(defn cash-flow
  "Return normalized cash flow statement as a long-format dataset.
   Applies concept fallback chains, duration filtering, and restatement
   deduplication. Adds :line-item column with human-readable labels.

   Options:
     :form       - \"10-K\" (default) or \"10-Q\"
     :concepts   - override cash-flow-concepts (vector of fallback chains)
     :shape      - :long (default) or :wide"
  [ticker-or-cik & {:keys [form concepts shape] :or {form "10-K" shape :long}}]
  (let [chains (or concepts cash-flow-concepts)
        ds (xbrl/get-facts-dataset (company/company-cik ticker-or-cik))
        result (normalized-statement ds chains form :duration)]
    (if (= shape :wide) (to-wide result) result)))

(defn get-financials
  "Return a map of all three normalized statements for a company.

   Returns:
     {:income-statement ds :balance-sheet ds :cash-flow ds}

   Options:
     :form  - \"10-K\" (default) or \"10-Q\"
     :shape - :long (default) or :wide"
  [ticker-or-cik & {:keys [form shape] :or {form "10-K" shape :long}}]
  (let [cik (company/company-cik ticker-or-cik)
        facts-ds (xbrl/get-facts-dataset cik)]
    {:income-statement (let [r (normalized-statement facts-ds income-statement-concepts form :duration)]
                         (if (= shape :wide) (to-wide r) r))
     :balance-sheet (let [r (normalized-statement facts-ds balance-sheet-concepts form :instant)]
                      (if (= shape :wide) (to-wide r) r))
     :cash-flow (let [r (normalized-statement facts-ds cash-flow-concepts form :duration)]
                  (if (= shape :wide) (to-wide r) r))}))
