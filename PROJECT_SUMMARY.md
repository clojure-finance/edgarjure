# edgarjure — Project Summary

## Overview

**edgarjure** is a Clojure library for accessing and analysing SEC EDGAR filings. It is the Clojure ecosystem's equivalent of the Python libraries `edgartools`, `sec-edgar-downloader`, `secedgar`, and `edgar-crawler`, combined into a single coherent stack.

It talks directly to SEC's public JSON/HTTP APIs — no API keys, no paid services. The library covers:

- Company and CIK lookup
- Filing index queries (by company, date, form type)
- Filing content access (HTML, text, attachments)
- Bulk filing download to disk
- XBRL/company-facts → `tech.ml.dataset` integration
- Financial statement extraction (income statement, balance sheet, cash flow)
- NLP-oriented item-section text extraction (10-K, 10-Q, 8-K items)
- Dataset utilities for empirical finance workflows, compatible with Datajure

**Runtime:** Clojure 1.12.4, Java 21  
**REPL:** `clj -M:nrepl` → port 7888

---

## Project Structure

```
edgarjure/
├── deps.edn                   — all dependencies and :nrepl alias
├── src/edgar/
│   ├── core.clj               — HTTP client, rate limiter, SEC base URLs
│   ├── company.clj            — ticker↔CIK resolution, company metadata
│   ├── filings.clj            — filing index queries, quarterly full-index
│   ├── filing.clj             — individual filing content, save to disk, multimethod dispatch
│   ├── download.clj           — bulk downloader (single company + batch)
│   ├── xbrl.clj               — XBRL company-facts → tech.ml.dataset
│   ├── financials.clj         — income statement, balance sheet, cash flow
│   ├── extract.clj            — NLP item-section extraction (edgar-crawler analog)
│   └── dataset.clj            — TMD/Datajure integration, panel datasets, pivot
├── test/                      — (empty, to be filled)
└── resources/
```

---

## Dependencies (`deps.edn`)

| Dependency | Version | Role |
|---|---|---|
| `hato/hato` | 1.0.0 | HTTP client (JDK11 HttpClient, HTTP/2, async) |
| `metosin/jsonista` | 0.3.8 | Fast Jackson-based JSON parsing |
| `hickory/hickory` | 0.7.1 | HTML → Clojure data structures (hiccup/zippers) |
| `com.bucket4j/bucket4j-core` | 8.10.1 | Token-bucket rate limiter (SEC 10 req/s) |
| `babashka/fs` | 0.5.22 | Filesystem utilities (nio-based) |
| `com.github.seancorfield/next.jdbc` | 1.3.939 | JDBC layer (for future metadata persistence) |
| `com.github.seancorfield/honeysql` | 2.6.1126 | SQL from data structures |
| `org.xerial/sqlite-jdbc` | 3.47.1.0 | SQLite driver |
| `metosin/malli` | 0.16.4 | Schema validation (for future pipeline hardening) |
| `techascent/tech.ml.dataset` | 7.030 | Columnar datasets (pandas equivalent) |
| `com.github.clojure-finance/datajure` | 2.0.0 | Data manipulation DSL over TMD |

**Note on hato coordinates:** The correct Clojars coordinate is `hato/hato` (not `io.github.gnarroway/hato` which is Maven Central and does not exist there).

---

## Namespace Reference

### `edgar.core`

Foundation layer. All other namespaces depend on it.

```clojure
;; Must call before any requests
(edgar.core/set-identity! "Your Name your@email.com")

;; Rate-limited GET → parsed JSON map
(edgar.core/edgar-get url)
(edgar.core/edgar-get url :raw? true)  ; → string

;; Rate-limited GET → byte array
(edgar.core/edgar-get-bytes url)
```

**Key vars:** `base-url`, `data-url`, `archives-url`, `full-index-url`, `submissions-url`, `facts-url`, `tickers-url`, `efts-url`  
**Key fns:** `cik-url`, `facts-endpoint`, `archives-path`

**Rate limiter:** Bucket4j token-bucket, 10 tokens/second, lazy `delay`-initialised.  
**HTTP client:** hato with persistent connection pool, redirect-policy `:always`, lazy `delay`-initialised.

---

