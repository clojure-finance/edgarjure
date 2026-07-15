(ns compustat-validation
  "Validation study: edgarjure standardized statements vs Compustat (roadmap 4.1f).

   Reproduces the July 2026 study that graded edgarjure against WRDS Compustat
   extracts for 19 large filers (13 industrials/tech, 3 banks, 3 insurers).

   Headline results:
     Annual core items, FY2010-2015, vs FUNDA (2016 vintage, :as-of matched):
       overall 89.6% (1195/1334 within 1%); Total Assets 97.7%, Net Income
       96.2%, Stockholders Equity 94.7%, OCF 93.2%; banks' constructed gross
       revenue (Interest Income + Noninterest Income, a derived line item)
       95.2%. Weakest: insurers' Total Revenue 66.7% (Compustat excludes
       realized investment gains) and Pre-Tax Income 78.8% (equity-method
       income placement).
     Quarterly, 2022+ vs FUNDQ: single-quarter income values (:val-q vs
       SALEQ/NIQ) 98.6% (205/208); quarterly Total Assets 100% (104/104).
     Reclassification-sensitive items (COGS, SG&A, R&D, Operating Income,
       Gross Profit vs REVT-COGS) match only ~15-18% — this is the documented
       Compustat reclassification gap (D&A stripped out of COGS/SG&A, etc.),
       future territory for the rule engine (roadmap 4.1c).
     Extended items (annual): Investing/Financing Cash Flow 96.2%, Goodwill
       95.2%, Shares Basic/Diluted 89/88%, Cash 87.8%, EPS 79.5%, PP&E 77.6%.
       Known-definitional laggards (documented, not chased):
       - Retained Earnings 16%: Compustat RE carries treasury-stock and other
         adjustments vs the raw RetainedEarningsAccumulatedDeficit tag
       - Accounts Receivable 30%: Compustat RECT is total receivables; the
         XBRL line is trade receivables
       - Long-Term Debt 53%: Compustat DLTT includes finance leases (AMZN's
         early years are almost entirely leases)
       - Interest Expense 29%: XINT aggregates related expense components
       - EPS mismatches are stock-split vintage artifacts (Compustat keeps
         as-reported; restated XBRL is split-adjusted — align with AJEX or
         per-year :as-of) plus discontinued-ops exclusion (EPSPX/EPSFX)
       - D&A 51%: filers tagging depreciation and intangible amortization
         separately are covered by a derived identity; remaining gaps are
         Compustat DP definitional scope

   Known matching pitfalls this study codified into the harness:
     - Compustat DATADATE is calendar month-end; XBRL :end is the exact
       52/53-week date -> :date-tolerance-days 10
     - 10-K facts include quarterly-footnote rows sharing the FY end date ->
       the harness prefers the longest duration window per date
     - Ticker->CIK gives the CURRENT registrant; validate old fiscal years
       against the historical entity's CIK (Alphabet pre-2015 = Google Inc
       0001288776; similarly for other reorganized issuers)
     - Compustat values are in millions (scale by 1e6); skip nil/zero cells
     - Fiscal Q4 never exists in 10-Q data - exclude Q4 benchmark rows
       (or validate annual figures against the 10-K instead)

   Usage sketch (WRDS exports; column names are standard FUNDA/FUNDQ):
     (def annual (load-funda-tsv \"/path/to/funda-extract.tsv\"))
     (validate-annual \"AAPL\" annual :as-of \"2016-08-30\")"
  (:require [edgar.api :as e]
            [edgar.validation :as validation]
            [tech.v3.dataset :as ds]))

(def banks #{"JPM" "BAC" "WFC" "C" "GS" "MS" "USB" "PNC"})
(def insurers #{"MET" "TRV" "AIG" "PRU" "AFL" "ALL" "PGR"})

(def annual-core-items
  "Core FUNDA items -> edgarjure line items. Values must be scaled by 1e6."
  [["Revenue" :revt]            ; banks -> "Total Gross Revenue" (derived), see below
   ["Pre-Tax Income" :pi]
   ["Income Tax Expense" :txt]
   ["Net Income" :ni]
   ["Total Assets" :at]
   ["Total Liabilities" :lt]
   ["Stockholders Equity" :seq]
   ["Current Assets" :act]
   ["Current Liabilities" :lct]
   ["Operating Cash Flow" :oancf]
   ["Capex" :capx]])

(def annual-extended-items
  "Extended FUNDA items -> edgarjure line items [line-item key scale].
   EPS is per-share (scale 1.0, split-vintage caveat above); weighted shares
   are in millions. Route income items to :income, balance to :balance,
   cash flow to :cash-flow."
  [["EPS Basic" :epspx 1.0] ["EPS Diluted" :epsfx 1.0]
   ["Shares Basic" :cshpri 1e6] ["Shares Diluted" :cshfd 1e6]
   ["Interest Expense" :xint 1e6]
   ["Cash and Equivalents" :ch 1e6] ["Accounts Receivable" :rect 1e6]
   ["Inventory" :invt 1e6] ["PP&E Net" :ppent 1e6] ["Goodwill" :gdwl 1e6]
   ["Retained Earnings" :re 1e6] ["Long-Term Debt" :dltt 1e6]
   ["D&A" :dp 1e6] ["Investing Cash Flow" :ivncf 1e6]
   ["Financing Cash Flow" :fincf 1e6] ["Dividends Paid" :dv 1e6]])

(def annual-reclass-items
  "Items where Compustat reclassifies (D&A stripped from COGS/XSGA, etc.).
   Expect low match rates until a reclassification rule engine exists."
  [["Cost of Revenue" :cogs]
   ["SG&A Expense" :xsga]
   ["R&D Expense" :xrd]
   ["Operating Income" :oiadp]])

(defn- revenue-line-item [ticker]
  (cond
    (banks ticker) "Total Gross Revenue"   ; Compustat REVT for banks = interest + noninterest income
    (insurers ticker) "Total Revenue"
    :else "Revenue"))

(defn load-funda-tsv
  "Load a WRDS FUNDA tab-delimited extract. Expects at least
   tic, datadate (YYYYMMDD), plus the item columns above, pre-screened to
   indfmt=INDL, consol=C, datafmt=STD, popsrc=D, curcd=USD."
  [path]
  (->> (ds/rows (ds/->dataset path {:file-type :tsv :key-fn keyword})
                {:nil-missing? true})
       (map #(assoc % :end (let [d (str (:datadate %))]
                             (str (subs d 0 4) "-" (subs d 4 6) "-" (subs d 6 8)))))))

(defn make-benchmark
  "Build compare-to-benchmark rows from Compustat rows for one ticker."
  [ticker compustat-rows items]
  (vec (for [r compustat-rows
             :when (= ticker (:tic r))
             [li k] items
             :let [v (get r k)
                   li (if (= li "Revenue") (revenue-line-item ticker) li)]
             :when (and (number? v) (not (zero? (double v))))]
         {:line-item li :end (:end r) :val (* (double v) 1e6)})))

(defn validate-annual
  "Validate one firm's annual core items against a FUNDA extract.
   Pass :as-of as the extract's vintage date so restatements filed after the
   Compustat snapshot don't contaminate the comparison.
   statement-key routing: income items -> :income, balance -> :balance,
   cash flow -> :cash-flow; this helper runs all three and merges."
  [ticker compustat-rows & {:keys [as-of date-tolerance-days]
                            :or {date-tolerance-days 10}}]
  (let [bench-for (fn [lis] (filterv #(lis (:line-item %))
                                     (make-benchmark ticker compustat-rows annual-core-items)))
        run (fn [stmt bench]
              (when (seq bench)
                (validation/compare-to-benchmark ticker bench
                                                 :statement stmt :view :standardized :as-of as-of
                                                 :date-tolerance-days date-tolerance-days)))]
    {:income (run :income (bench-for #{"Revenue" "Total Revenue" "Total Gross Revenue"
                                       "Pre-Tax Income" "Income Tax Expense" "Net Income"}))
     :balance (run :balance (bench-for #{"Total Assets" "Total Liabilities" "Stockholders Equity"
                                         "Current Assets" "Current Liabilities"}))
     :cashflow (run :cash-flow (bench-for #{"Operating Cash Flow" "Capex"}))}))

(defn validate-quarterly
  "Validate single-quarter values (:val-q) against FUNDQ SALEQ/NIQ rows.
   quarterly-rows need :tic, :end (ISO), :saleq, :niq (millions).
   Exclude fiscal-Q4 rows first - they never appear in 10-Q data."
  [ticker quarterly-rows]
  (let [bench (vec (for [r quarterly-rows
                         :when (= ticker (:tic r))
                         [li k] [[(revenue-line-item ticker) :saleq] ["Net Income" :niq]]
                         :let [v (get r k)]
                         :when (and (number? v) (not (zero? (double v))))]
                     {:line-item li :end (:end r) :val (* (double v) 1e6)}))]
    (validation/compare-to-benchmark ticker bench
                                     :statement :income :form "10-Q" :view :standardized
                                     :value-key :val-q :date-tolerance-days 10)))

(comment
  (e/init! "Your Name your@email.com")

  (def annual (load-funda-tsv "/path/to/funda-extract.tsv"))
  (validate-annual "AAPL" annual :as-of "2016-08-30")
  ;; => {:income {:match-rate 1.0 ...} :balance {...} :cashflow {...}}

  ;; Grow chain coverage from what the sample missed:
  (e/unmapped-concepts :top 20))
