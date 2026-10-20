# edgarjure

[![Clojars Project](https://img.shields.io/clojars/v/com.github.clojure-finance/edgarjure.svg)](https://clojars.org/com.github.clojure-finance/edgarjure)
[![CI](https://github.com/clojure-finance/edgarjure/actions/workflows/ci.yml/badge.svg)](https://github.com/clojure-finance/edgarjure/actions/workflows/ci.yml)
[![cljdoc](https://cljdoc.org/badge/com.github.clojure-finance/edgarjure)](https://cljdoc.org/d/com.github.clojure-finance/edgarjure)

**A Clojure library for SEC EDGAR — filings, financials, and XBRL data, ready for research.**

Every public company in the U.S. files its financials, insider trades, institutional holdings, and risk disclosures with the SEC. That's decades of structured data covering thousands of firms — but getting at it programmatically means dealing with paginated APIs, inconsistent HTML, XBRL taxonomies, and rate limits. The Python ecosystem has several good libraries for different parts of this problem. edgarjure brings all of that functionality into a single Clojure stack built on `tech.ml.dataset`.

Pull a company's income statement in two lines. Screen an XBRL line item across every filer in a single call. Extract the full text of a 10-K's MD&A section. Download a decade of filings for a universe of tickers overnight. Financial statements are normalized — meaning the library maps the many different XBRL concept names companies use for the same line item (e.g., three different "Revenue" tags across accounting standard changes) to a single canonical label, and automatically picks the most recent filing when restatements exist. A point-in-time mode lets you see exactly the data that was available on any historical date, so your backtests and event studies are free of look-ahead bias. No API keys, no paid services — just SEC's public endpoints.

## What You Can Do

**Pull financial statements** — income statement, balance sheet, and cash flow, with automatic line-item resolution across different XBRL tags, restatement deduplication, and long or wide output. Four views per statement: as-reported, normalized (default), standardized — imputes missing line items from arithmetic identities (Gross Profit, Free Cash Flow, …) with full provenance — and compustat, which adds line items approximating Compustat item definitions on the income statement and balance sheet. Banks, insurers, and REITs automatically get industry-specific line items. Override the mappings for non-standard filers. For 10-Q data, quarterly and trailing-twelve-month values are derived automatically from YTD figures.

**Backtest without look-ahead bias** — the `:as-of` option on every financial statement and panel query restricts data to what was actually filed on or before a given date. Essential for event studies, strategy backtests, and panel regressions.

**Get XBRL financials as datasets** — company facts come as columnar datasets with human-readable labels. Discover available line items before querying. Build cross-sectional snapshots across all filers for a given period — useful for industry screens and peer comparisons.

**Look up any public company** — by ticker or CIK, with structured metadata including SIC codes, fiscal year end, state of incorporation, and mailing addresses.

**Query filing history** — filter by form type or date range, list everything filed on a given day, or run a full-text search. Pagination is automatic, even for filers with 10,000+ filings. Amendments are handled transparently.

**Read filings** — as HTML, plain text, or structured data. Extract specific sections (MD&A, Risk Factors, any 10-K/10-Q/8-K item) with full section bodies. Parse HTML tables into datasets. Pull exhibits and XBRL linkbase documents.

**Parse insider trades and institutional holdings** — Forms 3, 4, 5 (beneficial ownership, including amendments), Schedules 13D/13G, and 13F-HR come back as structured maps and datasets, ready for ownership analysis.

**Download in bulk** — single company or batch, with bounded parallelism, skip-existing, and structured result envelopes reporting success, skip, or error per filing.

## Requirements

- Clojure 1.12+
- Java 21+

## Installation

```clojure
;; deps.edn
{:deps {com.github.clojure-finance/edgarjure {:mvn/version "0.5.2"}}}
```

## Getting Started

```clojure
(require '[edgar.api :as e])

;; SEC requires a User-Agent header — call once at startup
(e/init! "Your Name your@email.com")

;; Find a company
(e/cik "AAPL")              ;=> "0000320193"
(e/company-name "AAPL")     ;=> "Apple Inc."
(e/search "apple" :limit 5) ;=> [{:cik "0000320193" :name "Apple Inc." :tickers ["AAPL"]} ...]

;; Get the latest 10-K
(def f (e/filing "AAPL" :form "10-K"))

;; Read it
(e/text f)                   ;=> plain text string
(e/html f)                   ;=> raw HTML string

;; Extract the MD&A section (Item 7 in a 10-K)
(e/item f "7")
;=> {:title "Management's Discussion..." :text "...20k chars..." :method :html-heading-boundaries}

;; XBRL facts as a dataset
(e/facts "AAPL" :concept "Assets" :form "10-K")

;; Income statement with automatic line-item resolution and restatement dedup
(e/income "AAPL")
(e/income "AAPL" :shape :wide)   ; one row per fiscal year, one column per line item
```

Every function accepts a ticker or CIK interchangeably (a CIK may be a string or an integer). All arguments are keyword-based — no positional `form` or `type` parameters anywhere.

### Example: Apple's Net Income from the Last Three 10-Ks

```clojure
(require '[edgar.api :as e]
         '[tech.v3.dataset :as ds])

(e/init! "Your Name your@email.com")

;; Raw XBRL facts: every 10-K also reports the prior years, so a fiscal
;; year appears once per filing that contains it
(-> (e/facts "AAPL" :concept "NetIncomeLoss" :form "10-K")
    (ds/select-columns [:end :val :filed])
    (ds/head 4))
;=> | :end       | :val         | :filed     |
;   | 2025-09-27 | 112010000000 | 2025-10-31 |
;   | 2024-09-28 |  93736000000 | 2025-10-31 |
;   | 2024-09-28 |  93736000000 | 2024-11-01 |
;   | 2023-09-30 |  96995000000 | 2025-10-31 |

;; The normalized income statement resolves line items and deduplicates
;; restatements — one row per fiscal year
(-> (e/income "AAPL" :shape :wide)
    (ds/select-columns [:end "Net Income"])
    (ds/head 3))
;=> | :end       | Net Income   |
;   | 2025-09-27 | 112010000000 |
;   | 2024-09-28 |  93736000000 |
;   | 2023-09-30 |  96995000000 |
```

### Example: Gross Margin — Apple vs. Peers

`e/frame` pulls a single XBRL line item for **every SEC filer** in a given fiscal year — in one API call. Frames return raw tags, so pick the tag most filers use (`e/unmapped-concepts` and `e/concepts` help discover coverage):

```clojure
;; Pull FY2023 revenue and gross profit for ALL filers — just two API calls
(def revenue  (e/frame "RevenueFromContractWithCustomerExcludingAssessedTax" "CY2023"))
(def gross-pr (e/frame "GrossProfit" "CY2023"))

;; Filter to a peer group — :cik is a zero-padded string, same as (e/cik ...)
(def peers #{"0000320193" "0000789019"})   ; AAPL, MSFT

(def rev-peers (-> (ds/filter-column revenue :cik peers)
                   (ds/select-columns [:cik :entityName :val])
                   (ds/rename-columns {:entityName :company :val "Revenue"})))
(def gp-peers  (-> (ds/filter-column gross-pr :cik peers)
                   (ds/select-columns [:cik :val])
                   (ds/rename-columns {:val "Gross Profit"})))

;; Join and compute gross margin
(require '[tech.v3.dataset.join :as j])
(def result
  (-> (j/inner-join :cik rev-peers gp-peers)
      (ds/column-map :gross-margin (fn [gp rev] (when (pos? rev) (double (/ gp rev))))
                     ["Gross Profit" "Revenue"])
      (ds/sort-by-column :gross-margin >)
      (ds/select-columns [:company "Revenue" "Gross Profit" :gross-margin])))

result
;=> | :company              | Revenue      | Gross Profit | :gross-margin |
;   | MICROSOFT CORPORATION | 211915000000 | 146052000000 | 0.689         |
;   | Apple Inc.            | 383285000000 | 169148000000 | 0.441         |
```

The `revenue` and `gross-pr` datasets already contain every filer — to screen
the full universe (e.g., by SIC code) just change the filter. Caveats: not
every company files every tag (Alphabet, for example, does not tag
`GrossProfit`, so it drops out of this join — `(e/income "GOOG" :view
:standardized)` derives it instead), and looking up SIC codes via
`e/company-metadata` requires one API call per company, so filtering thousands
of filers that way may take a few minutes at the SEC's 10 requests/second
rate limit.

### Guided Tour Notebook

`examples/notebook.clj` is a [Clerk](https://github.com/nextjournal/clerk) notebook touring the whole library — statements and views, industry routing, quarterly/LTM derivation, point-in-time mode, panels, and ownership forms:

```
clj -M:clerk:nrepl
;; in the REPL:
(require '[nextjournal.clerk :as clerk])
(clerk/serve! {:browse? true})
(clerk/show! "examples/notebook.clj")
```

## The `edgar.api` Namespace

`edgar.api` is the recommended entry point. Require it as `[edgar.api :as e]` and you have access to everything. The core query functions (company lookup, filings, statements, search, frames, panels, tables) validate their arguments with Malli at entry — bad inputs throw informative `ex-info` errors.

### Company Lookup

```clojure
(e/cik "AAPL")                ;=> "0000320193"
(e/cik "BRK.B")               ;=> "0001067983" (share-class dots accepted)
(e/cik 320193)                ;=> "0000320193" (integer CIKs accepted)
(e/ticker 1652044)            ;=> "GOOGL" (reverse lookup; primary share class, nil if unlisted)
(e/company-name "AAPL")       ;=> "Apple Inc."
(e/company "AAPL")            ; full SEC submissions map

;; Search entities by CURRENT name (ticker and CIK queries match too), best match first
(e/search "apple" :limit 5)
;=> [{:cik "0000320193" :name "Apple Inc." :tickers ["AAPL"]} ...]   ; at most 10 results

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
(e/filings "AAPL" :form "10-K" :include-amends? true)   ; 10-K and 10-K/A
(e/filings "AAPL" :form "10-K/A")                       ; amendments only

;; Get a specific filing
(e/filing "AAPL" :form "10-K")          ; latest
(e/filing "AAPL" :form "10-K" :n 2)    ; 3rd latest (0-indexed)
(e/latest-effective-filing "AAPL" :form "10-K")  ; prefers amendment if newer

;; Look up by accession number — essential for reproducibility
(def f (e/filing-by-accession "0000320193-23-000106"))
;=> {:cik "0000320193" :accessionNumber "0000320193-23-000106" :form "10-K"
;    :primaryDocument "..." :filingDate "2023-11-03" :url "..."
;    :filers [{:name "Apple Inc." :role "Filer" :cik "0000320193"}]}
;; :cik is the company the filing is about, even when a filing agent submitted it;
;; :filers lists every entity on the filing index (e.g. issuer and owner of a Form 4)

;; The filing's index: every document, plus form type, filing date, and filers
(e/filing-index f)
;=> {:files [{:sequence "1" :name "..." :type "10-K" :description "..." :size "..."} ...]
;    :formType "10-K" :filingDate "2023-11-03" :filers [...]}

;; Daily index — every filing disseminated on a given date, one map per filing
(e/daily-filings "2026-03-10")
(e/daily-filings "2026-03-10" :form "8-K")   ; exact match: excludes 8-K/A
;=> ({:accessionNumber "..." :form "8-K" :filingDate "2026-03-10"
;     :cik "..." :companyName "..." :filers [{:cik "..." :name "..."}]} ...)

;; :cik is the FIRST listed filer (for a Form 4 often the owner) — match on :filers
(e/daily-filings "2026-03-10" :form "4"
                 :filter-fn (fn [f] (some #(= "0000320193" (:cik %)) (:filers f))))

;; Quarterly full index (1993 onward) — every filing in a quarter
(e/quarterly-index 2025 4 :form "10-K")      ; :form is exact
;=> ({:cik "0001000230" :company-name "OPTICAL CABLE CORP" :form-type "10-K"
;     :date-filed "2025-12-18" :filename "edgar/data/1000230/0001437749-25-038228.txt"} ...)

;; Full-text search via SEC EFTS (filings since 2001) — one map per filing
(e/search-filings "climate risk" :forms ["10-K"] :start-date "2022-01-01")
;=> ({:accessionNumber "..." :cik "..." :form "10-K" :filingDate "..." :companyName "..."
;     :document "..." :url "..." ...raw EFTS fields (:items :period_ending :sics ...)} ...)

;; As a dataset
(e/filings-dataset "AAPL" :form "10-K")
```

Every map these functions return works directly with `e/html`, `e/text`, `e/items`, `e/obj`, and the exhibit API below. The daily index is the SEC's daily master index: it is complete, but it carries neither the primary document nor 8-K item numbers — call `e/filing-by-accession` for the former, and use `e/search-filings` (whose hits carry `:items`) to filter 8-Ks by item. It is empty for weekends, holidays, future dates, and the current day until the SEC publishes the index around 10 pm ET; it throws when the SEC refuses access (e.g. a missing User-Agent). `e/quarterly-index` reads the quarter's `master.idx` (30–60 MB; not held in the in-memory cache). For `e/search-filings`, a single `:start-date` or `:end-date` works (the other bound defaults to today or 2001-01-01), and `:forms` also matches amendments (`["8-K"]` returns 8-K/A too). EFTS caps search results at 10,000 offsets — narrow the query or date range for exhaustive sweeps.

### Filing Content and Section Extraction

10-K and 10-Q filings are divided into numbered item sections (e.g., Item 7 = MD&A, Item 1A = Risk Factors), 8-Ks into numbered event items (e.g., Item 2.02 = Results of Operations). edgarjure extracts the full text of any section — not just the heading, but the entire body.

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

;; 8-K items use the SEC's decimal numbering (1.01 ... 9.01)
(e/items (e/filing "AAPL" :form "8-K"))
;=> e.g. {"2.02" {:title "..." :text "..." ...} "9.01" {...}}

;; Batch extraction across many filings — saves results to disk as EDN
(require '[edgar.extract :as extract])
(extract/batch-extract! (e/filings "AAPL" :form "10-K" :limit 10)
                        "/data/extracted"
                        :items #{"7" "1A"}
                        :remove-tables? true
                        :skip-existing? true)

;; HTML tables → seq of tech.ml.dataset (with numeric type inference)
(e/tables f)
(e/tables f :min-rows 5 :min-cols 3)   ; filter small tables (:min-rows 1 keeps single-row tables)
(e/tables f :n 0)                      ; first table only

;; Exhibits and XBRL linkbase documents
(e/exhibits f)              ; seq of exhibit maps {:name :type :description :sequence :size}
(e/exhibit f "EX-21.1")     ; subsidiaries exhibit, or nil — :type must match exactly
(e/xbrl-docs f)             ; XBRL instance, schema, and linkbase files

;; Fetch exhibit content — two equivalent patterns:
(def ex (e/exhibit f "EX-21.1"))
(e/filing-document f (:name ex))   ; raw content string
(e/doc-url f (:name ex))           ; SEC archives URL

;; Structured parse via form-specific parser (e.g., Form 4 → insider trade map)
(e/obj f)

;; Save filing to disk under dir/<form>/<cik>/<accession>/ ("10-K/A" → "10-K_A")
(e/save! f "/data/filings")      ; primary document only
(e/save-all! f "/data/filings")  ; all attachments
```

`e/tables` skips layout tables and turns each data table into a dataset: column names come from the header row (a sub-header row such as units is folded in, e.g. `"2025 Amount (in millions)"`), colspan grids collapse to one column per header cell, `$`/`%`/`)` fragment cells merge into their number, parentheses become negatives, and `—`/`-` placeholders read as nil so the column stays numeric. Most filings number their exhibits, so `e/exhibit` wants the full type (`"EX-21.1"`, not `"EX-21"`) — list them with `e/exhibits`.

Pre-2001 plain-text filings are parsed line by line (item headings such as `ITEM 7. MANAGEMENT'S DISCUSSION ...`), with the same `:only` filtering; when the index lists only the complete submission text file, that file serves as the primary document. Older variants (10-K405, 10-KT, 10-KSB, 10-QSB, 10-QT) and amendments use the item list of their base form (10-KSB: MD&A is Item 6). Known limitations: PART headings are not section boundaries, so the last Part I item of a 10-K ends with the "PART II" heading text; 8-K item titles come back empty when a filing puts the title in a separate HTML element from the "Item 2.02" label.

### XBRL Facts and Concept Discovery

```clojure
;; Full facts dataset (~24k rows for AAPL) — columns: :taxonomy :concept :label
;;   :description :unit :start (duration facts) :end :val :accn :fy :fp :form :filed :frame
(e/facts "AAPL")
(e/facts "AAPL" :concept "Assets")
(e/facts "AAPL" :concept ["Assets" "NetIncomeLoss"] :form "10-K")
;; :unit is the full XBRL unit: "USD", "shares", per-share facts "USD/shares"

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
(e/income   "AAPL" :shape :wide)       ; one row per fiscal year, one column per line item
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

A 10-K also carries quarterly-footnote figures (e.g. fourth-quarter data sharing the fiscal-year end date). Long-format 10-K income and cash-flow output keeps those rows and tags every row with `:duration-months` (3/6/9/12) — select `(= 12 :duration-months)` for the annual figures. `:shape :wide` keeps only the annual windows.

### Quarterly and LTM Derivation

For 10-Q queries on income statement and cash flow (flow variables), the long-format output includes three derived columns: `:duration-months` classifies each observation window (3/6/9/12 months, tolerant of 52/53-week fiscal calendars), `:val-q` is the single-quarter value, and `:val-ltm` is the trailing twelve months. Derivation works purely from each observation's actual `:start`/`:end` dates — deliberately not from SEC's `:fy`/`:fp` fields, which describe the filing rather than the observation and collide across the comparative periods a 10-Q contains. Quarters come from same-fiscal-year-start YTD differencing; 10-K annual rows participate so that fiscal Q4 (never filed as a 10-Q) is derived as FY − 9M YTD, which is what makes LTM windows computable. Share counts and per-share amounts are never differenced or summed: their `:val-q` is the reported 3-month value (nil when only YTD is reported) and `:val-ltm` is nil. Balance sheet queries are unaffected.

### Point-in-Time Data

> **Essential for backtesting and investment strategy research.**

By default, financial statements return the latest restated values — suitable for current analysis but biased for historical backtests. The `:as-of` option restricts to filings submitted on or before a given date, giving you exactly what a market participant knew at that point. It applies to every view, `:as-reported` included.

```clojure
;; Current (default): latest restated figures
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

### Four Views: As-Reported, Normalized, Standardized, Compustat

Every statement function takes a `:view` option exposing four layers of the same underlying XBRL facts:

```clojure
(e/income "AAPL" :view :as-reported)   ; raw rows exactly as filed — no dedup, no mapping
(e/income "AAPL")                      ; :normalized (default) — canonical labels + restatement dedup
(e/income "AAPL" :view :standardized)  ; + missing line items imputed from arithmetic identities
(e/income "AAPL" :view :compustat)     ; + line items approximating Compustat definitions
```

The standardized view fills gaps commercial databases fill by hand: if a filer doesn't tag `GrossProfit`, it is derived as Revenue − Cost of Revenue; Total Liabilities from L&E − Equity; a `"Free Cash Flow"` line item (OCF − Capex) is added to the cash flow statement. Derived rows are fully auditable — they carry `:method :derived` and `:derived-from [operand labels]`, while reported rows carry `:method :direct`.

The Compustat view goes one step further: a data-driven reclassification rule engine (`edgar.reclass`, rules in `resources/edgar/reclass/`) adds line items that approximate Compustat's item definitions, alongside the originals. On the income statement: `"COGS (Compustat)"` (D&A stripped out, sourced from the cash-flow statement), `"XSGA (Compustat)"` (R&D folded in, with a component fallback for filers that tag S&M and G&A separately), `"OIADP (Compustat)"` (restructuring/impairment charges added back), plus `"DP (Compustat)"`, `"XOPR (Compustat)"`, `"OIBDP (Compustat)"`, `"Special Items (Compustat)"`, `"Revenue (Compustat)"` (net of excise taxes where the filer tags them, and the sales line rather than a revenue total that includes other income), `"Gross Profit (Compustat)"` (that revenue less Compustat COGS), and `"XRD (Compustat)"` (the reported R&D line; an extension-tag variant, e.g. AMZN's technology and content, needs FSDS — see below). On the balance sheet, rules build the analogous aggregates from the filer's tagged components: `"RE (Compustat)"` (retained earnings *including* accumulated OCI, as in Compustat's RE), `"DLC (Compustat)"` / `"DLTT (Compustat)"` (debt including finance-lease — and, post-ASC-842, operating-lease — obligations), `"MIB (Compustat)"` / `"MIBN (Compustat)"` / `"MIBT (Compustat)"` (mezzanine redeemable vs equity-section noncontrolling interest, and their total), `"PSTK (Compustat)"` (preferred including mezzanine redeemable preferred), `"SEQ (Compustat)"`/`"CEQ (Compustat)"`/`"TEQ (Compustat)"`, `"RECT (Compustat)"` (total receivables incl. non-trade and tax refunds), and `"CHE (Compustat)"` (cash + short-term investments). Presentation-specific rules cover filers that report expenses by nature with no cost-of-sales or SG&A line (COGS from total costs) and integrated oil companies (exploration expense in XSGA; operating expenses and non-income taxes in COGS). On 10-K statements the view keeps each value as *first reported* by the fiscal year's own 10-K rather than the latest restatement, which is how Compustat's annual items behave empirically. Reclassified rows carry `:method :reclassified`, the `:rule` that produced them, and `:derived-from`; inspect the active rules with `(e/reclass-rules :income)` or `(e/reclass-rules :balance)`. The income rules were fitted on the standard (industrial) chains and only apply there: for companies routed to bank, insurance, or REIT chains, `:view :compustat` on the income statement equals `:view :standardized`. How close the approximation gets is measured in [Validation against Compustat](#validation-against-compustat).

Two expense-classification questions cannot be answered from the companyfacts API at all: *where* a filer's D&A sits (own income-statement line, or embedded in COGS/SG&A?) and what its custom extension expense lines contain (Amazon's Fulfillment and Technology & content, which Compustat folds into XSGA/XRD). `(e/enable-fsds!)` turns on a local cache of the SEC's quarterly [Financial Statement Data Sets](https://www.sec.gov/data-research/sec-markets-data/financial-statement-data-sets) (downloaded on first use into `~/.edgarjure/fsds`, shared across all companies), and the `:compustat` income view then uses statement placement to guard the D&A strip and the R&D fold-in (R&D disclosed only in the notes already sits inside another expense line), and extension-tag values as reclassification operands (e.g. AMZN's technology line as `"XRD (Compustat)"`):

```clojure
(e/enable-fsds!)                        ; opt-in; quarter files are 50-130 MB
(e/income "AMZN" :view :compustat)      ; XSGA now includes Fulfillment + Technology,
                                        ; XRD (Compustat) = the technology extension line
(e/disable-fsds!)                       ; turn off (cached quarter files are kept)
```

FSDS operands apply to 10-K periods only. 10-Q queries never trigger FSDS downloads, and the annual rows they use for Q4/LTM are reclassified without FSDS, so a derived Q4 never mixes the two rule regimes.

### Industry Routing

Banks, insurers, and REITs use fundamentally different income statement line items (Net Interest Income, Provision for Credit Losses, Premiums Earned, Rental Revenue, Property Operating Expense). edgarjure auto-detects them from the company's SIC code — banks 6000–6199 and 6712, insurers 6300–6399 and 6411, REITs 6798 (plus real-estate operators 6500–6553) — and switches concept chains. Coverage is partial and grows via the unmapped-concepts feedback loop (below):

```clojure
(e/income "JPM")                        ; auto-routes to bank chains
(e/income "MET")                        ; auto-routes to insurance chains
(e/income "SPG")                        ; auto-routes to REIT chains (SIC 6798)
(e/income "JPM" :industry :standard)    ; force the generic chains
```

### Concept Chains, Coverage, and Benchmarking

The library decides which XBRL tags map to "Revenue", "Net Income", etc. by looking up each label in a list of candidate tags (trying the most common one first, then falling back to alternatives). Chains live in EDN files under `resources/edgar/concepts/` and are exposed both as public vars and through an inspection API:

```clojure
edgar.financials/income-statement-concepts     ; standard chains as data
(e/concepts-for :income :industry :bank)       ; active chains + file metadata
(e/income "AAPL" :concepts my-custom-chains)   ; per-call override

;; Coverage feedback loop: what did the chains miss?
(e/unmapped-concepts :top 20)   ; us-gaap concepts seen in facts but unmatched
(e/save-unmapped-concepts!)     ; persist to ~/.edgarjure/unmapped-concepts.edn
(e/clear-unmapped-concepts!)    ; reset the in-memory registry

;; Quantify agreement with a benchmark (Compustat extract, hand-collected)
(require '[edgar.validation :as validation])
(validation/compare-to-benchmark "AAPL"
  [{:line-item "Revenue" :end "2023-09-30" :val 383285000000}]
  :date-tolerance-days 10)   ; Compustat month-end vs exact 52/53-week dates
;=> {:match-rate 1.0 :matched [...] :mismatched [] :missing []}
```

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

### Panel Datasets

```clojure
(e/panel ["AAPL" "MSFT" "GOOG"] :concept "Assets")
(e/panel ["AAPL" "MSFT" "GOOG"] :concept ["Assets" "NetIncomeLoss"] :form "10-Q")

;; Pivot long → wide: one row per [ticker end] for panel input, one column per concept
(e/pivot some-facts-ds)
```

`e/pivot` keeps one value per cell: the longest window wins, then the latest `:filed`; 10-K quarterly-footnote rows (3/6/9-month windows) are dropped, so 10-K input pivots to annual values only. Units are never mixed — a concept reported in several units gets one `"<concept> (<unit>)"` column per unit. Columns: `:ticker` (panel input) and `:end`, then the concepts alphabetically.

```clojure
(e/pivot (e/panel ["AAPL" "MSFT"] :concept ["Assets" "NetIncomeLoss"] :form "10-K"))
;=> | :ticker | :end       | Assets       | NetIncomeLoss |
;   | AAPL    | 2025-09-27 | 359241000000 | 112010000000  |
;   | AAPL    | 2024-09-28 | 364980000000 |  93736000000  |
;   ...
```

### Form Parsers

`e/obj` dispatches on the filing's form type through the `filing-obj` multimethod; `edgar.api` loads all built-in parsers, and unparsed forms return `{:form ... :raw-html ...}`.

**Forms 3, 4, 5 — Beneficial Ownership (initial, changes, annual):**

```clojure
(-> (e/filing "AAPL" :form "4") e/obj)
;=> {:form "4" :period-of-report "..."
;    :issuer {:cik "0000320193" :name "Apple Inc." :ticker "AAPL"}
;    :reporting-owner {:name "..." :is-officer? true :officer-title "CEO" ...}
;    :reporting-owners [{:name "..." ...}]   ; every owner of a joint filing
;    :transactions [{:type :non-derivative :coding "S" :shares 50000.0
;                    :price 185.50 :acquired-disposed "D" ...}]
;    :holdings []}

(-> (e/filing "AAPL" :form "3") e/obj)
;=> {:form "3" ...
;    :transactions []
;    :holdings [{:type :derivative :security-title "Restricted Stock Unit"
;                :underlying-title "Common Stock" :underlying-shares 301040.0 ...}]}
```

All three (plus their `/A` amendments) share the ownership XML schema and one parser (`edgar.forms.ownership`). Form 3 carries initial holdings, Form 4 transactions, Form 5 the annual statement with both. `:reporting-owner` is the first owner; amendments also carry `:date-of-original-submission`.

**Schedules 13D/13G — >5% Beneficial Ownership (XML era, filed Dec 2024+):**

```clojure
(-> (e/filing "AAPL" :form "SCHEDULE 13G") e/obj)
;=> {:form "SCHEDULE 13G" :schedule :13g
;    :security-class "Common Stock" :event-date "03/31/2026" :rules ["Rule 13d-1(b)"]
;    :issuer {:cik "0000320193" :name "Apple Inc" :cusip "037833100"}
;    :reporting-persons [{:name "..." :aggregate-owned 1.099E9
;                         :percent-of-class 7.48 :types ["IA"] ...}]}
```

The 13D and 13G XML schemas spell their fields differently; both normalize to the same shape, and activist group filings yield one entry per reporting person. `:rules` and `:types` are always vectors (`[]` when absent) — a filer may list several (`["IA" "PN"]`). Legacy `SC 13D`/`SC 13G` filings (pre-Dec-2024, HTML/text) fall through to the default raw-HTML result.

**13F-HR — Institutional Holdings (XML era, 2013 Q2+):**

```clojure
(-> (e/filing "BRK-A" :form "13F-HR") e/obj)
;=> {:form "13F-HR"                      ; "13F-HR/A" for amendments
;    :period-of-report "2026-06-30"
;    :report-type "13F HOLDINGS REPORT"
;    :is-amendment? false
;    :table-entry-count 89
;    :table-value-total 299253556246
;    :manager {:name "..." :street "..." :city "..." :state "..." :zip "..."}
;    :holdings <tech.ml.dataset>         ; :name :cusip :value :shares :put-call :voting-sole ...
;    :total-value 299253556246}          ; sum of the :value column
```

Also returned: `:form13f-file-number`, `:other-managers-count`, and on amendments `:amendment-number` and `:amendment-type`. Dollar values are as filed — whole dollars in filings made from January 2023 on, thousands of dollars before.

### Bulk Downloads

```clojure
(require '[edgar.download :as download])

(download/download-filings! "AAPL" "/data/filings" :form "10-K" :limit 5)
(download/download-filings! "AAPL" "/data/filings" :form "10-K" :skip-existing? true)

;; Batch with bounded parallelism
(download/download-batch! ["AAPL" "MSFT" "GOOG"] "/data/filings"
                           :form "10-K" :limit 3 :parallelism 4)

;; Raw quarterly index files, written to dir/<year>/QTR<q>/<type>.idx
(download/download-index! 2020 2024 "/data/index" :type "master")   ; "company" (default) | "form" | "master"

;; All functions return structured envelopes:
;; {:status :ok :path "..."} or {:status :skipped ...} or {:status :error ...}
```

## Validation against Compustat

How close is edgarjure to a commercial database? The method, item mappings, and per-context results are in `examples/compustat_validation.clj`; all rates are matches within 1% against a licensed Compustat sample.

**Standardized view.** A 19-firm study (13 industrials/tech, 3 banks, 3 insurers) found annual core line items for FY2010–2015 match at **89.4%** overall (Total Assets 97.7%, Net Income 96.2%, Stockholders Equity 94.7%, Operating Cash Flow 93.2%), and quarterly derived values match Compustat's single-quarter items at **98.6%** (quarterly Total Assets 100%). Across ~45 line items on all three statements, the strongest secondary items are Total Liabilities & Equity (98.5%), Investing/Financing Cash Flow (96.2%), Goodwill (95.2%), derived Total Equity (94.7%) and Working Capital (90%). Expense-classification items (COGS, SG&A, R&D, Operating Income) match only ~15–18% as reported, because Compustat reclassifies them (e.g. D&A stripped out) — the gap the `:compustat` view addresses.

**`:compustat` view.** Measured 2026-10-20 on the current rules against a current-vintage Compustat extract: 13 industrials (AAPL MSFT IBM INTC KO PEP PG WMT CAT CVX HON UPS CSCO), FY2009–2025, 221 firm-years, FSDS enabled. The 2026-10 rule additions were developed on this sample, so these are in-sample rates.

- Income: Revenue **96%**, XRD **90%**, DP **80%**, XSGA **66%**, COGS **63%**, XOPR **63%**, Gross Profit 51%, OIADP 35%, OIBDP 30%. Reported separately (not among the 13): AMZN COGS 15/17, XSGA 17/17, XRD 17/17 (fulfillment and technology lines come from FSDS extension tags); GE's conglomerate format matches poorly.
- Income, out-of-sample (2026-07, FY2016+, ~190 firm-years the rules were never fitted on): COGS **54%** (as reported 1%), XSGA **43%** (16%), OIADP **34%** (19%), OIBDP 33%, DP 76%.
- Balance sheet: SEQ/CEQ/TEQ **98%**, RE **98%** (the raw retained-earnings tag: 12% — Compustat RE includes accumulated OCI), MIBN **96%**, MIBT 94%, DLTT **87%**, MIB **83%**, RECT 71%, CHE 69%, DLC 57%, PSTK 34%. Equity items hold up for banks and insurers too (RE, SEQ and TEQ 100%); classified-balance-sheet items (debt/receivables/cash buckets) do not apply to financial-format filers and REITs.
- Excise taxes (MO, out of sample): Gross Profit 53%, Revenue (Compustat) 59%.

Part of the gain comes from first-reported values: Compustat keeps what the fiscal year's own 10-K reported, while later 10-Ks restate comparatives. The remainder is mostly footnote-level allocation that the companyfacts API doesn't carry (partial D&A splits between COGS and SG&A, R&D embedded in COGS, special items only visible in text). Per-share items need split-vintage alignment (AJEX).

> **Disclaimer.** Compustat is a registered trademark of S&P Global Inc.
> edgarjure is an independent open-source project with no affiliation to,
> endorsement by, or reimplementation of S&P Global products. The
> `:compustat` view is an *approximation* computed entirely from public SEC
> filings, built by empirically comparing edgarjure's output against a
> licensed Compustat sample — the match rates above quantify exactly how
> close (and how far) that approximation is. It is not a reimplementation of,
> or substitute for, licensed Compustat data, whose values rest on manual
> analyst review that no automated mapping reproduces.

## Namespace Reference

`edgar.core` (HTTP, caching, rate limiting) sits at the bottom; `edgar.company`, `edgar.filings`, and `edgar.filing` build on it, with extraction, tables, form parsers, and downloads on top of `edgar.filing`. `edgar.xbrl` feeds `edgar.financials` (which uses `edgar.reclass` and, when enabled, `edgar.fsds`) and `edgar.dataset`. `edgar.api` wraps all of them.

| Namespace | Role |
|---|---|
| `edgar.api` | Unified entry point — wraps all namespaces; core query functions Malli-validated |
| `edgar.core` | HTTP client, in-memory caches (JSON: 5 min metadata / 1 hr XBRL; raw documents: 64 entries, 1 hr), opt-in cross-session disk cache, exponential backoff retry, Bucket4j rate limiter (10 req/s) |
| `edgar.schema` | Malli schemas and the `validate!` helper used by the core `edgar.api` query functions |
| `edgar.company` | Ticker↔CIK resolution, entity search, shaped metadata |
| `edgar.filings` | Filing index queries, pagination for large filers, amendment handling, daily and quarterly master indexes, EFTS full-text search |
| `edgar.filing` | Individual filing content, accession number lookup, filing index, save to disk, `filing-obj` multimethod, exhibit API |
| `edgar.download` | Bulk downloader — single company and batch, structured result envelopes |
| `edgar.xbrl` | XBRL company-facts → `tech.ml.dataset` with labels; concept discovery; cross-sectional frames |
| `edgar.financials` | Income statement, balance sheet, cash flow; four views; line-item resolution; imputation; industry routing; quarterly/LTM; `:as-of` |
| `edgar.reclass` | Reclassification rule engine — EDN rulesets approximating Compustat item definitions (`:view :compustat`) |
| `edgar.validation` | Match-rate harness against benchmark datasets (Compustat extracts, hand-collected figures) |
| `edgar.fsds` | SEC Financial Statement Data Sets (DERA quarterly dumps) — download, load as datasets, placement/extension queries, the `e/enable-fsds!` cache |
| `edgar.extract` | Item-section extraction (10-K, 10-Q, 8-K; HTML and plain text); batch mode |
| `edgar.dataset` | Panel datasets, filing-list datasets, pivot helpers |
| `edgar.tables` | HTML table extraction → `tech.ml.dataset` |
| `edgar.forms` | Central loader for the built-in form parsers (required by `edgar.api`) |
| `edgar.forms.ownership` | Forms 3/4/5 parser (beneficial ownership: initial holdings, insider trades, annual statements; includes `/A` amendments) |
| `edgar.forms.form13f` | 13F-HR parser (institutional holdings, XML era 2013 Q2+) |
| `edgar.forms.schedule13` | Schedule 13D/13G parser (>5% beneficial ownership, XML era Dec 2024+) |
| `edgar.forms.xml` | Shared XML helpers for the form parsers (namespace-prefix-tolerant tag matching; external entities disabled) |
| `edgar.util` | Internal helpers (date parsing, period windows, restatement dedup) — not a public API |

## Conventions

- **CIKs come back as zero-padded 10-digit strings** — in every returned map and dataset (`"0000320193"`), so results join directly; CIK inputs may be strings (with or without leading zeros) or integers
- **Options as keywords or a single map** — `(e/income "AAPL" :form "10-Q")` and `(e/income "AAPL" {:form "10-Q"})` are equivalent
- **Long-name aliases** — `e/income-statement`, `e/balance-sheet`, `e/cash-flow`, and `e/search-companies` are the same vars as their short names
- **`:n` picks the nth item, 0-indexed** (`e/filing`, `e/tables`) — out of range returns nil, never an exception
- **Invalid arguments throw** `ex-info` with `:type ::edgar.schema/invalid-args` — filing-content functions also check the filing map (`:cik` and `:accessionNumber`)

## Rate Limits and Caching

SEC enforces a `User-Agent` header and a rate limit of ~10 requests/second. edgarjure handles both automatically: `e/init!` sets the header (a request made before it throws an error pointing to `e/init!`), and a Bucket4j token-bucket rate limiter paces requests. Failed requests retry with exponential backoff on 429/5xx (initial attempt plus up to 3 retries, sleeping 2s → 4s → 8s between attempts).

All HTTP responses are cached in memory: JSON for 5 minutes (metadata) or 1 hour (XBRL facts); raw documents (filing HTML, indexes) in a bounded cache (64 entries, 1 hour), so repeated content access on the same filing hits the network once. The 30–60 MB quarterly index files bypass the in-memory cache. For cross-session persistence — batch jobs, repeated research runs — enable the opt-in disk cache:

```clojure
(e/enable-disk-cache!)          ; ~/.edgarjure/http-cache
(e/disk-cache-stats)            ;=> {:dir "..." :entries 42 :bytes 12345678}
(e/clear-disk-cache!)           ; delete all entries
(e/disable-disk-cache!)
```

Disk entries expire after 24 hours for JSON endpoints and the daily-changing full-index/daily-index files, and after 30 days for filing documents (published filings are immutable); both are configurable (`:dir`, `:ttl-json-ms`, `:ttl-raw-ms`).

## Development

```bash
# Start REPL (random port, written to .nrepl-port)
clj -M:nrepl

# Run offline unit tests (298 tests, 1620 assertions, no network)
clj -M:test

# Run live integration tests (36 tests, 186 assertions; manual only, requires network)
clj -M:test-integration
```

```clojure
(require '[edgar.api :as e]
         '[tech.v3.dataset :as ds])

(e/init! "Your Name your@email.com")

;; Clear the in-memory cache when testing fresh fetches
(edgar.core/clear-cache!)
```

## License

Apache-2.0 — see [LICENSE](LICENSE).
