(ns compustat-validation
  "Validation study: edgarjure standardized statements vs Compustat.

   Reproduces the July 2026 study that graded edgarjure against licensed Compustat
   extracts for 19 large filers (13 industrials/tech, 3 banks, 3 insurers).

   Headline results:
     Annual core items, FY2010-2015, vs FUNDA (2016 vintage, :as-of matched):
       overall 89.4% (1193/1334 within 1%; two rows moved when the incl-NCI equity tag was relabeled Total Equity); Total Assets 97.7%, Net Income
       96.2%, Stockholders Equity 94.7%, OCF 93.2%; banks' constructed gross
       revenue (Interest Income + Noninterest Income, a derived line item)
       95.2%. Weakest: insurers' Total Revenue 66.7% (Compustat excludes
       realized investment gains) and Pre-Tax Income 78.8% (equity-method
       income placement).
     Quarterly, 2022+ vs FUNDQ: single-quarter income values (:val-q vs
       SALEQ/NIQ) 98.6% (205/208); quarterly Total Assets 100% (104/104).
     Reclassification-sensitive items (COGS, SG&A, R&D, Operating Income,
       Gross Profit vs REVT-COGS) match only ~15-18% as reported — the
       Compustat reclassification gap (D&A stripped out of COGS, R&D folded
       into SG&A, special items added back to operating income, etc.) that
       the :view :compustat rules address. Rates for the reclassified
       items are listed once, by context, under \"Reclassified-item match
       rates\" below.
     Extended items (annual): Investing/Financing Cash Flow 96.2%, Goodwill
       95.2%, Shares Basic/Diluted 89/88%, Cash 87.8%, EPS 79.5%, PP&E 77.6%.
       Known-definitional laggards (the ORIGINAL standardized-view study,
       kept as the baseline record; the :view :compustat balance-sheet
       rules now address them — see the balance-sheet rates below):
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
     Balance sheet / cash flow items (third pass):
       clean: Total Liabilities and Equity 98.5%, Total Equity 94.7%
       (derived: SE + NCI, falling back to SE), Working Capital 90%
       (derived-only), Net Change in Cash 89.4%, Income Taxes Payable 88.6%,
       Acquisitions 84.8%, Share Buybacks 78.1%, LT Debt Issued 75.2%,
       Deferred Taxes (CF) 74.5%, LT Debt Repaid 70.7%.
       Known-definitional laggards:
       - Noncontrolling Interest 0%: Compustat MIB includes redeemable/
         mezzanine NCI and subsidiary preferred; us-gaap:MinorityInterest is
         the equity-section NCI only (AIG diverges by 10-30x in both
         directions)
       - Current Debt 44%: Compustat DLC = notes payable + commercial paper
         + current LTD; us-gaap:DebtCurrent is the closest single tag but
         many filers tag only components
       - Short-Term Investments ~40%: Compustat IVST bucket vs heterogeneous
         tags (MarketableSecuritiesCurrent may include equities)
       - Accounts Payable 63%: banks have no AP line; some filers tag only
         the combined payables-and-accruals value
       - Preferred Stock 30%: Compustat PSTK is a broader construction

   Reclassified-item match rates (:view :compustat, within 1%), by context:
     Income, fitting sample (18 large industrials, FY2010-2015, 2016-vintage
       FUNDA, 2026-07): COGS 57%, XSGA 45%, XOPR 45%, OIADP 38%, OIBDP 29%,
       DP 74%, Gross Profit (Compustat) vs REVT-COGS 58%. The firm list of
       this sample was not preserved, so these figures are a historical
       record that cannot be re-derived.
     Income, re-measured baseline (2026-08-01) on a NAMED 13-industrial
       subsample (AAPL MSFT IBM INTC KO PEP PG WMT CAT CVX HON UPS CSCO),
       FY2010-2015, 2016 vintage, :as-of 2016-08-30, no FSDS: COGS 41%,
       XSGA 57%, XOPR 47%, OIADP 26%, OIBDP 18%, DP 77%, GP 42%. The gap
       to the fitting-sample figures is sample composition plus rule
       evolution (:dp-components, GLP/RDIP), not a regression.
     Income, out-of-sample (2026-07-28; FY2016+ vs a current-vintage FUNDA
       extract, ~190 firm-years the rules were never fitted on): COGS 54%
       (as reported 1%), XSGA 43% (16%), OIADP 34% (19%), OIBDP 33%,
       DP 76% — including the :dp-components rule (Depreciation +
       intangible amortization beats the combined cash-flow D&A tag, whose
       scope can exceed Compustat DP: AMZN's includes content
       amortization). Near-perfect on clean filers (MSFT, PEP, WMT, KO
       90-100%); misses concentrate in known problem cases (CVX, GE, HON,
       IBM). AMZN's XSGA/XRD need its Fulfillment and Technology & Content
       extension tags, which only the FSDS cache supplies.
     Balance sheet, 2026-07-31 (extended 21-firm sample, FY2009-2025,
       rules as of that date): RE 96%, DLTT 76%, DLC 63%, RECT 61%,
       MIB 81%. On the same sample the 2026-08-01 equity re-derivation
       lifted SEQ/CEQ/TEQ to 97% and standardized Total Liabilities from
       79% to 96%.
     Balance sheet, re-measured 2026-08-01 on the 13 industrials above,
       FY2009-2025, current vintage, current rules: SEQ/CEQ/TEQ 97%,
       RE 96%, MIBN 96%, DLTT 86%, MIB 83%, RECT 69%, CHE 68%, DLC 58%,
       PSTK 34% (73% excluding missing-value years).
     Current rules, re-measured 2026-10-15 on the 13 industrials above,
       FY2009-2025, current vintage, FSDS enabled (221 firm-years):
       income XRD 84%, DP 74%, XSGA 50%, COGS 45%, XOPR 42%, Gross Profit
       40%, OIADP 28%, OIBDP 23%, Special Items 10%; balance unchanged from
       the 2026-08-01 figures above (MIBT 94%). AMZN: COGS 17/17, XSGA
       16/17, XRD 16/17. MO (excise, out of sample): Gross Profit 53%,
       Revenue (Compustat) 59%. Against the previous rules on the same
       extract only 18 firm-years moved, all from miss to match (OIADP
       27.6 -> 28.1%, OIBDP 22.2 -> 22.6%, AMZN XSGA 13 -> 16 of 17, MO
       Gross Profit 0 -> 9 of 17). The 2026-07/08 income figures for the
       13 industrials (XSGA 58%, COGS 52%) do not reproduce on this extract
       under either rule set, so compare rule versions on one extract.

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

   Usage sketch (column names are standard FUNDA/FUNDQ):
     (def annual (load-funda-tsv \"/path/to/funda-extract.tsv\"))
     (validate-annual \"AAPL\" annual :as-of \"2016-08-30\")"
  (:require [edgar.api :as e]
            [edgar.validation :as validation]
            [tech.v3.dataset :as ds]))

;; Tickers whose benchmark revenue is the derived bank "Total Gross Revenue".
;; Only filers that edgarjure routes to the bank chains (SIC 6000-6199, 6712)
;; belong here: broker-dealers such as GS and MS (SIC 6211) get the standard
;; chains, which have no "Total Gross Revenue" line.
(def banks #{"JPM" "BAC" "WFC" "C" "USB" "PNC"})
(def insurers #{"MET" "TRV" "AIG" "PRU" "AFL" "ALL" "PGR"})

(def annual-core-items
  "Core FUNDA items -> edgarjure line items. Values must be scaled by 1e6."
  [["Revenue" :revt] ; banks -> "Total Gross Revenue" (derived), see below
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
  "Extended FUNDA items -> edgarjure line items [line-item key scale],
   grouped by the statement that carries them (compare-to-benchmark's
   :statement option; see validate-items). EPS is per-share (scale 1.0,
   split-vintage caveat above); weighted shares are in millions."
  {:income [["EPS Basic" :epspx 1.0] ["EPS Diluted" :epsfx 1.0]
            ["Shares Basic" :cshpri 1e6] ["Shares Diluted" :cshfd 1e6]
            ["Interest Expense" :xint 1e6]]
   :balance [["Cash and Equivalents" :ch 1e6] ["Accounts Receivable" :rect 1e6]
             ["Inventory" :invt 1e6] ["PP&E Net" :ppent 1e6] ["Goodwill" :gdwl 1e6]
             ["Retained Earnings" :re 1e6] ["Long-Term Debt" :dltt 1e6]]
   :cash-flow [["D&A" :dp 1e6] ["Investing Cash Flow" :ivncf 1e6]
               ["Financing Cash Flow" :fincf 1e6] ["Dividends Paid" :dv 1e6]]})

(def annual-balance-cashflow-items
  "Third-pass FUNDA items -> edgarjure line items [line-item key scale],
   grouped by statement (see validate-items)."
  {:balance [["Short-Term Investments" :ivst 1e6] ["Accounts Payable" :ap 1e6]
             ["Current Debt" :dlc 1e6] ["Income Taxes Payable" :txp 1e6]
             ["Preferred Stock" :pstk 1e6] ["Noncontrolling Interest" :mib 1e6]
             ["Total Equity" :teq 1e6] ["Working Capital" :wcap 1e6]
             ["Total Liabilities and Equity" :lse 1e6]]
   :cash-flow [["Net Change in Cash" :chech 1e6] ["LT Debt Issued" :dltis 1e6]
               ["LT Debt Repaid" :dltr 1e6] ["Share Buybacks" :prstkc 1e6]
               ["Acquisitions" :aqc 1e6] ["Stock Issued" :sstk 1e6]
               ["Deferred Taxes (CF)" :txdc 1e6]]})

(def annual-reclass-items
  "Income items where Compustat reclassifies (D&A stripped from COGS, R&D
   folded into XSGA, special items added back to OIADP, etc.), compared as
   reported (:view :standardized); these match only ~15-18%. The
   reclassified counterparts are annual-compustat-items."
  [["Cost of Revenue" :cogs]
   ["SG&A Expense" :xsga]
   ["R&D Expense" :xrd]
   ["Operating Income" :oiadp]])

(def annual-compustat-items
  "Reclassified income items (:view :compustat) -> FUNDA keys. Match rates
   by sample are in the namespace docstring (Reclassified-item match
   rates). Residual gaps are footnote-level allocations (partial D&A
   splits, R&D embedded in COGS) and special items invisible to
   companyfacts."
  [["COGS (Compustat)" :cogs]
   ["XSGA (Compustat)" :xsga]
   ["XOPR (Compustat)" :xopr]
   ["OIADP (Compustat)" :oiadp]
   ["OIBDP (Compustat)" :oibdp]
   ["DP (Compustat)" :dp]])

(defn- revenue-line-item [ticker]
  (cond
    (banks ticker) "Total Gross Revenue" ; Compustat REVT for banks = interest + noninterest income
    (insurers ticker) "Total Revenue"
    :else "Revenue"))

(defn load-funda-tsv
  "Load a FUNDA tab-delimited extract. Expects at least
   tic, datadate (YYYYMMDD), plus the item columns above, pre-screened to
   indfmt=INDL, consol=C, datafmt=STD, popsrc=D, curcd=USD."
  [path]
  (->> (ds/rows (ds/->dataset path {:file-type :tsv :key-fn keyword})
                {:nil-missing? true})
       (map #(assoc % :end (let [d (str (:datadate %))]
                             (str (subs d 0 4) "-" (subs d 4 6) "-" (subs d 6 8)))))))

(defn make-benchmark
  "Build compare-to-benchmark rows from Compustat rows for one ticker.
   Items are [line-item funda-key] pairs (values scaled by 1e6) or
   [line-item funda-key scale] triples (annual-extended-items carries
   per-item scales: EPS is per-share, weighted shares are in millions)."
  [ticker compustat-rows items]
  (vec (for [r compustat-rows
             :when (= ticker (:tic r))
             [li k scale] items
             :let [v (get r k)
                   li (if (= li "Revenue") (revenue-line-item ticker) li)]
             :when (and (number? v) (not (zero? (double v))))]
         {:line-item li :end (:end r) :val (* (double v) (or scale 1e6))})))

(defn validate-annual
  "Validate one firm's annual core items against a FUNDA extract.
   Pass :as-of as the extract's vintage date so restatements filed after the
   Compustat snapshot don't contaminate the comparison.
   statement-key routing: income items -> :income, balance -> :balance,
   cash flow -> :cash-flow; this helper runs all three and returns
   {:income result :balance result :cash-flow result} (the same keys as
   validate-items)."
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
     :cash-flow (run :cash-flow (bench-for #{"Operating Cash Flow" "Capex"}))}))

(defn validate-items
  "Validate one firm's items grouped by statement ({statement [item ...]},
   e.g. annual-extended-items) against a FUNDA extract: each group runs
   through compare-to-benchmark with its own :statement, so income, balance
   and cash-flow items are looked up in the right statement.
   Returns {statement result}. Same conventions as validate-annual."
  [ticker compustat-rows items-by-statement
   & {:keys [as-of view date-tolerance-days]
      :or {view :standardized date-tolerance-days 10}}]
  (into {}
        (for [[stmt items] items-by-statement
              :let [bench (make-benchmark ticker compustat-rows items)]
              :when (seq bench)]
          [stmt (validation/compare-to-benchmark ticker bench
                                                 :statement stmt :view view :as-of as-of
                                                 :date-tolerance-days date-tolerance-days)])))

(defn validate-reclass
  "Validate one firm's reclassified income items (:view :compustat) against
   a FUNDA extract. Same conventions as validate-annual."
  [ticker compustat-rows & {:keys [as-of date-tolerance-days]
                            :or {date-tolerance-days 10}}]
  (let [bench (make-benchmark ticker compustat-rows annual-compustat-items)]
    (when (seq bench)
      (validation/compare-to-benchmark ticker bench
                                       :statement :income :view :compustat :as-of as-of
                                       :date-tolerance-days date-tolerance-days))))

(def annual-balance-compustat-items
  "Reclassified balance-sheet items (:view :compustat) -> FUNDA keys. Match
   rates by sample are in the namespace docstring (Reclassified-item match
   rates)."
  [["RE (Compustat)" :re] ["DLC (Compustat)" :dlc] ["DLTT (Compustat)" :dltt]
   ["RECT (Compustat)" :rect] ["CHE (Compustat)" :che] ["MIB (Compustat)" :mib]
   ["MIBN (Compustat)" :mibn] ["PSTK (Compustat)" :pstk] ["SEQ (Compustat)" :seq]
   ["CEQ (Compustat)" :ceq] ["TEQ (Compustat)" :teq]])

(defn validate-balance-reclass
  "Validate one firm's reclassified balance-sheet items (:view :compustat)
   against a FUNDA extract (needs the component columns: re, dlc, dltt, rect,
   che, mib, mibn, pstk, seq, ceq, teq). Same conventions as validate-annual."
  [ticker compustat-rows & {:keys [as-of date-tolerance-days]
                            :or {date-tolerance-days 10}}]
  (let [bench (make-benchmark ticker compustat-rows annual-balance-compustat-items)]
    (when (seq bench)
      (validation/compare-to-benchmark ticker bench
                                       :statement :balance :view :compustat :as-of as-of
                                       :date-tolerance-days date-tolerance-days))))

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
  ;; => {:income {:match-rate 1.0 ...} :balance {...} :cash-flow {...}}

  ;; Second/third-pass items are grouped by statement; validate-items runs
  ;; each group against its own statement:
  (validate-items "AAPL" annual annual-extended-items :as-of "2016-08-30")
  ;; => {:income {...} :balance {...} :cash-flow {...}}
  (validate-items "AAPL" annual annual-balance-cashflow-items :as-of "2016-08-30")
  ;; As-reported reclassification-sensitive items (income only):
  (validate-items "AAPL" annual {:income annual-reclass-items} :as-of "2016-08-30")

  ;; Reclassified views (:view :compustat), income and balance:
  (validate-reclass "AAPL" annual :as-of "2016-08-30")
  (validate-balance-reclass "AAPL" annual)

  ;; Grow chain coverage from what the sample missed:
  (e/unmapped-concepts :top 20))