### `edgar.company`

```clojure
(require '[edgar.company :as company])

(company/ticker->cik "AAPL")        ; => "0000320193"
(company/cik->ticker "0000320193")  ; => "AAPL"
(company/company-cik "AAPL")        ; handles both ticker and CIK input
(company/get-company "AAPL")        ; full SEC submissions map
(company/company-name "AAPL")       ; => "Apple Inc."
(company/search-companies "apple" :limit 5)
```

**Caching:** Tickers JSON (`/files/company_tickers.json`) is cached in a module-level atom on first call.

---

### `edgar.filings`

```clojure
(require '[edgar.filings :as filings])

;; Lazy seq of filing metadata maps
(filings/get-filings "AAPL" :form "10-K" :limit 5)
(filings/get-filings "AAPL" :form "10-K" :start-date "2020-01-01")

;; Convenience
(filings/get-filing "AAPL" "10-K")     ; latest single filing
(filings/latest-filing some-filings)

;; Quarterly full-index (bulk/historical crawling)
(filings/get-quarterly-index 2023 1)
(filings/get-quarterly-index-by-form 2023 1 "10-K")

;; Full-text search via EFTS
(filings/search-filings "climate risk" :forms ["10-K"] :start-date "2022-01-01")
```

**Filing map keys:** `:cik :form :filingDate :accessionNumber :primaryDocument :reportDate :isInlineXBRL`

---

### `edgar.filing`

Individual filing content access.

```clojure
(require '[edgar.filing :as filing])

(let [f (filings/get-filing "AAPL" "10-K")]
  (filing/filing-index f)             ; list of all documents in filing
  (filing/filing-html f)              ; primary doc as HTML string
  (filing/filing-text f)              ; stripped plain text
  (filing/filing-document f "R2.htm") ; specific attachment
  (filing/filing-save! f "/data/")    ; save primary doc to disk
  (filing/filing-save-all! f "/data/") ; save all attachments
  (filing/filing-obj f))              ; dispatch by :form → structured map
```

**`filing-obj` multimethod** dispatches on `:form`. Default returns `{:form ... :raw-html ...}`. Add form-specific parsers via `(defmethod filing/filing-obj "10-K" [f] ...)`.

**Directory structure** created by `filing-save!`: `dir/{form}/{cik}/{accession-no}/{filename}`

---

### `edgar.download`

Bulk downloader (sec-edgar-downloader / secedgar analog).

```clojure
(require '[edgar.download :as download])

;; Single company
(download/download-filings! "AAPL" "/data/filings"
                             :form "10-K" :limit 5)
(download/download-filings! "AAPL" "/data/filings"
                             :form "10-K" :download-all? true)

;; Multiple tickers in parallel (pmap-based)
(download/download-batch! ["AAPL" "MSFT" "GOOG"] "/data/filings"
                           :form "10-K" :limit 3)

;; Raw quarterly index files
(download/download-index! 2020 2024 "/data/index")
```

---

### `edgar.xbrl`

XBRL company-facts → tech.ml.dataset. Uses SEC's pre-parsed `/api/xbrl/companyfacts/` endpoint.

```clojure
(require '[edgar.xbrl :as xbrl])

;; Full facts dataset (~24k rows for AAPL)
;; Columns: :taxonomy :concept :unit :end :val :accn :fy :fp :form :filed :frame :start
(def facts (xbrl/get-facts-dataset "0000320193"))

;; Filter helpers
(xbrl/facts-for-concept facts "Assets")   ; all Assets observations, reversed
(xbrl/annual-facts facts)                 ; filter :form = "10-K"
(xbrl/quarterly-facts facts)              ; filter :form = "10-Q"

;; Cross-sectional: single concept across ALL companies for a period
;; frame examples: "CY2023Q4I" (instant), "CY2023" (annual duration)
(xbrl/get-concept-frame "us-gaap" "Assets" "USD" "CY2023Q4I")
```

**Known issue with sorting:** Do not use `ds/sort-by-column` with a comparator lambda on string columns — causes StackOverflowError in TMD. Use `ds/reverse-rows` or sort on numeric columns only.

---

### `edgar.financials`

