# edgarjure

[![Clojars Project](https://img.shields.io/clojars/v/com.github.clojure-finance/edgarjure.svg)](https://clojars.org/com.github.clojure-finance/edgarjure)
[![CI](https://github.com/clojure-finance/edgarjure/actions/workflows/ci.yml/badge.svg)](https://github.com/clojure-finance/edgarjure/actions/workflows/ci.yml)
[![cljdoc](https://cljdoc.org/badge/com.github.clojure-finance/edgarjure)](https://cljdoc.org/d/com.github.clojure-finance/edgarjure)

**A Clojure library for SEC EDGAR — filings, financials, and XBRL data, ready for research.**

Every public company in the U.S. files its financials, insider trades, institutional holdings, and risk disclosures with the SEC. That's decades of structured data covering thousands of firms — but getting at it programmatically means dealing with paginated APIs, inconsistent HTML, XBRL taxonomies, and rate limits. The Python ecosystem has several good libraries for different parts of this problem. edgarjure brings all of that functionality into a single Clojure stack built on `tech.ml.dataset`.

Pull a company's income statement in two lines. Screen an XBRL line item across every filer in a single call. Extract the full text of a 10-K's MD&A section. Download a decade of filings for a universe of tickers overnight. Financial statements are normalized — meaning the library maps the many different XBRL concept names companies use for the same line item (e.g., three different "Revenue" tags across accounting standard changes) to a single canonical label, and automatically picks the most recent filing when restatements exist. A point-in-time mode lets you see exactly the data that was available on any historical date, so your backtests and event studies are free of look-ahead bias. No API keys, no paid services — just SEC's public endpoints.

## What You Can Do

**Pull financial statements** — income statement, balance sheet, and cash flow, with automatic line-item resolution across different XBRL tags, restatement deduplication, and long or wide output. Override the mappings for non-standard filers. For 10-Q data, quarterly and trailing-twelve-month values are derived automatically from YTD figures.

**Backtest without look-ahead bias** — the `:as-of` option on every financial statement and panel query restricts data to what was actually filed on or before a given date. Essential for event studies, strategy backtests, and panel regressions.

**Get XBRL financials as datasets** — company facts come as columnar datasets with human-readable labels. Discover available line items before querying. Build cross-sectional snapshots across all filers for a given period — useful for industry screens and peer comparisons.

**Look up any public company** — by ticker or CIK, with structured metadata including SIC codes, fiscal year end, state of incorporation, and mailing addresses.

**Query filing history** — filter by form type, date range, or full-text search. Pagination is automatic, even for filers with 10,000+ filings. Amendments are handled transparently.

**Read filings** — as HTML, plain text, or structured data. Extract specific sections (MD&A, Risk Factors, any 10-K/10-Q item) with full section bodies. Parse HTML tables into datasets. Pull exhibits and XBRL linkbase documents.

**Parse insider trades and institutional holdings** — Form 4 and 13F-HR come back as structured maps and datasets, ready for ownership analysis.

**Download in bulk** — single company or batch, with bounded parallelism, skip-existing, and structured result envelopes reporting success, skip, or error per filing.

## Requirements

- Clojure 1.12+
- Java 21+

## Installation

```clojure
;; deps.edn
{:deps {com.github.clojure-finance/edgarjure {:mvn/version "0.1.8"}}}
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

`e/frame` pulls a single XBRL line item for **every SEC filer** in a given fiscal year — in one API call. That makes peer comparisons fast:

```clojure
;; Pull FY2023 Revenue and Gross Profit for ALL filers — just two API calls
(def revenue  (e/frame "Revenues" "CY2023"))
(def gross-pr (e/frame "GrossProfit" "CY2023"))

;; Filter to a peer group
(def peers #{"0000320193" "0000789019" "0001652044"   ; AAPL, MSFT, GOOG
             "0001018724" "0001326801" "0000047217"   ; AMZN, META, HPQ
             "0001571996"})                            ; DELL

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
;=> | :company        | Revenue        | Gross Profit   | :gross-margin |
;   | MICROSOFT CORP  | 211915000000   | 146052000000   | 0.689         |
;   | ALPHABET INC    | 307394000000   | 174062000000   | 0.566         |
;   | APPLE INC       | 383285000000   | 169148000000   | 0.441         |
;   | ...
```

The `revenue` and `gross-pr` datasets already contain every filer — to screen
the full universe (e.g., by SIC code) just change the filter. Note that looking
up SIC codes via `e/company-metadata` requires one API call per company, so
filtering thousands of filers that way may take a few minutes at the SEC's
10 requests/second rate limit.

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

;; Batch extraction across many filings — saves results to disk as JSON
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
(e/income   "AAPL" :form "10-Q")        ; adds :val-q and :val-ltm columns
(e/cashflow "AAPL" :form "10-Q")        ; single-quarter + trailing 12 months
```

The library decides which XBRL tags map to "Revenue", "Net Income", etc. by looking up each label in a list of candidate tags (trying the most common one first, then falling back to alternatives). These lists are exposed as public vars, so you can inspect them or swap in your own if a company uses non-standard tags:

```clojure
edgar.financials/income-statement-concepts   ; e.g. ["Revenue" "RevenueFromContract..." "Revenues" "SalesRevenueNet"]
edgar.financials/balance-sheet-concepts
edgar.financials/cash-flow-concepts
```

For 10-Q queries on income statement and cash flow (flow variables), the long-format output includes two derived columns: `:val-q` is the single-quarter value computed by subtracting the prior cumulative YTD, and `:val-ltm` is the trailing twelve months computed as the sum of four consecutive quarter values. Both use SEC's `:fy` and `:fp` fields for fiscal year sequencing, so they handle non-calendar fiscal years correctly. Balance sheet queries and 10-K queries are unaffected.

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

**Form 4 — Insider Trades:**

```clojure
(-> (e/filing "AAPL" :form "4") e/obj)
;=> {:form "4"
;    :issuer {:cik "0000320193" :name "Apple Inc." :ticker "AAPL"}
;    :reporting-owner {:name "..." :is-officer? true :officer-title "CEO" ...}
;    :transactions [{:type :non-derivative :coding "S" :shares 50000.0
;                    :price 185.50 :acquired-disposed "D" ...}]}
```

**13F-HR — Institutional Holdings:**

```clojure
(-> (e/filing "BRK-A" :form "13F-HR") e/obj)
;=> {:form "13F-HR"
;    :period-of-report "2024-03-31"
;    :manager {:name "BERKSHIRE HATHAWAY INC" ...}
;    :holdings <tech.ml.dataset>   ; :name :cusip :value :shares ...
;    :total-value 12345678}
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
edgar.core            HTTP client, TTL cache, retry, rate limiter
    │
    ├── edgar.schema        Malli schemas + validation
    ├── edgar.api           Unified entry point (wraps everything below)
    ├── edgar.company       Ticker↔CIK resolution, metadata
    ├── edgar.filings       Filing index queries, pagination, amendments, daily index
    │       └── edgar.filing        Filing content, accession lookup, exhibits
    │               ├── edgar.download      Bulk save to disk
    │               ├── edgar.extract       NLP item-section extraction
    │               ├── edgar.tables        HTML table → dataset
    │               └── edgar.forms/        Form parsers (Form 4, 13F-HR)
    ├── edgar.xbrl          Company facts → dataset, concept discovery, frames
    │       ├── edgar.financials    Normalized financial statements
    │       └── edgar.dataset       Panel datasets, pivot, cross-sectional
    └──
```

## Namespace Reference

| Namespace | Role |
|---|---|
| `edgar.api` | Unified entry point — wraps all namespaces; Malli-validated |
| `edgar.core` | HTTP client, TTL cache (5 min metadata / 1 hr XBRL), exponential backoff retry, Bucket4j rate limiter (10 req/s) |
| `edgar.schema` | Malli schemas and `validate!` helper for all public API functions |
| `edgar.company` | Ticker↔CIK resolution, company search, shaped metadata |
| `edgar.filings` | Filing index queries, pagination for large filers, amendment handling, daily/quarterly index, EFTS search |
| `edgar.filing` | Individual filing content, accession number lookup, save to disk, `filing-obj` multimethod, exhibit API |
| `edgar.download` | Bulk downloader — single company and batch, structured result envelopes |
| `edgar.xbrl` | XBRL company-facts → `tech.ml.dataset` with labels; concept discovery; cross-sectional frames |
| `edgar.financials` | Income statement, balance sheet, cash flow; line-item resolution; restatement dedup; `:as-of` |
| `edgar.extract` | NLP item-section extraction (10-K, 10-Q, 8-K); batch mode |
| `edgar.dataset` | Panel datasets, cross-sectional snapshots, pivot helpers |
| `edgar.tables` | HTML table extraction → `tech.ml.dataset` |
| `edgar.forms` | Central loader — `(require '[edgar.forms])` activates all built-in parsers |
| `edgar.forms.form4` | Form 4 parser (insider trades) |
| `edgar.forms.form13f` | 13F-HR parser (institutional holdings, XML-era post-2013Q2) |

## Conventions

- **Keyword args throughout** — no positional parameters
- **Ticker or CIK interchangeably** — every function resolves via `company-cik`
- **`:concept` accepts string or collection** — coerced to a set internally
- **Amendments excluded by default** — pass `:include-amends? true` to include `10-K/A` etc.
- **Datasets always return `tech.ml.dataset`** — never seq-of-maps
- **Form parsers must be required** — `(require '[edgar.forms])` loads all at once
- **Download results are structured envelopes** — `{:status :ok/:skipped/:error ...}`
- **All `edgar.api` functions are Malli-validated** — bad args throw `ex-info` with `:type ::edgar.schema/invalid-args`

## Rate Limits and Caching

SEC enforces a `User-Agent` header and a rate limit of ~10 requests/second. edgarjure handles both automatically: `set-identity!` sets the header, and a Bucket4j token-bucket rate limiter paces requests. JSON responses are cached in memory (5 min for metadata, 1 hr for XBRL facts). Failed requests retry with exponential backoff on 429/5xx (up to 3 attempts, 2s → 4s → 8s).

## Development

```bash
# Start REPL on port 7888
clj -M:nrepl

# Run offline unit tests (139 tests, 633 assertions, no network)
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

EPL-2.0 — see [LICENSE](LICENSE).
