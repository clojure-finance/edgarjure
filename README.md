# edgarjure

A Clojure library for accessing and analysing SEC EDGAR filings. Talks directly to SEC's public JSON/HTTP APIs ? no API keys, no paid services.

The Clojure ecosystem's equivalent of Python's `edgartools`, `sec-edgar-downloader`, `secedgar`, and `edgar-crawler`, combined into a single coherent stack built on `tech.ml.dataset`.

## Features

- **Company & CIK lookup** ? ticker?CIK resolution, company search, rich shaped metadata (SIC, addresses, fiscal year end, state of incorporation)
- **Filing index queries** ? by company, date range, form type; full pagination for large filers (>1000 filings); amendment filtering
- **Daily filing index** ? all SEC filings submitted on a given date, lazily paginated; optional form filter and predicate
- **Filing content access** ? HTML, plain text, individual attachments
- **Accession number lookup** ? hydrate a filing map directly from a cited accession number
- **Bulk download** ? single company or batch (bounded parallelism), skip-existing, structured result envelopes
- **XBRL / company-facts** ? `tech.ml.dataset` with `:label` and `:description` columns
- **XBRL concept discovery** ? `(e/concepts "AAPL")` ? dataset of all available concepts with labels
- **Financial statements** ? income statement, balance sheet, cash flow; concept fallback chains, restatement deduplication, long or wide output
- **NLP item-section extraction** ? 10-K, 10-Q, 8-K items with full section bodies; strip numeric tables; batch mode
- **Form parsers** ? Form 4 (insider trades), 13F-HR (institutional holdings); central `edgar.forms` loader
- **HTML table extraction** ? `(e/tables filing)` ? seq of `tech.ml.dataset`; infers numeric types, deduplicates column names
- **Dataset utilities** ? panel datasets, cross-sectional snapshots, pivot

## Requirements

- Clojure 1.12+
- Java 21+

## Quick Start

```clojure
;; deps.edn ? add as a git dependency (replace SHA)
{:deps {com.github.clojure-finance/edgarjure
        {:git/url "https://github.com/clojure-finance/edgarjure"
         :git/sha "LATEST_SHA"}}}
```

```clojure
(require '[edgar.core       :as core]
         '[edgar.company    :as company]
         '[edgar.filings    :as filings]
         '[edgar.filing     :as filing]
         '[edgar.xbrl       :as xbrl]
         '[edgar.financials :as financials]
         '[edgar.extract    :as extract])

;; SEC requires a User-Agent header ? call this once at startup
(core/set-identity! "Your Name your@email.com")

;; Resolve ticker ? CIK
(company/ticker->cik "AAPL")   ;=> "0000320193"

;; Get the latest 10-K (keyword args throughout)
(def f (filings/get-filing "AAPL" :form "10-K"))

;; Read its plain text
(filing/filing-text f)

;; Extract MD&A section (Item 7)
;; Returns {:title "..." :text "...20k chars..." :method :html-heading-boundaries}
(extract/extract-item f "7")

;; XBRL facts as a dataset (~24k rows for AAPL), sorted :end descending
;; Columns: :taxonomy :concept :label :description :unit :end :val :accn :fy :fp :form :filed :frame
(def facts (xbrl/get-facts-dataset "0000320193"))

;; Filter at fetch time
(xbrl/get-facts-dataset "0000320193" :concept "Assets" :form "10-K")

;; Discover available concepts
(xbrl/get-concepts "0000320193")
;=> dataset [:taxonomy :concept :label :description]

;; Income statement ? accepts ticker or CIK; concept fallback chains + restatement dedup
(financials/income-statement "AAPL")
(financials/income-statement "AAPL" :shape :wide)   ; pivoted, one column per line item

;; All three financial statements at once
(financials/get-financials "AAPL")
;=> {:income-statement ds, :balance-sheet ds, :cash-flow ds}
(financials/get-financials "AAPL" :shape :wide)
```

### Unified API (`edgar.api`)

For exploratory work the `edgar.api` namespace wraps everything with consistent defaults:

```clojure
(require '[edgar.api :as e])
(e/init! "Your Name your@email.com")

;; Company
(e/cik "AAPL")                ; => "0000320193"
(e/company "AAPL")            ; full raw SEC submissions map
(e/company-name "AAPL")
(e/company-metadata "AAPL")   ; shaped map: :sic :state-of-inc :fiscal-year-end :addresses ...
(e/search "apple" :limit 5)

;; Filings ? amendments excluded by default
(e/filing "AAPL" :form "10-K")                        ; latest non-amended
(e/filing "AAPL" :form "10-K" :n 2)                   ; 3rd latest (0-indexed)
(e/filings "AAPL" :form "10-K" :include-amends? true) ; include 10-K/A
(e/latest-effective-filing "AAPL" :form "10-K")        ; original or amendment, whichever is newer

;; Daily filing index ? lazy seq of all filings submitted on a date
(e/daily-filings "2026-03-10")
(e/daily-filings "2026-03-10" :form "8-K")
(e/daily-filings "2026-03-10" :filter-fn #(seq (:items %)))
(e/daily-filings (java.time.LocalDate/of 2026 3 10) :form "10-K")

;; Accession number direct lookup ? for research reproducibility
(def f (e/filing-by-accession "0000320193-23-000106"))
(e/text f)
(e/items f :only #{"7" "1A"})

;; Filing content
(def f (e/filing "AAPL" :form "10-K"))
(e/html f)
(e/text f)
(e/item  f "7")                        ; {:title "MD&A..." :text "...20k chars..." :method ...}
(e/items f :only #{"7" "1A"} :remove-tables? true)

;; HTML table extraction ? returns seq of tech.ml.dataset
(e/tables f)
(e/tables f :min-rows 5 :min-cols 3)
(e/tables f :nth 0)                    ; first table only

;; XBRL facts ? columns include :label and :description
(e/facts "AAPL" :concept "Assets")
(e/facts "AAPL" :concept ["Assets" "NetIncomeLoss"] :form "10-K")

;; Concept discovery
(e/concepts "AAPL")   ; => dataset [:taxonomy :concept :label :description]

;; Cross-sectional frame ? defaults: taxonomy=us-gaap, unit=USD
(e/frame "Assets" "CY2023Q4I")
(e/frame "SharesOutstanding" "CY2023Q4I" :unit "shares")

;; Financial statements ? concept fallback chains, restatement dedup, long or wide output
(e/income   "AAPL")
(e/income   "AAPL" :shape :wide)
(e/balance  "AAPL" :form "10-Q")
(e/cashflow "AAPL")
(e/financials "AAPL")              ; => {:income ds :balance ds :cashflow ds}
(e/financials "AAPL" :shape :wide)

;; Panel ? :concept accepts string or collection
(e/panel ["AAPL" "MSFT" "GOOG"] :concept ["Assets" "NetIncomeLoss"])

;; Pivot long ? wide, returns tech.ml.dataset directly
(e/pivot some-facts-ds)
```

### Form 4 ? Insider Trades

```clojure
(require '[edgar.forms.form4]          ; side-effectful: registers filing-obj "4"
         '[edgar.filings :as filings]
         '[edgar.filing :as filing])

(-> (filings/get-filing "AAPL" :form "4")
    filing/filing-obj)
;=> {:form "4"
;    :issuer {:cik "0000320193" :name "Apple Inc." :ticker "AAPL"}
;    :reporting-owner {:name "..." :is-officer? true :officer-title "CEO" ...}
;    :transactions [{:type :non-derivative :date "2024-01-15" :coding "S"
;                    :shares 50000.0 :price 185.50 :acquired-disposed "D" ...}]}
```

### 13F-HR ? Institutional Holdings

```clojure
;; Load all built-in parsers at once (recommended)
(require '[edgar.forms])

;; Or individually
(require '[edgar.forms.form13f])

(-> (filings/get-filing "BRK-A" :form "13F-HR")
    filing/filing-obj)
;=> {:form             "13F-HR"
;    :period-of-report "2024-03-31"
;    :report-type      "13F HOLDINGS REPORT"
;    :is-amendment?    false
;    :manager          {:name "BERKSHIRE HATHAWAY INC" :street "3555 FARNAM ST" ...}
;    :holdings         <tech.ml.dataset>  ; columns: :name :cusip :title :value :shares ...
;    :total-value      12345678}
```

### Rich Company Metadata

