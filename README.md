# edgarjure

A Clojure library for accessing and analysing SEC EDGAR filings. Talks directly to SEC's public JSON/HTTP APIs — no API keys, no paid services.

The Clojure ecosystem's equivalent of Python's `edgartools`, `sec-edgar-downloader`, and `edgar-crawler`, combined into a single coherent stack built on `tech.ml.dataset`.

## Features

- **Company & CIK lookup** — ticker↔CIK resolution, company search
- **Filing index queries** — by company, date range, form type; full pagination for large filers (>1000 filings); amendment filtering
- **Filing content access** — HTML, plain text, individual attachments
- **Accession number lookup** — hydrate a filing map directly from a cited accession number
- **Bulk download** — single company or batch (bounded parallelism), skip-existing, structured result envelopes
- **XBRL / company-facts** → `tech.ml.dataset` with `:label` and `:description` columns
- **XBRL concept discovery** — `(e/concepts "AAPL")` → dataset of all available concepts with labels
- **Financial statements** — income statement, balance sheet, cash flow (long-format datasets)
- **NLP item-section extraction** — 10-K, 10-Q, 8-K items with full section bodies; strip numeric tables; batch mode
- **Form 4 parsing** — insider trades: issuer, reporting owner, non-derivative and derivative transactions
- **Dataset utilities** — panel datasets, cross-sectional snapshots, pivot

## Requirements

- Clojure 1.12+
- Java 21+

## Quick Start

```clojure
;; deps.edn — add as a git dependency (replace SHA)
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

;; SEC requires a User-Agent header — call this once at startup
(core/set-identity! "Your Name your@email.com")

;; Resolve ticker → CIK
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

;; Income statement — accepts ticker or CIK
(financials/income-statement "AAPL")

;; All three financial statements at once
(financials/get-financials "AAPL")
;=> {:income-statement ds, :balance-sheet ds, :cash-flow ds}
```

### Unified API (`edgar.api`)

For exploratory work the `edgar.api` namespace wraps everything with consistent defaults:

```clojure
(require '[edgar.api :as e])
(e/init! "Your Name your@email.com")

;; Company
(e/cik "AAPL")                ; => "0000320193"
(e/company "AAPL")            ; full SEC submissions map
(e/company-name "AAPL")
(e/search "apple" :limit 5)

;; Filings — amendments excluded by default
(e/filing "AAPL" :form "10-K")                        ; latest non-amended
(e/filing "AAPL" :form "10-K" :n 2)                   ; 3rd latest (0-indexed)
(e/filings "AAPL" :form "10-K" :include-amends? true) ; include 10-K/A
(e/latest-effective-filing "AAPL" :form "10-K")        ; original or amendment, whichever is newer

;; Accession number direct lookup — for research reproducibility
(def f (e/filing-by-accession "0000320193-23-000106"))
(e/text f)
(e/items f :only #{"7" "1A"})

;; Filing content
(def f (e/filing "AAPL" :form "10-K"))
(e/html f)
(e/text f)
(e/item  f "7")                        ; {:title "MD&A..." :text "...20k chars..." :method ...}
(e/items f :only #{"7" "1A"} :remove-tables? true)

;; XBRL facts — columns include :label and :description
(e/facts "AAPL" :concept "Assets")
(e/facts "AAPL" :concept ["Assets" "NetIncomeLoss"] :form "10-K")

;; Concept discovery
(e/concepts "AAPL")   ; => dataset [:taxonomy :concept :label :description]

;; Cross-sectional frame — defaults: taxonomy=us-gaap, unit=USD
(e/frame "Assets" "CY2023Q4I")
(e/frame "SharesOutstanding" "CY2023Q4I" :unit "shares")

;; Financial statements
(e/income   "AAPL")
(e/balance  "AAPL" :form "10-Q")
(e/cashflow "AAPL")
(e/financials "AAPL")   ; => {:income ds :balance ds :cashflow ds}

;; Panel — :concept accepts string or collection
(e/panel ["AAPL" "MSFT" "GOOG"] :concept ["Assets" "NetIncomeLoss"])

;; Pivot long → wide, returns tech.ml.dataset directly
(e/pivot some-facts-ds)
```

### Form 4 — Insider Trades

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

## Namespace Overview

| Namespace | Role |
|---|---|
| `edgar.core` | HTTP client, TTL cache, exponential backoff retry, Bucket4j rate limiter (10 req/s), SEC base URLs |
| `edgar.api` | unified power-user entry point (wraps all namespaces below) |
| `edgar.company` | ticker↔CIK resolution, company metadata, search |
| `edgar.filings` | filing index queries, pagination, amendment handling, quarterly full-index, EFTS search |
| `edgar.filing` | individual filing content, accession number lookup, save to disk, `filing-obj` multimethod |
| `edgar.download` | bulk downloader — single company + batch, structured result envelopes |
| `edgar.xbrl` | XBRL company-facts → `tech.ml.dataset` (with labels); concept discovery; cross-sectional frames |
| `edgar.financials` | income statement, balance sheet, cash flow (ticker or CIK) |
| `edgar.extract` | NLP item-section extraction (10-K/10-Q/8-K); batch mode |
| `edgar.dataset` | panel datasets, cross-sectional snapshots, pivot helpers |
| `edgar.forms.form4` | Form 4 parser — insider trades (require to activate) |

## API Conventions

- **Keyword args throughout** — no positional `form` or `type` args anywhere
- **Ticker or CIK interchangeably** — all functions resolve via `company/company-cik`
- **`:concept` accepts string or collection** — coerced to a set internally
- **Datasets always return `tech.ml.dataset`** — never seq-of-maps
- **Amendments excluded by default** — pass `:include-amends? true` to `e/filings` / `e/filing` to include `10-K/A` etc.
- **Item extraction returns full section bodies** — `{item-id {:title "..." :text "..." :method ...}}`
- **Download results are structured envelopes** — `{:status :ok/:skipped/:error ...}`

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

EPL-2.0 — see [LICENSE](LICENSE).
