# edgarjure

[![Clojars Project](https://img.shields.io/clojars/v/com.github.clojure-finance/edgarjure.svg)](https://clojars.org/com.github.clojure-finance/edgarjure)
[![CI](https://github.com/clojure-finance/edgarjure/actions/workflows/ci.yml/badge.svg)](https://github.com/clojure-finance/edgarjure/actions/workflows/ci.yml)
[![cljdoc](https://cljdoc.org/badge/com.github.clojure-finance/edgarjure)](https://cljdoc.org/d/com.github.clojure-finance/edgarjure)

**A Clojure library for SEC EDGAR — filings, financials, and XBRL data, ready for research.**

Every public company in the U.S. files its financials, insider trades, institutional holdings, and risk disclosures with the SEC. That's decades of structured data covering thousands of firms — but getting at it programmatically means dealing with paginated APIs, inconsistent HTML, XBRL taxonomies, and rate limits. The Python ecosystem has several good libraries for different parts of this problem. edgarjure brings all of that functionality into a single Clojure stack built on `tech.ml.dataset`.

Pull a company's income statement in two lines. Screen an XBRL line item across every filer in a single call. Extract the full text of a 10-K's MD&A section. Download a decade of filings for a universe of tickers overnight. Financial statements are normalized — meaning the library maps the many different XBRL concept names companies use for the same line item (e.g., three different "Revenue" tags across accounting standard changes) to a single canonical label, and automatically picks the most recent filing when restatements exist. A point-in-time mode lets you see exactly the data that was available on any historical date, so your backtests and event studies are free of look-ahead bias. No API keys, no paid services — just SEC's public endpoints.

## What You Can Do

**Pull financial statements** — income statement, balance sheet, and cash flow, with automatic line-item resolution across different XBRL tags, restatement deduplication, and long or wide output. Four views per statement: as-reported, normalized (default), standardized — imputes missing line items from arithmetic identities (Gross Profit, Free Cash Flow, …) with full provenance — and compustat, which reclassifies line items to approximate Compustat definitions on both the income statement (D&A out of COGS, R&D folded into XSGA, special items added back to operating income) and the balance sheet (retained earnings incl. AOCI, debt incl. lease obligations, mezzanine vs equity-section noncontrolling interest, and more). Banks, insurers, and REITs automatically get industry-specific line items. Override the mappings for non-standard filers. For 10-Q data, quarterly and trailing-twelve-month values are derived automatically from YTD figures.

**Backtest without look-ahead bias** — the `:as-of` option on every financial statement and panel query restricts data to what was actually filed on or before a given date. Essential for event studies, strategy backtests, and panel regressions.

**Get XBRL financials as datasets** — company facts come as columnar datasets with human-readable labels. Discover available line items before querying. Build cross-sectional snapshots across all filers for a given period — useful for industry screens and peer comparisons.

**Look up any public company** — by ticker or CIK, with structured metadata including SIC codes, fiscal year end, state of incorporation, and mailing addresses.

**Query filing history** — filter by form type, date range, or full-text search. Pagination is automatic, even for filers with 10,000+ filings. Amendments are handled transparently.

**Read filings** — as HTML, plain text, or structured data. Extract specific sections (MD&A, Risk Factors, any 10-K/10-Q item) with full section bodies. Parse HTML tables into datasets. Pull exhibits and XBRL linkbase documents.

**Parse insider trades and institutional holdings** — Forms 3, 4, 5 (beneficial ownership, including amendments) and 13F-HR come back as structured maps and datasets, ready for ownership analysis.

**Download in bulk** — single company or batch, with bounded parallelism, skip-existing, and structured result envelopes reporting success, skip, or error per filing.

## Requirements

- Clojure 1.12+
- Java 21+

## Installation

```clojure
;; deps.edn
{:deps {com.github.clojure-finance/edgarjure {:mvn/version "0.4.0"}}}
```

## Getting Started

```clojure
(require '[edgar.api :as e])

;; SEC requires a User-Agent header — call once at startup
(e/init! "Your Name your@email.com")

;; Find a company
(e/cik "AAPL")              ;=> "0000320193"
(e/company-name "AAPL")     ;=> "Apple Inc."
(e/search "apple" :limit 5) ;=> seq of matching companies

;; Get the latest 10-K
(def f (e/filing "AAPL" :form "10-K"))

;; Read it
(e/text f)                   ;=> plain text string
(e/html f)                   ;=> raw HTML string

;; Extract the MD&A section (Item 7 in a 10-K)
(e/item f "7")
;=> {:title "Management's Discussion..." :text "...20k chars..." :method :html-heading-boundaries}

;; XBRL facts as a dataset (~24k rows for AAPL)
(e/facts "AAPL" :concept "Assets" :form "10-K")

;; Income statement with automatic line-item resolution and restatement dedup
(e/income "AAPL")
(e/income "AAPL" :shape :wide)   ; one column per line item
```

Every function accepts a ticker or CIK interchangeably. All arguments are keyword-based — no positional `form` or `type` parameters anywhere.

### Example: Apple's Net Income from the Last Three 10-Ks

```clojure
(require '[edgar.api :as e]
         '[tech.v3.dataset :as ds])

(e/init! "Your Name your@email.com")

;; Pull net income from XBRL — one line
(-> (e/facts "AAPL" :concept "NetIncomeLoss" :form "10-K")
    (ds/select-columns [:end :val :filed])
    (ds/head 3))
;=> :end        | :val          | :filed
;   2024-09-28  | 93736000000   | 2024-11-01
;   2023-09-30  | 96995000000   | 2023-11-03
;   2022-10-01  | 99803000000   | 2022-10-28

;; Or use the normalized income statement (handles line-item resolution + restatement dedup)
(-> (e/income "AAPL" :shape :wide)
    (ds/select-columns [:end "Net Income"])
    (ds/head 3))
```

### Example: Gross Margin — Apple vs. Peers

`e/frame` pulls a single XBRL line item for **every SEC filer** in a given fiscal year — in one API call. Frames return raw tags, so pick the tag most filers use (`e/unmapped-concepts` and `e/concepts` help discover coverage):

```clojure
;; Pull FY2023 revenue and gross profit for ALL filers — just two API calls
(def revenue  (e/frame "RevenueFromContractWithCustomerExcludingAssessedTax" "CY2023"))
(def gross-pr (e/frame "GrossProfit" "CY2023"))

;; Filter to a peer group — :cik is a zero-padded string, same as (e/cik ...)
(def peers #{"0000320193" "0000789019"})   ; AAPL, MSFT

(def rev-peers (ds/filter-column revenue  :cik peers))
(def gp-peers  (ds/filter-column gross-pr :cik peers))

;; Join and compute gross margin
(def result
  (-> (ds/inner-join :cik rev-peers gp-peers)
      (ds/rename-columns {:val "Revenue" :right.val "Gross Profit"
                          :entityName :company})
      (ds/map-columns :gross-margin ["Gross Profit" "Revenue"]
                      (fn [gp rev] (when (pos? rev) (double (/ gp rev)))))
      (ds/sort-by-column :gross-margin >)
      (ds/select-columns [:company "Revenue" "Gross Profit" :gross-margin])))

result
;=> | :company              | Revenue        | Gross Profit   | :gross-margin |
;   | MICROSOFT CORPORATION | 211915000000   | 146052000000   | 0.689         |
;   | Apple Inc.            | 383285000000   | 169148000000   | 0.441         |
```

The `revenue` and `gross-pr` datasets already contain every filer — to screen
the full universe (e.g., by SIC code) just change the filter. Caveats: not
every company files every tag (Alphabet, for example, does not tag
`GrossProfit`, so it drops out of this join — `(e/income "GOOG" :view
:standardized)` derives it instead), and looking up SIC codes via
`e/company-metadata` requires one API call per company, so filtering thousands
of filers that way may take a few minutes at the SEC's 10 requests/second
rate limit.

## The `edgar.api` Namespace

`edgar.api` is the recommended entry point. Require it as `[edgar.api :as e]` and you have access to everything. All functions validate their arguments with Malli at entry — bad inputs throw informative `ex-info` errors.

### Company Lookup

```clojure
(e/cik "AAPL")                ;=> "0000320193"
(e/company-name "AAPL")       ;=> "Apple Inc."
(e/company "AAPL")            ; full SEC submissions map
(e/search "apple" :limit 5)

(e/company-metadata "AAPL")
;=> {:cik "0000320193" :name "Apple Inc." :sic "3571"
;    :sic-description "Electronic Computers" :state-of-inc "CA"
;    :fiscal-year-end "0926" :tickers ["AAPL"]
;    :addresses {:business {:street1 "ONE APPLE PARK WAY" :city "CUPERTINO" ...} ...}
;    ...}
```

### Filings

```clojure
;; Query filing history — amendments excluded by default
(e/filings "AAPL" :form "10-K" :limit 5)
(e/filings "AAPL" :form "10-K" :include-amends? true)

;; Get a specific filing
(e/filing "AAPL" :form "10-K")          ; latest
(e/filing "AAPL" :form "10-K" :n 2)    ; 3rd latest (0-indexed)
(e/latest-effective-filing "AAPL" :form "10-K")  ; prefers amendment if newer

;; Look up by accession number — essential for reproducibility
(def f (e/filing-by-accession "0000320193-23-000106"))

;; Daily index — all filings submitted on a given date
(e/daily-filings "2026-03-10")
(e/daily-filings "2026-03-10" :form "8-K")

;; Full-text search via SEC EFTS
(e/search-filings "climate risk" :forms ["10-K"] :start-date "2022-01-01")

;; As a dataset
(e/filings-dataset "AAPL" :form "10-K")
```

### Filing Content and Section Extraction

10-K and 10-Q filings are divided into numbered item sections (e.g., Item 7 = MD&A, Item 1A = Risk Factors). edgarjure extracts the full text of any section — not just the heading, but the entire body.

```clojure
(def f (e/filing "AAPL" :form "10-K"))

;; Raw content
(e/html f)                        ; full HTML of the primary document
(e/text f)                        ; plain text (HTML stripped)

;; Extract individual sections by item number
;; Item 7 = "Management's Discussion and Analysis" (MD&A)
(e/item f "7")
;=> {:title "Management's Discussion..." :text "...20k chars..." :method :html-heading-boundaries}

;; Extract multiple sections at once
;; Item 7 = MD&A, Item 1A = Risk Factors; :remove-tables? strips numeric tables from the text
(e/items f :only #{"7" "1A"} :remove-tables? true)
;=> {"7"  {:title "Management's Discussion..." :text "..." :method ...}
;    "1A" {:title "Risk Factors" :text "..." :method ...}}

;; 10-Q filings use a two-part numbering scheme with Roman numerals:
;; Part I (financial) and Part II (other disclosures), e.g.:
;; "I-1" = Financial Statements, "I-2" = MD&A, "II-1A" = Risk Factors
(def q (e/filing "AAPL" :form "10-Q"))
(e/items q :only #{"I-2" "II-1A"})   ; MD&A and Risk Factors from a 10-Q

;; Batch extraction across many filings — saves results to disk as EDN
(require '[edgar.extract :as extract])
(extract/batch-extract! (e/filings "AAPL" :form "10-K" :limit 10)
                        "/data/extracted"
                        :items #{"7" "1A"}
                        :remove-tables? true
                        :skip-existing? true)

;; HTML tables → seq of tech.ml.dataset (with numeric type inference)
(e/tables f)
(e/tables f :min-rows 5 :min-cols 3)   ; filter small tables
(e/tables f :nth 0)                    ; first table only

;; Exhibits and XBRL linkbase documents
(e/exhibits f)              ; seq of exhibit metadata maps
(e/exhibit f "EX-21")       ; subsidiaries exhibit, or nil
(e/xbrl-docs f)             ; XBRL instance, schema, and linkbase files

;; Fetch exhibit content — two equivalent patterns:
(def ex (e/exhibit f "EX-21"))
(e/filing-document f (:name ex))   ; raw content string
(e/doc-url f (:name ex))           ; SEC archives URL

;; Structured parse via form-specific parser (e.g., Form 4 → insider trade map)
(e/obj f)

;; Save filing to disk
(e/save! f "/data/filings")      ; primary document only
(e/save-all! f "/data/filings")  ; all attachments
```

### XBRL Facts and Concept Discovery

```clojure
;; Full facts dataset — columns: :taxonomy :concept :label :description
;;   :unit :end :val :accn :fy :fp :form :filed :frame
(e/facts "AAPL")
(e/facts "AAPL" :concept "Assets")
(e/facts "AAPL" :concept ["Assets" "NetIncomeLoss"] :form "10-K")

;; Discover what concepts are available
(e/concepts "AAPL")   ;=> dataset [:taxonomy :concept :label :description]

;; Cross-sectional: one concept across all filers for a period
(e/frame "Assets" "CY2023Q4I")
(e/frame "SharesOutstanding" "CY2023Q4I" :unit "shares")
```

### Financial Statements

Income statement, balance sheet, and cash flow — with automatic line-item resolution, restatement deduplication, and duration/instant filtering built in.

```clojure
(e/income   "AAPL")                    ; long format (default)
(e/income   "AAPL" :shape :wide)       ; pivoted: one column per line item
(e/balance  "AAPL" :form "10-Q")
(e/cashflow "AAPL")

;; All three at once
(e/financials "AAPL")
;=> {:income ds :balance ds :cashflow ds}
(e/financials "AAPL" :shape :wide)

;; Quarterly and LTM (10-Q income/cashflow only)
(e/income   "AAPL" :form "10-Q")        ; adds :duration-months :val-q :val-ltm
(e/cashflow "AAPL" :form "10-Q")        ; single-quarter + trailing 12 months
```

### Four Views: As-Reported, Normalized, Standardized, Compustat

Every statement function takes a `:view` option exposing four layers of the same underlying XBRL facts:

```clojure
(e/income "AAPL" :view :as-reported)   ; raw rows exactly as filed — no dedup, no mapping
(e/income "AAPL")                      ; :normalized (default) — canonical labels + restatement dedup
(e/income "AAPL" :view :standardized)  ; + missing line items imputed from arithmetic identities
(e/income "AAPL" :view :compustat)     ; + line items reclassified to Compustat definitions
```

The standardized view fills gaps commercial databases fill by hand: if a filer doesn't tag `GrossProfit`, it is derived as Revenue − Cost of Revenue; Total Liabilities from L&E − Equity; a `"Free Cash Flow"` line item (OCF − Capex) is added to the cash flow statement. Derived rows are fully auditable — they carry `:method :derived` and `:derived-from [operand labels]`, while reported rows carry `:method :direct`.

The Compustat view goes one step further: a data-driven reclassification rule engine (`edgar.reclass`, rules in `resources/edgar/reclass/`) adds line items that approximate Compustat's item definitions, alongside the originals. On the income statement: `"COGS (Compustat)"` (D&A stripped out, sourced from the cash-flow statement), `"XSGA (Compustat)"` (R&D folded in, with a component fallback for filers that tag S&M and G&A separately), `"OIADP (Compustat)"` (restructuring/impairment charges added back), plus `DP`, `XOPR`, `OIBDP`, `Special Items`, `Gross Profit`, and excise-netted `Revenue`. On the balance sheet, rules build the analogous aggregates from the filer's tagged components: `"RE (Compustat)"` (retained earnings *including* accumulated OCI, matching how Compustat reports RE), `"DLC (Compustat)"` / `"DLTT (Compustat)"` (debt including finance-lease — and, post-ASC-842, operating-lease — obligations), `"MIB (Compustat)"` / `"MIBN (Compustat)"` (mezzanine redeemable vs equity-section noncontrolling interest), `"PSTK (Compustat)"` (preferred including mezzanine redeemable preferred), `"SEQ"`/`"CEQ"`/`"TEQ"`, `"RECT (Compustat)"` (total receivables incl. non-trade and tax refunds), and `"CHE (Compustat)"` (cash + short-term investments). Reclassified rows carry `:method :reclassified`, the `:rule` that produced them, and `:derived-from`; inspect the active rules with `(e/reclass-rules :income)` or `(e/reclass-rules :balance)`.

Two expense-classification questions cannot be answered from the companyfacts API at all: *where* a filer's D&A sits (own income-statement line, or embedded in COGS/SG&A?) and what its custom extension expense lines contain (Amazon's Fulfillment and Technology & content, which Compustat folds into XSGA/XRD). `(e/enable-fsds!)` turns on a local cache of the SEC's quarterly [Financial Statement Data Sets](https://www.sec.gov/dera/data/financial-statement-data-sets) (downloaded on first use into `~/.edgarjure/fsds`, shared across all companies), and the `:compustat` income view then uses statement placement to guard the D&A strip and extension-tag values as reclassification operands — including a first-class `"XRD (Compustat)"` line item:

```clojure
(e/enable-fsds!)                        ; opt-in; quarter files are 50-130 MB
(e/income "AMZN" :view :compustat)      ; XSGA now includes Fulfillment + Technology,
                                        ; XRD (Compustat) = the technology extension line
```

### Industry Routing

Banks, insurers, and REITs use fundamentally different income statement line items (Net Interest Income, Provision for Credit Losses, Premiums Earned, Rental Revenue, Property Operating Expense). edgarjure auto-detects them from the company's SIC code and switches concept chains:

```clojure
(e/income "JPM")                        ; auto-routes to bank chains
(e/income "MET")                        ; auto-routes to insurance chains
(e/income "SPG")                        ; auto-routes to REIT chains (SIC 6798)
(e/income "JPM" :industry :standard)    ; force the generic chains
```

### Concept Chains, Coverage, and Validation

The library decides which XBRL tags map to "Revenue", "Net Income", etc. by looking up each label in a list of candidate tags (trying the most common one first, then falling back to alternatives). Chains live in EDN files under `resources/edgar/concepts/` and are exposed both as public vars and through an inspection API:

```clojure
edgar.financials/income-statement-concepts     ; standard chains as data
(e/concepts-for :income :industry :bank)       ; active chains + file metadata
(e/income "AAPL" :concepts my-custom-chains)   ; per-call override

;; Coverage feedback loop: what did the chains miss?
(e/unmapped-concepts :top 20)   ; us-gaap concepts seen in facts but unmatched
(e/save-unmapped-concepts!)     ; persist to ~/.edgarjure/unmapped-concepts.edn

;; Quantify agreement with a benchmark (Compustat extract, hand-collected)
(require '[edgar.validation :as validation])
(validation/compare-to-benchmark "AAPL"
  [{:line-item "Revenue" :end "2023-09-30" :val 383285000000}]
  :date-tolerance-days 10)   ; Compustat month-end vs exact 52/53-week dates
;=> {:match-rate 1.0 :matched [...] :mismatched [] :missing []}
```

How close is the standardized view to a commercial database? A 19-firm study
against Compustat (13 industrials/tech, 3 banks, 3 insurers — method in
`examples/compustat_validation.clj`) found annual core line items match within
1% at **89.4%** overall (Total Assets 97.7%, Net Income 96.2%, Stockholders
Equity 94.7%, Operating Cash Flow 93.2%), and quarterly derived values match
Compustat's single-quarter items at **98.6%** (quarterly Total Assets 100%).
Across ~45 line items on all three statements, the strongest secondary items
are Total Liabilities & Equity (98.5%), Investing/Financing Cash Flow (96.2%),
Goodwill (95.2%), derived Total Equity (94.7%) and Working Capital (90%).
Expense-classification items (COGS, SG&A, R&D, Operating Income) diverge by
construction — Compustat reclassifies them (e.g. D&A stripped out). The
`:compustat` view's rule engine closes much of that gap on the same sample:
COGS 1% → **57%**, SG&A (as XSGA) 20% → **45%**, Operating Income (as OIADP)
24% → **38%**, Gross Profit vs REVT−COGS 0% → **59%** — the COGS/XSGA/OIADP
trio moves from ~15% to ~47% overall. The remainder is footnote-level
allocation Compustat keys from disclosures the companyfacts API doesn't carry
(partial D&A splits between COGS and SG&A, R&D embedded in COGS, special
items only visible in text). Per-share items need split-vintage alignment
(AJEX).

The balance-sheet `:compustat` rules were validated against live Compustat
(21 firms, FY2009–2025, 357 firm-years; rates below are the 13 industrials,
within 1%): Retained Earnings 12% → **96%** (Compustat RE includes
accumulated OCI), Long-Term Debt 33% → **76%** and Current Debt 27% → **63%**
(finance leases in all eras; operating leases folded into debt by Compustat
from ASC 842 / FY2019 onward), Receivables 20% → **61%**, Noncontrolling
Interest 2% → **81%** (mezzanine vs equity-section split; the equity-section
`MIBN` matches at 99%), SEQ/CEQ/TEQ 93–95%, CHE 76%. Equity items hold up for
banks and insurers too (RE 96%, SEQ/TEQ 95%); classified-balance-sheet items
(debt/receivables/cash buckets) do not apply to financial-format filers and
REITs, which remain future industry-chain work.

> **Disclaimer.** Compustat is a registered trademark of S&P Global Inc.
> edgarjure is an independent open-source project with no affiliation to,
> endorsement by, or derivation from S&P Global or WRDS products. The
> `:compustat` view is an *approximation* computed entirely from public SEC
> filings, built by empirically comparing edgarjure's output against a
> licensed Compustat sample — the match rates above quantify exactly how
> close (and how far) that approximation is. It is not a reimplementation of,
> or substitute for, licensed Compustat data, whose values rest on manual
> analyst review that no automated mapping reproduces.

For bulk standardization work, `edgar.fsds` downloads the SEC Financial Statement Data Sets (quarterly DERA dumps with every filer's numeric facts, statement placement, and company extension tags — things the companyfacts API lacks):

```clojure
(require '[edgar.fsds :as fsds])
(def zip (fsds/download-quarter! 2026 1 "/data/fsds"))
(def sub (fsds/load-table zip :sub))          ; also :num :pre :tag

;; Statement placement + extension tags for one filing:
(def adsh (-> (fsds/find-submissions sub :cik "1018724" :form "10-K")
              (ds/column :adsh) first))
(fsds/presentation (fsds/load-table zip :pre) adsh :stmt "IS")
;; AMZN's income statement includes FulfillmentExpense and
;; TechnologyAndInfrastructureExpense — extension tags invisible to the
;; companyfacts API; fsds/facts-for returns their values, and
;; fsds/statement-placement tells you which statement any tag sits on
;; (e.g. is D&A a separate income statement line, or embedded in COGS?).
```

### Quarterly and LTM Derivation

For 10-Q queries on income statement and cash flow (flow variables), the long-format output includes three derived columns: `:duration-months` classifies each observation window (3/6/9/12 months, tolerant of 52/53-week fiscal calendars), `:val-q` is the single-quarter value, and `:val-ltm` is the trailing twelve months. Derivation works purely from each observation's actual `:start`/`:end` dates — deliberately not from SEC's `:fy`/`:fp` fields, which describe the filing rather than the observation and collide across the comparative periods a 10-Q contains. Quarters come from same-fiscal-year-start YTD differencing; 10-K annual rows participate so that fiscal Q4 (never filed as a 10-Q) is derived as FY − 9M YTD, which is what makes LTM windows computable. Balance sheet queries and 10-K queries are unaffected.

### Panel Datasets

```clojure
(e/panel ["AAPL" "MSFT" "GOOG"] :concept "Assets")
(e/panel ["AAPL" "MSFT" "GOOG"] :concept ["Assets" "NetIncomeLoss"] :form "10-Q")

;; Pivot long → wide
(e/pivot some-facts-ds)
```

### Form Parsers

Form-specific parsers register via the `filing-obj` multimethod and activate on require:

```clojure
(require '[edgar.forms])   ; loads all built-in parsers at once
```

**Forms 3, 4, 5 — Beneficial Ownership (initial, changes, annual):**

```clojure
(-> (e/filing "AAPL" :form "4") e/obj)
;=> {:form "4"
;    :issuer {:cik "0000320193" :name "Apple Inc." :ticker "AAPL"}
;    :reporting-owner {:name "..." :is-officer? true :officer-title "CEO" ...}
;    :transactions [{:type :non-derivative :coding "S" :shares 50000.0
;                    :price 185.50 :acquired-disposed "D" ...}]
;    :holdings []}

(-> (e/filing "AAPL" :form "3") e/obj)
;=> {:form "3" ...
;    :transactions []
;    :holdings [{:type :derivative :security-title "Restricted Stock Unit"
;                :underlying-title "Common Stock" :underlying-shares 301040.0 ...}]}
```

All three (plus their `/A` amendments) share the ownership XML schema and one parser (`edgar.forms.ownership`). Form 3 carries initial holdings, Form 4 transactions, Form 5 the annual statement with both.

**Schedules 13D/13G — >5% Beneficial Ownership (XML era, filed Dec 2024+):**

```clojure
(-> (e/filing "AAPL" :form "SCHEDULE 13G") e/obj)
;=> {:form "SCHEDULE 13G" :schedule :13g
;    :security-class "Common Stock" :event-date "03/31/2026" :rule "Rule 13d-1(b)"
;    :issuer {:cik "0000320193" :name "Apple Inc" :cusip "037833100"}
;    :reporting-persons [{:name "..." :aggregate-owned 1.099E9
;                         :percent-of-class 7.48 :type "IA" ...}]}
```

The 13D and 13G XML schemas spell their fields differently; both normalize to the same shape, and activist group filings yield one entry per reporting person. Legacy `SC 13D`/`SC 13G` filings (pre-Dec-2024, HTML/text) fall through to the default raw-HTML result.

**13F-HR — Institutional Holdings:**

```clojure
(-> (e/filing "BRK-A" :form "13F-HR") e/obj)
;=> {:form "13F-HR"
;    :period-of-report "2024-03-31"
;    :manager {:name "BERKSHIRE HATHAWAY INC" ...}
;    :holdings <tech.ml.dataset>   ; :name :cusip :value :shares ...
;    :total-value 12345678}
```

### Guided Tour Notebook

`examples/notebook.clj` is a [Clerk](https://github.com/nextjournal/clerk) notebook touring the whole library — statements and views, industry routing, quarterly/LTM derivation, point-in-time mode, panels, and ownership forms:

```
clj -M:clerk:nrepl
;; in the REPL:
(require '[nextjournal.clerk :as clerk])
(clerk/serve! {:browse? true})
(clerk/show! "examples/notebook.clj")
```

### Disk Cache

All HTTP responses are cached in memory by default. For cross-session persistence — batch jobs, repeated research runs — enable the opt-in disk cache:

```clojure
(e/enable-disk-cache!)          ; ~/.edgarjure/http-cache; JSON 24h, filings 30d
(e/disk-cache-stats)            ;=> {:dir "..." :entries 42 :bytes 12345678}
(e/clear-disk-cache!)           ; delete all entries
(e/disable-disk-cache!)
```

### Bulk Downloads

```clojure
(require '[edgar.download :as download])

(download/download-filings! "AAPL" "/data/filings" :form "10-K" :limit 5)
(download/download-filings! "AAPL" "/data/filings" :form "10-K" :skip-existing? true)

;; Batch with bounded parallelism
(download/download-batch! ["AAPL" "MSFT" "GOOG"] "/data/filings"
                           :form "10-K" :limit 3 :parallelism 4)

;; All functions return structured envelopes:
;; {:status :ok :path "..."} or {:status :skipped ...} or {:status :error ...}
```

## Point-in-Time Data

> **Essential for backtesting and investment strategy research.**

By default, financial statements return the latest restated values — suitable for current analysis but biased for historical backtests. The `:as-of` option restricts to filings submitted on or before a given date, giving you exactly what a market participant knew at that point.

```clojure
;; Current (default): latest restated FY2021 figures
(e/income "AAPL")

;; Point-in-time: only data available in EDGAR as of 2022-01-01
(e/income "AAPL" :as-of "2022-01-01")

;; Consistent vintage across all three statements
(e/financials "AAPL" :as-of "2022-01-01")

;; Panel — point-in-time across multiple tickers
(e/panel ["AAPL" "MSFT" "GOOG"] :concept "Assets" :as-of "2022-01-01")

;; Combinable with other options
(e/income "AAPL" :as-of "2022-01-01" :shape :wide :form "10-Q")
```

**Caveats:** Accounting standard changes (e.g., ASC 606) restate prior periods within the same filing — restrict your panel or model the structural break. Raw `e/facts` datasets are unfiltered; filter on `:filed` manually if needed. The `:filed` date is the SEC submission date, not the earnings announcement — add a few days buffer for tight event windows.

## Architecture

```
SEC EDGAR APIs
    │
    ▼
edgar.core            HTTP client, JSON + raw caches, retry, rate limiter
    │
    ├── edgar.schema        Malli schemas + validation
    ├── edgar.api           Unified entry point (wraps everything below)
    ├── edgar.company       Ticker↔CIK resolution, metadata, company search
    ├── edgar.filings       Filing index queries, pagination, amendments, daily/quarterly index
    │       └── edgar.filing        Filing content, accession lookup, exhibits
    │               ├── edgar.download      Bulk save to disk
    │               ├── edgar.extract       NLP item-section extraction
    │               ├── edgar.tables        HTML table → dataset
    │               └── edgar.forms/        Form parsers (Forms 3/4/5, 13F-HR)
    ├── edgar.xbrl          Company facts → dataset, concept discovery, frames
    │       ├── edgar.financials    Statements: views, imputation, industry routing
    │       │       ├── edgar.reclass       Reclassification rule engine (Compustat definitions)
    │       │       └── edgar.validation    Benchmark match-rate harness
    │       └── edgar.dataset       Panel datasets, pivot, cross-sectional
    └── edgar.fsds          SEC Financial Statement Data Sets (bulk quarterly dumps)
```

## Namespace Reference

| Namespace | Role |
|---|---|
| `edgar.api` | Unified entry point — wraps all namespaces; Malli-validated |
| `edgar.core` | HTTP client, TTL cache (5 min metadata / 1 hr XBRL), opt-in cross-session disk cache (`e/enable-disk-cache!`), exponential backoff retry, Bucket4j rate limiter (10 req/s) |
| `edgar.schema` | Malli schemas and `validate!` helper for all public API functions |
| `edgar.company` | Ticker↔CIK resolution, company search, shaped metadata |
| `edgar.filings` | Filing index queries, pagination for large filers, amendment handling, daily index, quarterly master.idx, EFTS search |
| `edgar.filing` | Individual filing content, accession number lookup, save to disk, `filing-obj` multimethod, exhibit API |
| `edgar.download` | Bulk downloader — single company and batch, structured result envelopes |
| `edgar.xbrl` | XBRL company-facts → `tech.ml.dataset` with labels; concept discovery; cross-sectional frames |
| `edgar.financials` | Income statement, balance sheet, cash flow; four views; line-item resolution; imputation; industry routing; `:as-of` |
| `edgar.reclass` | Reclassification rule engine — EDN rulesets approximating Compustat item definitions (`:view :compustat`) |
| `edgar.validation` | Match-rate harness against benchmark datasets (Compustat extracts, hand-collected figures) |
| `edgar.fsds` | SEC Financial Statement Data Sets (DERA quarterly dumps) — download + load as datasets |
| `edgar.extract` | NLP item-section extraction (10-K, 10-Q, 8-K); batch mode |
| `edgar.dataset` | Panel datasets, cross-sectional snapshots, pivot helpers |
| `edgar.tables` | HTML table extraction → `tech.ml.dataset` |
| `edgar.forms` | Central loader — `(require '[edgar.forms])` activates all built-in parsers |
| `edgar.forms.ownership` | Forms 3/4/5 parser (beneficial ownership: initial holdings, insider trades, annual statements; includes `/A` amendments) |
| `edgar.forms.form4` | Historical Form 4 entry point — delegates to `edgar.forms.ownership` |
| `edgar.forms.form13f` | 13F-HR parser (institutional holdings, XML-era post-2013Q2) |
| `edgar.forms.schedule13` | Schedule 13D/13G parser (>5% beneficial ownership, XML-era Dec 2024+) |

## Conventions

- **Keyword args throughout** — no positional parameters
- **Ticker or CIK interchangeably** — every function resolves via `company-cik`
- **`:concept` accepts string or collection** — coerced to a set internally
- **Amendments excluded by default** — pass `:include-amends? true` to include `10-K/A` etc.
- **Datasets always return `tech.ml.dataset`** — never seq-of-maps
- **Form parsers must be required** — `(require '[edgar.forms])` loads all at once
- **Download results are structured envelopes** — `{:status :ok/:skipped/:error ...}`
- **All `edgar.api` functions are Malli-validated** — bad args throw `ex-info` with `:type ::edgar.schema/invalid-args`
- **Banks and insurers are auto-routed** — `e/income` detects SIC 6000–6199/6712 (banks) and 6300–6399/6411 (insurers) and switches to industry-specific concept chains; coverage is partial and grows via the unmapped-concepts feedback loop

## Rate Limits and Caching

SEC enforces a `User-Agent` header and a rate limit of ~10 requests/second. edgarjure handles both automatically: `set-identity!` sets the header, and a Bucket4j token-bucket rate limiter paces requests. JSON responses are cached in memory (5 min for metadata, 1 hr for XBRL facts); raw documents (filing HTML, indexes) are cached in a bounded cache (64 entries, 1 hr) so repeated content access on the same filing hits the network once. Failed requests retry with exponential backoff on 429/5xx (up to 3 attempts, 2s → 4s → 8s).

## Development

```bash
# Start REPL (random port, written to .nrepl-port)
clj -M:nrepl

# Run offline unit tests (169 tests, 888 assertions, no network)
clj -M:test

# Run live integration tests (manual only, requires network)
clj -M:test-integration
```

```clojure
(require '[edgar.api :as e]
         '[edgar.forms]
         '[tech.v3.dataset :as ds])

(e/init! "Your Name your@email.com")

;; Clear the in-memory cache when testing fresh fetches
(edgar.core/clear-cache!)
```

## License

Apache-2.0 — see [LICENSE](LICENSE).
