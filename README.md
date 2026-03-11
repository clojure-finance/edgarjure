# edgarjure

**SEC EDGAR as a composable Clojure data system.**

Public companies file thousands of pages of financial data with the SEC every day — earnings reports, insider trades, institutional holdings, risk disclosures. Getting at this data programmatically means wrestling with inconsistent HTML, paginated JSON APIs, XBRL taxonomies, and rate limits. In Python you'd cobble together `edgartools`, `sec-edgar-downloader`, `secedgar`, and `edgar-crawler`. edgarjure replaces all four with a single coherent library built on `tech.ml.dataset`.

Everything flows through keyword args, returns immutable data, and composes naturally in the REPL. Filings are plain maps. Facts are columnar datasets. Financial statements come with concept fallback chains, restatement deduplication, and point-in-time filtering for look-ahead-safe backtesting. No API keys, no paid services — just SEC's public endpoints.

## What You Can Do

**Look up any public company** and get structured metadata — SIC codes, addresses, fiscal year end, state of incorporation.

**Query filing history** with full pagination (even for filers with 10,000+ filings), date filtering, and automatic amendment handling. Access any filing by its accession number for reproducible research.

**Read filings** as HTML, plain text, or structured data. Extract specific item sections (MD&A, Risk Factors) with full section bodies — not just headings. Parse HTML tables into datasets. Pull exhibits and XBRL linkbase documents.

**Work with XBRL data** as `tech.ml.dataset` columns. Get company facts with human-readable labels and descriptions. Discover what concepts exist before querying. Build cross-sectional snapshots across all filers for a given period.

**Build normalized financial statements** — income, balance sheet, cash flow — with concept fallback chains (e.g., three different revenue concepts tried in priority order), duration/instant filtering, and restatement deduplication. Output in long or wide format.

**Run look-ahead-safe backtests** with the `:as-of` option. Every financial statement function and panel query accepts a date cutoff that excludes filings submitted after that date — giving you exactly the data a market participant had available, no more.

**Parse structured forms** — Form 4 insider trades and 13F-HR institutional holdings come back as typed maps and datasets, ready for analysis.

**Download filings in bulk** — single company or batch, with bounded parallelism, skip-existing, exponential backoff, and structured result envelopes.

## Requirements

- Clojure 1.12+
- Java 21+

## Installation

```clojure
;; deps.edn — add as a git dependency (replace SHA with latest)
{:deps {com.github.clojure-finance/edgarjure
        {:git/url "https://github.com/clojure-finance/edgarjure"
         :git/sha "LATEST_SHA"}}}
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

;; Extract the MD&A section
(e/item f "7")
;=> {:title "Management's Discussion..." :text "...20k chars..." :method :html-heading-boundaries}

;; XBRL facts as a dataset (~24k rows for AAPL)
(e/facts "AAPL" :concept "Assets" :form "10-K")

;; Income statement with automatic concept resolution and restatement dedup
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

;; Or use the normalized income statement (handles concept fallbacks + restatement dedup)
(-> (e/income "AAPL" :shape :wide)
    (ds/select-columns [:end "Net Income"])
    (ds/head 3))
```

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

### Filing Content

```clojure
(def f (e/filing "AAPL" :form "10-K"))

(e/html f)                                  ; raw HTML
(e/text f)                                  ; plain text
(e/item  f "7")                             ; single item section
(e/items f :only #{"7" "1A"} :remove-tables? true)  ; multiple items

;; 10-Q items use Roman-numeral IDs — matched automatically
(e/items f :only #{"I-1" "II-1A"})

;; HTML tables → seq of tech.ml.dataset
(e/tables f)
(e/tables f :min-rows 5 :min-cols 3)
(e/tables f :nth 0)

;; Exhibits and XBRL documents
(e/exhibits f)              ; seq of exhibit metadata maps
(e/exhibit f "EX-21")       ; first match, or nil
(e/xbrl-docs f)             ; XBRL linkbase documents

;; Structured parse via multimethod dispatch on :form
(e/obj f)

;; Save to disk
(e/save! f "/data/filings")
(e/save-all! f "/data/filings")
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

Income statement, balance sheet, and cash flow — with concept fallback chains, restatement deduplication, and duration/instant filtering built in.

```clojure
(e/income   "AAPL")                    ; long format (default)
(e/income   "AAPL" :shape :wide)       ; pivoted: one column per line item
(e/balance  "AAPL" :form "10-Q")
(e/cashflow "AAPL")

;; All three at once
(e/financials "AAPL")
;=> {:income ds :balance ds :cashflow ds}
(e/financials "AAPL" :shape :wide)
```

Concept fallback chains are public vars — override for non-standard filers:

```clojure
edgar.financials/income-statement-concepts   ; vector of [label primary fallback1 ...]
edgar.financials/balance-sheet-concepts
edgar.financials/cash-flow-concepts
```

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
| `edgar.financials` | Income statement, balance sheet, cash flow; concept fallback chains; restatement dedup; `:as-of` |
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

# Run tests (offline only — no network calls)
clj -M:test
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