```clojure
(require '[edgar.financials :as financials])

;; Individual statements (returns long-format TMD dataset)
(financials/income-statement "0000320193")
(financials/balance-sheet    "0000320193" :form "10-Q")
(financials/cash-flow        "0000320193")

;; All three at once
(financials/get-financials "AAPL")
; => {:income-statement ds :balance-sheet ds :cash-flow ds}
```

**GAAP concept lists** are defined as `income-statement-concepts`, `balance-sheet-concepts`, `cash-flow-concepts` — plain vectors of strings, easy to extend.

Output is a **long-format** dataset with columns `:end :concept :val :form :accn :fy ...`. Use `edgar.dataset/pivot-wide` to convert to wide format.

---

### `edgar.extract`

NLP-oriented item-section text extraction (edgar-crawler analog).

```clojure
(require '[edgar.extract :as extract])

(let [f (filings/get-filing "AAPL" "10-K")]
  ;; All items
  (extract/extract-items f)

  ;; Specific items only
  (extract/extract-items f :items #{"7" "1A"})

  ;; NLP mode: strip numeric tables
  (extract/extract-items f :items #{"7"} :remove-tables? true)

  ;; Single item
  (extract/extract-item f "7"))   ; MD&A text

;; Batch: process a filing seq, write .edn files
(extract/batch-extract! "/raw" "/extracted" filing-seq
                         :items #{"1A" "7"}
                         :remove-tables? true
                         :skip-existing? true)
```

**Item maps available:** `items-10k`, `items-10q`, `items-8k` (plain string→string maps).  
**Detection strategy:** Hickory HTML heading selector + regex `item-pattern` for boundary detection. Falls back to `extract-items-text` for plain-text pre-2000 filings.

---

### `edgar.dataset`

TMD/Datajure integration and empirical finance helpers.

```clojure
(require '[edgar.dataset :as dataset])

;; Filings index as dataset
(dataset/get-filings-dataset "AAPL" :form "10-K" :limit 10)
(dataset/filings->dataset some-filings-seq)

;; Long-format panel: multiple companies × concepts
(dataset/multi-company-facts ["AAPL" "MSFT" "GOOG"]
                              :concepts ["Assets" "NetIncomeLoss"]
                              :form "10-K")

;; Cross-sectional (single API call, all companies)
(dataset/cross-sectional-dataset "us-gaap" "Assets" "USD" "CY2023Q4I")

;; Helpers
(dataset/pivot-wide facts-ds)        ; long → seq of wide maps per period
(dataset/filter-form ds "10-K")
(dataset/add-market-cap-rank ds :val)
```

---

## Architecture

```
SEC EDGAR APIs
    │
    ▼
edgar.core          ← identity, Bucket4j rate-limiter, hato HTTP client
    │
    ├── edgar.company       ← CIK/ticker resolution (cached)
    │
    ├── edgar.filings       ← filing index queries (submissions JSON + full-index)
    │       │
    │       └── edgar.filing        ← individual filing content + multimethod dispatch
    │               │
    │               ├── edgar.download      ← bulk save to disk
    │               └── edgar.extract       ← NLP item-section extraction
    │
    └── edgar.xbrl          ← company-facts JSON → tech.ml.dataset
            │
            ├── edgar.financials    ← income/balance/cashflow statement builders
            └── edgar.dataset       ← panel datasets, cross-sectional, pivot helpers
                                       (Datajure-compatible output)
```

---

## SEC API Endpoints Used

| Endpoint | Used by |
|---|---|
| `https://www.sec.gov/files/company_tickers.json` | `edgar.company` — CIK lookup |
| `https://data.sec.gov/submissions/CIK##########.json` | `edgar.company`, `edgar.filings` |
| `https://data.sec.gov/api/xbrl/companyfacts/CIK##########.json` | `edgar.xbrl` |
| `https://data.sec.gov/api/xbrl/frames/{taxonomy}/{concept}/{unit}/{frame}.json` | `edgar.xbrl` |
| `https://www.sec.gov/Archives/edgar/data/{cik}/{accession}/...` | `edgar.filing`, `edgar.download` |
| `https://www.sec.gov/Archives/edgar/full-index/{year}/QTR{q}/company.idx` | `edgar.filings`, `edgar.download` |
| `https://efts.sec.gov/LATEST/search-index?q=...` | `edgar.filings`, `edgar.company` |