```clojure
(e/company-metadata "AAPL")
;=> {:cik             "0000320193"
;    :name            "Apple Inc."
;    :tickers         ["AAPL"]
;    :exchanges       ["Nasdaq"]
;    :sic             "3571"
;    :sic-description "Electronic Computers"
;    :entity-type     "operating"
;    :category        "Large accelerated filer"
;    :state-of-inc    "CA"
;    :fiscal-year-end "0926"
;    :ein             "942404110"
;    :phone           "(408) 996-1010"
;    :addresses       {:business {:street1 "ONE APPLE PARK WAY" :city "CUPERTINO"
;                                 :state "CA" :zip "95014" :foreign? false}
;                      :mailing  {...}}
;    :former-names    []}
```

### Financial Statement Normalization

```clojure
;; Long format (default) ? one row per concept+period
(e/income "AAPL")
;=> dataset columns: :concept :label :unit :end :val :accn :fy :fp :form :filed :line-item

;; Wide format ? one row per period, one column per line item
(e/income "AAPL" :shape :wide)
;=> dataset columns: :end | Revenue | Cost of Revenue | Gross Profit | ...

;; Concept vectors are public vars ? override for non-standard filers
edgar.financials/income-statement-concepts    ; vector of [label concept fallback1 fallback2 ...]
edgar.financials/balance-sheet-concepts
edgar.financials/cash-flow-concepts
```

## Namespace Overview

| Namespace | Role |
|---|---|
| `edgar.core` | HTTP client, TTL cache, exponential backoff retry, Bucket4j rate limiter (10 req/s), SEC base URLs |
| `edgar.api` | unified power-user entry point (wraps all namespaces below) |
| `edgar.company` | ticker?CIK resolution, company metadata, rich shaped metadata |
| `edgar.filings` | filing index queries, pagination, amendment handling, quarterly/daily full-index, EFTS search |
| `edgar.filing` | individual filing content, accession number lookup, save to disk, `filing-obj` multimethod |
| `edgar.download` | bulk downloader ? single company + batch, structured result envelopes |
| `edgar.xbrl` | XBRL company-facts ? `tech.ml.dataset` (with labels); concept discovery; cross-sectional frames |
| `edgar.financials` | income statement, balance sheet, cash flow; concept fallback chains; restatement dedup; wide/long output |
| `edgar.extract` | NLP item-section extraction (10-K/10-Q/8-K); batch mode |
| `edgar.dataset` | panel datasets, cross-sectional snapshots, pivot helpers |
| `edgar.tables` | HTML table extraction ? seq of `tech.ml.dataset` |
| `edgar.forms` | central loader ? `(require '[edgar.forms])` activates all built-in parsers |
| `edgar.forms.form4` | Form 4 parser ? insider trades |
| `edgar.forms.form13f` | 13F-HR parser ? institutional holdings (XML-era, post-2013Q2) |

## API Conventions

- **Keyword args throughout** ? no positional `form` or `type` args anywhere
- **Ticker or CIK interchangeably** ? all functions resolve via `company/company-cik`
- **`:concept` accepts string or collection** ? coerced to a set internally
- **Datasets always return `tech.ml.dataset`** ? never seq-of-maps
- **Amendments excluded by default** ? pass `:include-amends? true` to `e/filings` / `e/filing` to include `10-K/A` etc.
- **Item extraction returns full section bodies** ? `{item-id {:title "..." :text "..." :method ...}}`
- **Download results are structured envelopes** ? `{:status :ok/:skipped/:error ...}`
- **Form parsers must be required to activate** ? use `(require '[edgar.forms])` to load all at once

## SEC Rate Limits

SEC enforces a `User-Agent` header on all requests and a rate limit of ~10 requests/second. `edgarjure` handles both automatically via `edgar.core/set-identity!` and a Bucket4j token-bucket rate limiter. JSON responses are cached in memory (5 min for metadata, 1 hr for XBRL facts). Requests retry automatically on 429/5xx with exponential backoff.

## Development

```bash
# Start REPL on port 7888
clj -M:nrepl

# Run tests
clj -M:test

# In the REPL
(edgar.core/set-identity! "Your Name your@email.com")

;; Clear cache when testing fresh fetches
(edgar.core/clear-cache!)
```

## License

EPL-2.0 ? see [LICENSE](LICENSE).
