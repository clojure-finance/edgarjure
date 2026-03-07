(ns edgar.financials
  (:require [edgar.xbrl :as xbrl]
            [edgar.company :as company]
            [tech.v3.dataset :as ds]))

;;; ---------------------------------------------------------------------------
;;; Standard GAAP concept mappings for the three financial statements
;;; Keys are statement names, values are ordered vectors of concept names.
;;; We look them up from the company-facts dataset.
;;; ---------------------------------------------------------------------------

(def income-statement-concepts
  ["Revenues"
   "RevenueFromContractWithCustomerExcludingAssessedTax"
   "CostOfRevenue"
   "GrossProfit"
   "OperatingExpenses"
   "OperatingIncomeLoss"
   "NonoperatingIncomeExpense"
   "IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest"
   "IncomeTaxExpenseBenefit"
   "NetIncomeLoss"
   "EarningsPerShareBasic"
   "EarningsPerShareDiluted"
   "WeightedAverageNumberOfSharesOutstandingBasic"
   "WeightedAverageNumberOfDilutedSharesOutstanding"])

(def balance-sheet-concepts
  ["Assets"
   "AssetsCurrent"
   "CashAndCashEquivalentsAtCarryingValue"
   "ShortTermInvestments"
   "AccountsReceivableNetCurrent"
   "InventoryNet"
   "AssetsNoncurrent"
   "PropertyPlantAndEquipmentNet"
   "Goodwill"
   "IntangibleAssetsNetExcludingGoodwill"
   "Liabilities"
   "LiabilitiesCurrent"
   "LongTermDebt"
   "LiabilitiesNoncurrent"
   "StockholdersEquity"
   "RetainedEarningsAccumulatedDeficit"
   "CommonStockValue"
   "LiabilitiesAndStockholdersEquity"])

(def cash-flow-concepts
  ["NetCashProvidedByUsedInOperatingActivities"
   "DepreciationDepletionAndAmortization"
   "NetCashProvidedByUsedInInvestingActivities"
   "PaymentsToAcquirePropertyPlantAndEquipment"
   "PaymentsToAcquireBusinessesNetOfCashAcquired"
   "NetCashProvidedByUsedInFinancingActivities"
   "PaymentsForRepurchaseOfCommonStock"
   "PaymentsOfDividends"
   "ProceedsFromIssuanceOfLongTermDebt"
   "RepaymentsOfLongTermDebt"
   "CashAndCashEquivalentsPeriodIncreaseDecrease"])

;;; ---------------------------------------------------------------------------
;;; Statement builders
;;; ---------------------------------------------------------------------------

(defn- build-statement
  "Extract a financial statement from a facts dataset.
   Filters to the given concepts, annual filings, deduplicates by :end + :concept.
   Returns a wide dataset: rows are periods, columns are concepts."
  [facts-ds concepts & {:keys [form] :or {form "10-K"}}]
  (let [filtered (-> facts-ds
                     (ds/filter-column :concept #(contains? (set concepts) %))
                     (ds/filter-column :form #(= % form))
                     (ds/unique-by (fn [row] [(:end row) (:concept row)])))]
    filtered))

(defn income-statement
  "Return income statement data as a dataset for a ticker or CIK.
   Options:
     :form - \"10-K\" (default) or \"10-Q\""
  [ticker-or-cik & {:keys [form] :or {form "10-K"}}]
  (let [ds (xbrl/get-facts-dataset (company/company-cik ticker-or-cik))]
    (build-statement ds income-statement-concepts :form form)))

(defn balance-sheet
  "Return balance sheet data as a dataset for a ticker or CIK.
   Options:
     :form - \"10-K\" (default) or \"10-Q\""
  [ticker-or-cik & {:keys [form] :or {form "10-K"}}]
  (let [ds (xbrl/get-facts-dataset (company/company-cik ticker-or-cik))]
    (build-statement ds balance-sheet-concepts :form form)))

(defn cash-flow
  "Return cash flow statement data as a dataset for a ticker or CIK.
   Options:
     :form - \"10-K\" (default) or \"10-Q\""
  [ticker-or-cik & {:keys [form] :or {form "10-K"}}]
  (let [ds (xbrl/get-facts-dataset (company/company-cik ticker-or-cik))]
    (build-statement ds cash-flow-concepts :form form)))

(defn get-financials
  "Return a map of all three statements for a company.
   {:income-statement ds :balance-sheet ds :cash-flow ds}"
  [ticker-or-cik & {:keys [form] :or {form "10-K"}}]
  (let [cik (company/company-cik ticker-or-cik)
        ds (xbrl/get-facts-dataset cik)]
    {:income-statement (build-statement ds income-statement-concepts :form form)
     :balance-sheet (build-statement ds balance-sheet-concepts :form form)
     :cash-flow (build-statement ds cash-flow-concepts :form form)}))