---

## Conventions and Patterns

**Identity before all requests.** SEC enforces `User-Agent` header. Call `(edgar.core/set-identity! "Name email")` once at startup.

**Plain maps everywhere.** No OOP class hierarchies. A "filing" is a plain map with `:cik :form :filingDate :accessionNumber :primaryDocument`. A "company" is the raw SEC submissions map.

**Lazy seqs for filing lists.** `get-filings` returns a lazy seq. Use `take`, `filter`, `first` — not `.head(10)` methods.

**Long-format datasets.** XBRL facts and financial statements come back as long-format TMD datasets (one row per observation). Use `pivot-wide` or Datajure to reshape.

**`filing-obj` multimethod** is the extension point for form-specific parsing. Dispatches on `:form` string. Add new parsers with `(defmethod filing/filing-obj "10-K" [f] ...)`.

**Accession number normalisation.** Raw SEC data has 18-digit strings; `accession->str` converts to dashed format `XXXXXXXXXX-YY-ZZZZZZ`. Both forms are handled throughout.

**Bucket4j rate limiter** is lazy (wrapped in `delay`) — initialised on first request. Shared across all `edgar-get` / `edgar-get-bytes` calls.

**Error handling in batch ops.** `download-batch!` and `batch-extract!` catch exceptions per item and return `{:error ... :accession-number ...}` maps rather than blowing up the whole batch.

---

## Known Issues / Gotchas

1. **TMD sort StackOverflow:** `ds/sort-by-column` with a comparator lambda on string columns causes `StackOverflowError` in tech.ml.dataset 7.030. Use `ds/reverse-rows` for simple reversals or sort on numeric columns. The `facts-for-concept` fn uses `ds/reverse-rows` as a workaround.

2. **`ds/pivot->wider` does not exist in TMD 7.030.** The `pivot-wide` fn in `edgar.dataset` returns a seq of maps instead; re-materialize with `(ds/->dataset (pivot-wide ds))` if needed.

3. **`edgar.extract` text extraction is approximate.** The boundary detection works well for modern iXBRL filings (post-2017). Pre-2000 plain-text filings use a separate regex path (`extract-items-text`) which may miss some items.

4. **`edgar.extract` unused requires:** `hickory.zip` and `clojure.zip` are required but not yet used. Reserved for future zipper-based traversal.

5. **No persistence layer yet.** `next.jdbc`, `honeysql`, and `sqlite-jdbc` are in deps but `edgar.db` namespace has not been written. Intended for crawl-state tracking (download status, parse status, CIK/accession tables).

6. **`malli` not yet integrated.** Schemas for filing maps and XBRL facts are not yet defined.

---

## Development Workflow

```bash
# Start REPL
clj -M:nrepl

# In any session, set identity first
(edgar.core/set-identity! "Dr. B buehlmaier@hku.hk")

# Typical exploration
(require '[edgar.company :as company]
         '[edgar.filings :as filings]
         '[edgar.xbrl :as xbrl]
         '[edgar.financials :as financials]
         '[edgar.extract :as extract]
         '[edgar.dataset :as dataset]
         '[tech.v3.dataset :as ds])
```

---

## Extension Points

- **New form-type parsers:** `(defmethod edgar.filing/filing-obj "10-K" [f] ...)` — add to new `edgar/forms/form_10k.clj` etc.
- **Insider trading (Form 4):** Add `edgar/forms/form4.clj`, dispatch via `filing-obj`.
- **13F holdings:** Add `edgar/forms/form13f.clj`.
- **Persistence layer:** Write `edgar.db` using `next.jdbc` + `honeysql` + SQLite for crawl-state tracking.
- **Malli schemas:** Add input/output schemas to `edgar.core/edgar-get`, `edgar.filings/get-filings`, etc.
- **Async batch downloads:** Replace `pmap` in `edgar.download/download-batch!` with `core.async` pipeline for finer concurrency control.
- **Deep XBRL:** For calculation links, dimensions, and taxonomy traversal beyond what the `companyfacts` JSON provides, add `xbrlj` via Java interop.
