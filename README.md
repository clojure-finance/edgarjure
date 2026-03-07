# edgarjure

A Clojure library for accessing and analysing SEC EDGAR filings. Talks directly to SEC's public JSON/HTTP APIs — no API keys, no paid services.

The Clojure ecosystem's equivalent of Python's `edgartools`, `sec-edgar-downloader`, and `edgar-crawler`, combined into a single coherent stack built on `tech.ml.dataset` and Datajure.

## Features

- **Company & CIK lookup** — ticker↔CIK resolution, company search
- **Filing index queries** — by company, date range, form type; lazy seqs
- **Filing content access** — HTML, plain text, individual attachments
- **Bulk download** — single company or batch (pmap-based), quarterly index
- **XBRL / company-facts** → `tech.ml.dataset` integration
- **Financial statements** — income statement, balance sheet, cash flow (long-format datasets)
- **NLP item-section extraction** — 10-K, 10-Q, 8-K items; strip numeric tables; batch mode
- **Form 4 parsing** — insider trades: issuer, reporting owner, non-derivative and derivative transactions
- **Dataset utilities** — panel datasets, cross-sectional snapshots, pivot helpers (Datajure-compatible)

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
(require '[edgar.core     :as core]
         '[edgar.company  :as company]
         '[edgar.filings  :as filings]
         '[edgar.filing   :as filing]
         '[edgar.xbrl     :as xbrl]
         '[edgar.financials :as financials]
         '[edgar.extract  :as extract])

;; SEC requires a User-Agent header — call this once at startup
(core/set-identity! "Your Name your@email.com")

;; Resolve ticker → CIK
(company/ticker->cik "AAPL")   ;=> "0000320193"

;; Get the latest 10-K
(def f (filings/get-filing "AAPL" "10-K"))

;; Read its plain text
(filing/filing-text f)

;; Extract MD&A section (Item 7)
(extract/extract-item f "7")

;; XBRL facts as a dataset (~24k rows for AAPL)
(def facts (xbrl/get-facts-dataset "0000320193"))

;; Income statement
(financials/income-statement "0000320193")

;; All three financial statements at once
(financials/get-financials "AAPL")
;=> {:income-statement ds, :balance-sheet ds, :cash-flow ds}
```

### Form 4 — Insider Trades

```clojure
(require '[edgar.forms.form4]          ; side-effectful: registers filing-obj "4"
         '[edgar.filings :as filings]
         '[edgar.filing :as filing])

(-> (filings/get-filing "AAPL" "4")
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
| `edgar.core` | HTTP client, Bucket4j rate limiter (10 req/s), SEC base URLs |
| `edgar.company` | ticker↔CIK resolution, company metadata, search |
| `edgar.filings` | filing index queries, quarterly full-index, EFTS full-text search |
| `edgar.filing` | individual filing content, save to disk, `filing-obj` multimethod |
| `edgar.download` | bulk downloader — single company + batch |
| `edgar.xbrl` | XBRL company-facts → `tech.ml.dataset`; cross-sectional frames |
| `edgar.financials` | income statement, balance sheet, cash flow builders |
| `edgar.extract` | NLP item-section extraction (10-K/10-Q/8-K); batch mode |
| `edgar.dataset` | panel datasets, cross-sectional snapshots, pivot helpers |
| `edgar.forms.form4` | Form 4 parser — insider trades (require to activate) |

## SEC Rate Limits

SEC enforces a `User-Agent` header on all requests and a rate limit of ~10 requests/second. `edgarjure` handles both automatically via `edgar.core/set-identity!` and a Bucket4j token-bucket rate limiter.

## Development

```bash
# Start REPL on port 7888
clj -M:nrepl

# Run tests
clj -M:test

# In the REPL
(edgar.core/set-identity! "Your Name your@email.com")
```

## License

EPL-2.0 — see [LICENSE](LICENSE).
