# edgarjure — Project Summary

## Overview

**edgarjure** is a Clojure library for accessing and analysing SEC EDGAR filings. It is the Clojure ecosystem's equivalent of the Python libraries `edgartools`, `sec-edgar-downloader`, `secedgar`, and `edgar-crawler`, combined into a single coherent stack.

It talks directly to SEC's public JSON/HTTP APIs — no API keys, no paid services. The library covers:

- Company and CIK lookup
- Filing index queries (by company, date, form type), with full pagination for large filers
- Filing content access (HTML, text, attachments)
- Bulk filing download to disk
- XBRL/company-facts → `tech.ml.dataset` integration, including `:label` and `:description` columns
- XBRL concept discovery (`get-concepts` / `e/concepts`)
- Financial statement extraction (income statement, balance sheet, cash flow)
- NLP-oriented item-section text extraction (10-K, 10-Q, 8-K items)
- Form 4 (insider trades) parsing
- Accession number direct lookup (`filing-by-accession` / `e/filing-by-accession`)
- Amendment handling (`:include-amends?`, `latest-effective-filing`)
- Dataset utilities for empirical finance workflows, compatible with Datajure

**Runtime:** Clojure 1.12.4, Java 21
**REPL:** `clj -M:nrepl` → port 7888
**Tests:** `clj -M:test`

---

## Project Structure

```
edgarjure/
├── deps.edn                   — dependencies, :nrepl alias (port 7888), :test alias, :future alias
├── src/edgar/
│   ├── api.clj                — unified power-user entry point (require as [edgar.api :as e])
│   ├── core.clj               — HTTP client, TTL cache, retry, rate limiter, SEC base URLs
│   ├── company.clj            — ticker↔CIK resolution, company metadata
│   ├── filings.clj            — filing index queries, pagination, amendment handling, quarterly full-index
│   ├── filing.clj             — individual filing content, accession lookup, save to disk, multimethod dispatch
│   ├── download.clj           — bulk downloader (single company + batch), structured result envelopes
│   ├── xbrl.clj               — XBRL company-facts → tech.ml.dataset, concept discovery
│   ├── financials.clj         — income statement, balance sheet, cash flow
│   ├── extract.clj            — NLP item-section extraction (edgar-crawler analog)
│   ├── dataset.clj            — TMD/Datajure integration, panel datasets, pivot
│   └── forms/
│       └── form4.clj          — Form 4 parser (insider trades); registers filing-obj "4" method
├── test/edgar/
│   ├── core_test.clj          — URL builders, set-identity!, base URL sanity
│   ├── filings_test.clj       — latest-filing, full-index-url, get-filing
│   ├── extract_test.clj       — item maps, items-for-form dispatch, item-pattern regex
│   ├── xbrl_test.clj          — concept-frame-url builder, default taxonomy/unit values
│   └── test_runner.clj        — entry point for clj -M:test
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
| `techascent/tech.ml.dataset` | 7.030 | Columnar datasets (pandas equivalent) |

**`:future` alias** (not in main deps — declared for future use only):
- `com.github.seancorfield/next.jdbc` 1.3.939
- `com.github.seancorfield/honeysql` 2.6.1126
- `org.xerial/sqlite-jdbc` 3.47.1.0
- `metosin/malli` 0.16.4
- `com.github.clojure-finance/datajure` 2.0.0

**Note on hato coordinates:** The correct Clojars coordinate is `hato/hato` (not `io.github.gnarroway/hato`).

**No `org.clojure/data.zip` dependency.** `edgar.forms.form4` uses only `clojure.xml` and `clojure.string` for XML parsing — no extra deps required.

---

## Primary API: `edgar.api`

**`edgar.api` is the recommended entry point for all power-user and exploratory work.**
Require as `[edgar.api :as e]`. All functions accept ticker or CIK interchangeably. Keyword args throughout.

### Design principles
- Every function accepts ticker or CIK interchangeably
- Keyword args throughout; no positional `form`/`type` args
- Sensible defaults: taxonomy = `"us-gaap"`, unit = `"USD"`, form = `"10-K"`
- `:concept` accepts a string or a collection of strings
- Functions returning datasets always return `tech.ml.dataset`, never seq-of-maps

### Quick reference

```clojure
(require '[edgar.api :as e])
(e/init! "Your Name your@email.com")   ; required once

;; Company
(e/cik "AAPL")                         ; => "0000320193"
(e/company-name "AAPL")
(e/company "AAPL")                     ; full SEC submissions map
(e/search "apple" :limit 5)

;; Filings
(e/filings "AAPL" :form "10-K" :limit 5)
(e/filings "AAPL" :form "10-K" :include-amends? true)   ; include 10-K/A
(e/filing  "AAPL" :form "10-K")        ; latest non-amended
(e/filing  "AAPL" :form "10-K" :n 2)   ; 3rd latest (0-indexed)
(e/latest-effective-filing "AAPL" :form "10-K")  ; original or amendment, whichever is newer
(e/filings-dataset "AAPL" :form "10-K")
(e/search-filings "climate risk" :forms ["10-K"] :start-date "2022-01-01")

;; Accession number direct lookup
(def f (e/filing-by-accession "0000320193-23-000106"))
(e/text f)
(e/items f :only #{"7" "1A"})

;; Filing content
(def f (e/filing "AAPL" :form "10-K"))
(e/html  f)
(e/text  f)
(e/item  f "7")    ; => {:title "MD&A..." :text "...20k chars..." :method :html-heading-boundaries}
(e/items f :only #{"7" "1A"} :remove-tables? true)
(e/obj   f)        ; structured parse via filing-obj multimethod
(e/save! f "/data/filings")
(e/save-all! f "/data/filings")

;; XBRL facts — columns now include :label and :description
(e/facts "AAPL")                        ; full ~24k row dataset, sorted :end desc
(e/facts "AAPL" :concept "Assets")
(e/facts "AAPL" :concept ["Assets" "NetIncomeLoss"] :form "10-K")
(e/frame "Assets" "CY2023Q4I")          ; cross-sectional, defaults: us-gaap/USD
(e/frame "SharesOutstanding" "CY2023Q4I" :unit "shares")

;; XBRL concept discovery
(e/concepts "AAPL")   ; => dataset [:taxonomy :concept :label :description], one row per concept

;; Financial statements (ticker or CIK, both work)
(e/income   "AAPL")
(e/balance  "AAPL" :form "10-Q")
(e/cashflow "AAPL")
(e/financials "AAPL")   ; => {:income ds :balance ds :cashflow ds}

;; Panel datasets
(e/panel ["AAPL" "MSFT"] :concept "Assets")
(e/panel ["AAPL" "MSFT"] :concept ["Assets" "NetIncomeLoss"] :form "10-Q")

;; Pivot — always returns a tech.ml.dataset
(e/pivot some-facts-ds)
```

---

## Namespace Reference (underlying namespaces)

### `edgar.core`

Foundation layer. All other namespaces depend on it.

```clojure
;; Must call before any requests
(edgar.core/set-identity! "Your Name your@email.com")

;; Rate-limited GET → parsed JSON map (cached)
(edgar.core/edgar-get url)
(edgar.core/edgar-get url :raw? true)  ; → string, skips cache

;; Rate-limited GET → byte array
(edgar.core/edgar-get-bytes url)

;; Cache management
(edgar.core/clear-cache!)
```

**Key vars:** `base-url`, `data-url`, `archives-url`, `full-index-url`, `submissions-url`, `facts-url`, `tickers-url`, `efts-url`
**Key fns:** `cik-url`, `facts-endpoint`, `archives-path`

**Rate limiter:** Bucket4j token-bucket, 10 tokens/second, lazy `delay`-initialised.
**HTTP client:** hato with persistent connection pool, redirect-policy `:always`, lazy `delay`-initialised.
**TTL cache:** Atom-backed, keyed by URL. TTL = 5 min for metadata URLs, 1 hr for `/api/xbrl/` URLs. Only JSON responses are cached (`:raw? true` skips cache). Evict with `clear-cache!`.
**Retry:** `http-get-with-retry` — exponential backoff on 429/5xx, up to 3 attempts (2s → 4s → 8s). Throws `::http-error` ex-info on exhaustion or non-retryable 4xx.

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

**`search-companies` searches all filers.** No `&forms=` filter in EFTS query — returns results across all SEC form types.

---

### `edgar.filings`

```clojure
(require '[edgar.filings :as filings])

;; Lazy seq of filing metadata maps
(filings/get-filings "AAPL" :form "10-K" :limit 5)
(filings/get-filings "AAPL" :form "10-K" :start-date "2020-01-01")
(filings/get-filings "AAPL" :form "10-K" :include-amends? true)   ; include 10-K/A

;; Convenience
(filings/get-filing "AAPL" :form "10-K")            ; latest non-amended
(filings/get-filing "AAPL" :form "10-K" :n 2)       ; 3rd latest
(filings/get-filing "AAPL" :form "10-K" :include-amends? true)
(filings/latest-filing some-filings)
(filings/latest-effective-filing "AAPL" :form "10-K") ; original or amendment, whichever is newer

;; Quarterly full-index (bulk/historical crawling)
(filings/get-quarterly-index 2023 1)
(filings/get-quarterly-index-by-form 2023 1 "10-K")

;; Full-text search via EFTS
(filings/search-filings "climate risk" :forms ["10-K"] :start-date "2022-01-01")
```

**Filing map keys:** `:cik :form :filingDate :accessionNumber :primaryDocument :reportDate :isInlineXBRL`

**Amendment handling:** `get-filings` filters out amended forms (e.g. `10-K/A`, `10-Q/A`) by default. Pass `:include-amends? true` to include them. `latest-effective-filing` returns the most recent non-amended filing, but prefers a newer amendment if one exists.

**Pagination:** `get-filings` handles companies with >1000 filings automatically via `fetch-extra-filings`, which checks `(get-in company [:filings :files])` and fetches all additional JSON chunks.

---

### `edgar.filing`

Individual filing content access.

```clojure
(require '[edgar.filing :as filing])

;; Hydrate from accession number (no ticker/CIK needed)
(filing/filing-by-accession "0000320193-23-000106")
; => {:cik "320193" :accessionNumber "0000320193-23-000106" :form "10-K" :primaryDocument "..." :filingDate "..."}

(let [f (filings/get-filing "AAPL" :form "10-K")]
  (filing/filing-index f)              ; list of all documents in filing
  (filing/filing-html f)               ; primary doc as HTML string
  (filing/filing-text f)               ; stripped plain text
  (filing/filing-document f "R2.htm")  ; specific attachment
  (filing/filing-save! f "/data/")     ; save primary doc to disk
  (filing/filing-save-all! f "/data/") ; save all attachments
  (filing/filing-obj f))               ; dispatch by :form → structured map
```

**`filing-by-accession`:** Extracts the CIK from the first 10 digits of the accession number, fetches the filing index, returns a complete filing map. Accepts both dashed (`0000320193-23-000106`) and undashed (`000032019323000106`) formats. Throws `::not-found` ex-info if the accession does not exist.

**`filing-obj` multimethod** dispatches on `:form`. Default returns `{:form ... :raw-html ...}`. Add form-specific parsers via `(defmethod filing/filing-obj "10-K" [f] ...)`.

**Directory structure** created by `filing-save!`: `dir/{form}/{cik}/{accession-no}/{filename}`

---

### `edgar.forms.form4`

Form 4 parser — Statement of Changes in Beneficial Ownership (insider trades).
Load via `(require '[edgar.forms.form4])` — side-effectful, registers the `filing-obj "4"` method.

```clojure
(require '[edgar.forms.form4]
         '[edgar.filings :as filings]
         '[edgar.filing :as filing])

(-> (filings/get-filing "AAPL" :form "4")
    filing/filing-obj)
; =>
; {:form             "4"
;  :period-of-report "2024-01-15"
;  :date-of-change   "2024-01-17"
;  :issuer           {:cik "0000320193" :name "Apple Inc." :ticker "AAPL"}
;  :reporting-owner  {:cik "..." :name "..." :is-director? true :is-officer? true
;                     :officer-title "CEO" ...}
;  :transactions     [{:type :non-derivative
;                      :security-title "Common Stock"
;                      :date "2024-01-15"
;                      :coding "S"
;                      :shares 50000.0
;                      :price 185.50
;                      :acquired-disposed "D"
;                      :shares-after 1200000.0
;                      :ownership-nature "D"}]}
```

**Transaction types:** `:non-derivative` (common stock, RSUs) and `:derivative` (options, warrants). Both in `:transactions` vector.
**Transaction coding values:** `"P"` = purchase, `"S"` = sale, `"A"` = grant/award, `"F"` = tax withholding, `"M"` = option exercise.
**Acquired/disposed:** `"A"` = acquired, `"D"` = disposed.
**Implementation notes:** Uses only `clojure.xml` + `clojure.string`. Finds `.xml` attachment in filing index; falls back to first document. All numeric fields parsed to `Double` or `nil`.

---

### `edgar.download`

Bulk downloader (sec-edgar-downloader / secedgar analog).

```clojure
(require '[edgar.download :as download])

;; Single company — returns seq of result maps
(download/download-filings! "AAPL" "/data/filings"
                             :form "10-K" :limit 5)
(download/download-filings! "AAPL" "/data/filings"
                             :form "10-K" :skip-existing? true)
(download/download-filings! "AAPL" "/data/filings"
                             :form "10-K" :download-all? true)

;; Multiple tickers — :parallelism controls bounded concurrency via partition-all + pmap
(download/download-batch! ["AAPL" "MSFT" "GOOG"] "/data/filings"
                           :form "10-K" :limit 3 :parallelism 4)

;; Raw quarterly index files — returns seq of result maps
(download/download-index! 2020 2024 "/data/index")
```

**Result envelopes:** All download functions return structured maps:
- `{:status :ok :path "/data/10-K/320193/..."}`
- `{:status :skipped :accession-number "..."}`
- `{:status :error :accession-number "..." :type :edgar.core/http-error :message "..."}`

**`:parallelism` is now honoured** via `(partition-all parallelism tickers)` + `pmap` per partition.
**`:skip-existing?`** checks output file existence before downloading; returns `:skipped` envelope if already present.

---

### `edgar.xbrl`

XBRL company-facts → tech.ml.dataset. Uses SEC's pre-parsed `/api/xbrl/companyfacts/` endpoint.

```clojure
(require '[edgar.xbrl :as xbrl])

;; Full facts dataset (~24k rows for AAPL)
;; Columns: :taxonomy :concept :label :description :unit :end :val :accn :fy :fp :form :filed :frame
;; Sorted :end descending by default.
(def facts (xbrl/get-facts-dataset "0000320193"))

;; Built-in filtering
(xbrl/get-facts-dataset "0000320193" :concept "Assets")
(xbrl/get-facts-dataset "0000320193" :concept ["Assets" "NetIncomeLoss"] :form "10-K")
(xbrl/get-facts-dataset "0000320193" :sort nil)   ; skip sort

;; Concept discovery — one row per concept
(xbrl/get-concepts "0000320193")
; => dataset [:taxonomy :concept :label :description]

;; Cross-sectional: single concept across ALL companies for a period
(xbrl/get-concept-frame "Assets" "CY2023Q4I")
(xbrl/get-concept-frame "SharesOutstanding" "CY2023Q4I" :unit "shares")
```

**`:label` and `:description` are now preserved** in `flatten-facts` and appear as columns in all facts datasets. `get-concepts` returns a compact concept-discovery dataset without per-observation duplication.

**Known issue with sorting:** Do not use `ds/sort-by-column` with a comparator lambda on string columns — causes StackOverflowError in TMD 7.030. Use `ds/reverse-rows` or sort on numeric columns only.

---

### `edgar.financials`

All three statement functions accept ticker or CIK interchangeably.

```clojure
(require '[edgar.financials :as financials])

(financials/income-statement "AAPL")
(financials/income-statement "AAPL" :form "10-Q")
(financials/income-statement "AAPL" :shape :wide)   ; pivoted, one column per line item
(financials/balance-sheet    "AAPL")
(financials/cash-flow        "0000320193")

(financials/get-financials "AAPL")
; => {:income-statement ds :balance-sheet ds :cash-flow ds}
(financials/get-financials "AAPL" :shape :wide)
```

Output is **long-format** by default (`:shape :long`); pass `:shape :wide` for a pivoted dataset. Uses **concept fallback chains** (e.g. `RevenueFromContractWithCustomerExcludingAssessedTax` → `Revenues` → `SalesRevenueNet`), **duration vs instant filtering** (balance sheet = instant frames, income/cashflow = duration), and **restatement deduplication** (latest `:filed` date wins per concept+period). Concept vectors are public `def`s — override for non-standard filers:

```clojure
financials/income-statement-concepts   ; vector of [label primary-concept fallback1 ...]
financials/balance-sheet-concepts
financials/cash-flow-concepts
```

---

### `edgar.extract`

NLP-oriented item-section text extraction (edgar-crawler analog).

```clojure
(require '[edgar.extract :as extract])

(let [f (filings/get-filing "AAPL" :form "10-K")]
  (extract/extract-items f)
  (extract/extract-items f :items #{"7" "1A"})
  (extract/extract-items f :items #{"7"} :remove-tables? true)
  (extract/extract-item f "7"))

(extract/batch-extract! filing-seq "/tmp/extracted"
                        :items #{"1A" "7"}
                        :remove-tables? true
                        :skip-existing? true)
```

**Return shape:** `{item-id {:title "..." :text "..." :method :html-heading-boundaries}}`.

---

### `edgar.dataset`

TMD/Datajure integration and empirical finance helpers.

```clojure
(require '[edgar.dataset :as dataset])

(dataset/get-filings-dataset "AAPL" :form "10-K" :limit 10)
(dataset/filings->dataset some-filings-seq)
(dataset/multi-company-facts ["AAPL" "MSFT"] :concept "Assets" :form "10-K")
(dataset/cross-sectional-dataset "us-gaap" "Assets" "USD" "CY2023Q4I")
(dataset/pivot-wide facts-ds)
(dataset/filter-form ds "10-K")
(dataset/add-market-cap-rank ds :val)
```

---

## Architecture

```
SEC EDGAR APIs
    │
    ▼
edgar.core          ← identity, TTL cache, retry, Bucket4j rate-limiter, hato HTTP client
    │
    ├── edgar.api           ← unified power-user entry point (wraps all below)
    │
    ├── edgar.company       ← CIK/ticker resolution (cached)
    │
    ├── edgar.filings       ← filing index queries, pagination, amendment handling, full-index
    │       │
    │       └── edgar.filing        ← individual filing content, accession lookup, multimethod dispatch
    │               │
    │               ├── edgar.download      ← bulk save to disk, structured result envelopes
    │               ├── edgar.extract       ← NLP item-section extraction
    │               └── edgar.forms/
    │                   └── form4.clj       ← Form 4 parser (registers filing-obj "4")
    │
    └── edgar.xbrl          ← company-facts JSON → tech.ml.dataset (with labels), concept discovery
            │
            ├── edgar.financials    ← income/balance/cashflow statement builders
            └── edgar.dataset       ← panel datasets, cross-sectional, pivot helpers
```

---

## SEC API Endpoints Used

| Endpoint | Used by |
|---|---|
| `https://www.sec.gov/files/company_tickers.json` | `edgar.company` — CIK lookup |
| `https://data.sec.gov/submissions/CIK##########.json` | `edgar.company`, `edgar.filings` |
| `https://data.sec.gov/submissions/CIK##########-submissions-NNN.json` | `edgar.filings` — pagination chunks |
| `https://data.sec.gov/api/xbrl/companyfacts/CIK##########.json` | `edgar.xbrl` |
| `https://data.sec.gov/api/xbrl/frames/{taxonomy}/{concept}/{unit}/{frame}.json` | `edgar.xbrl` |
| `https://www.sec.gov/Archives/edgar/data/{cik}/{accession}/...` | `edgar.filing`, `edgar.download`, `edgar.forms.form4` |
| `https://www.sec.gov/Archives/edgar/full-index/{year}/QTR{q}/company.idx` | `edgar.filings`, `edgar.download` |
| `https://efts.sec.gov/LATEST/search-index?q=...` | `edgar.filings`, `edgar.company` |

---

## Conventions and Patterns

**Identity before all requests.** SEC enforces `User-Agent` header. Call `(edgar.core/set-identity! "Name email")` or `(e/init! "Name email")` once at startup.

**Plain maps everywhere.** No OOP class hierarchies. A "filing" is a plain map with `:cik :form :filingDate :accessionNumber :primaryDocument`. A "company" is the raw SEC submissions map.

**Lazy seqs for filing lists.** `get-filings` returns a lazy seq. Use `take`, `filter`, `first` — not `.head(10)` methods.

**Long-format datasets.** XBRL facts and financial statements come back as long-format TMD datasets (one row per observation). Use `e/pivot` or `dataset/pivot-wide` to reshape to wide format.

**Keyword args throughout.** Every public function uses `& {:keys [...]}`. No positional `form` or `type` arguments anywhere.

**`:concept` accepts string or collection.** Coerced to a set internally in `get-facts-dataset`, `multi-company-facts`, and `e/facts`.

**`filing-obj` multimethod** is the extension point for form-specific parsing. Dispatches on `:form` string.

**Form parsers live in `src/edgar/forms/`.** Each file is side-effectful on load (registers a `defmethod`). Require them explicitly.

**Amendments excluded by default.** `get-filings` and `get-filing` strip `10-K/A`, `10-Q/A`, etc. unless `:include-amends? true` is passed. Use `latest-effective-filing` when you want automatic amendment-awareness.

**Structured result envelopes from download functions.** `{:status :ok/:skipped/:error ...}` — never bare strings or raw exceptions.

**`filing-by-accession` as reproducibility entry point.** Given a cited accession number, produces a filing map ready for all downstream functions without needing to know the ticker.

**Bucket4j rate limiter** is lazy (`delay`) — initialised on first request. Shared across all HTTP calls.

**Error handling in batch ops.** `download-batch!` and `batch-extract!` catch exceptions per item and return error envelopes rather than failing the whole batch.

---

## Implementation Status vs. Roadmap

### Phase 1: Core Correctness — ✅ All done

| Item | Status | Notes |
|---|---|---|
| Item-section extraction (full section bodies) | ✅ Done | `extract.clj` — full hickory boundary-slice implementation |
| Filing pagination (>1000 filings) | ✅ Done | `fetch-extra-filings` in `filings.clj` |
| `build-statement` docstring accuracy | ✅ Done | Correctly says long-format |
| Unused dep pruning | ✅ Done | Heavy deps under `:future` alias only |
| `search-companies` form filter removal | ✅ Done | No `&forms=` in EFTS query |
| Ticker/CIK resolution consistency | ✅ Done | Consistent across all namespaces |

### Phase 2: Core Infrastructure — ✅ All done (March 2026)

| Item | Status | Notes |
|---|---|---|
| In-memory TTL cache on `edgar-get` | ✅ Done | Atom-backed; 5 min metadata, 1 hr XBRL; `clear-cache!`; `:raw?` bypasses |
| Exponential backoff retry | ✅ Done | `http-get-with-retry`; 429/5xx; up to 3 attempts (2s→4s→8s); `::http-error` ex-info |
| Accession number direct lookup | ✅ Done | `filing/filing-by-accession`, `e/filing-by-accession`; `::not-found` ex-info |
| XBRL concept discovery + labels | ✅ Done | `:label`/`:description` in all facts datasets; `xbrl/get-concepts`, `e/concepts` |
| Download robustness | ✅ Done | `:skip-existing?`; `:parallelism` honoured via `partition-all`; structured result envelopes |
| Amendment handling | ✅ Done | `:include-amends?` on `get-filings`/`get-filing`/`e/filings`/`e/filing`; `latest-effective-filing` / `e/latest-effective-filing` |
| Richer company metadata | ✅ Done | `company/company-metadata` + `e/company-metadata` return shaped map with `:sic :state-of-inc :fiscal-year-end :addresses` etc. |

### Phase 3: Form Parsers

| Item | Status |
|---|---|
| Form 4 (insider trades) | ✅ Done |
| 13F-HR (institutional holdings) | ✅ Done | `edgar.forms.form13f`; XML-era only (post-2013Q2); holdings as `tech.ml.dataset` |
| Form 3/5 (initial/annual ownership) | ❌ Not done |
| Schedule 13D/G (beneficial ownership) | ❌ Not done |
| DEF 14A (exec compensation) | ❌ Not done |
| Central `edgar.forms` loader namespace | ✅ Done | `src/edgar/forms.clj`; `(require '[edgar.forms])` loads all built-in parsers |

### Phase 3b: Research-Grade Data Features ✅ All done (March 2026)

| Item | Status | Notes |
|---|---|---|
| Financial statement normalization | ✅ Done | Concept fallback chains; duration/instant filtering; restatement dedup; `:shape :wide` option; public concept vars |
| Richer company metadata | ✅ Done | `e/company-metadata` shaped map; SIC, addresses, fiscal-year-end, state-of-inc |
| Daily filing index | ✅ Done | `filings/get-daily-filings`, `e/daily-filings`; lazy EFTS pagination; `:form` and `:filter-fn` options |
| HTML table extraction | ✅ Done | `edgar.tables/extract-tables`, `e/tables`; numeric type inference; `:nth`, `:min-rows`, `:min-cols` options |

---

## Prioritized Next Implementation Steps

All Phase 2 and Phase 3 items are complete. Remaining high-value work:

1. **13D/G parser** — activist investor / beneficial ownership stakes; XML-structured; follow `form13f` pattern
2. **Form 3/5 parser** — initial and annual ownership; same XML structure as Form 4; incremental
3. **DEF 14A exec compensation** — Summary Compensation Table extraction; highest-value subset; complex HTML
4. **Clojars 0.1.0 release** — tag + publish; use same `deps-deploy` workflow as clj-yfinance
5. **Fixture-driven test suite** — offline tests against stored HTML/XML fixtures; `:test-integration` alias for live calls
6. **Financial ratio namespace** — `edgar.ratios`; ROE, EBITDA, FCF, current ratio, D/E
7. **HTML table extraction tuning** — iXBRL splits values across cells; investigate post-processing for modern filings

---

## Gap Analysis vs. Python Equivalents

### vs. edgartools (largest remaining gap)

| Feature | edgartools | edgarjure status |
|---|---|---|
| Form 4 parser | ✅ | ✅ Implemented |
| 13F parser (fund holdings) | ✅ | ✅ Implemented (XML-era, post-2013Q2) |
| Proxy statement (DEF 14A) | ✅ | ❌ Not implemented |
| Schedule 13D/G ownership | ✅ | ❌ Not implemented |
| Financial ratio calculation | ✅ | Raw GAAP values only |
| XBRL concept labels/discovery | ✅ | ✅ Implemented |
| Text chunking for RAG | ✅ | `extract-items` returns full text only |
| HTTP caching layer | ✅ | ✅ Implemented |

### vs. sec-edgar-downloader / secedgar (minimal remaining gap)

| Feature | Python libs | edgarjure status |
|---|---|---|
| `:include_amends` | ✅ | ✅ Implemented |
| Resumable batch with skip-existing | ✅ | ✅ Implemented |
| Structured result envelopes | ✅ | ✅ Implemented |
| Actual parallelism control | ✅ | ✅ Implemented |
| Accession number direct lookup | ✅ | ✅ Implemented |

### Cross-cutting gaps

- **Persistence layer (`edgar.db`)** — `next.jdbc` + `honeysql` + SQLite in `:future` alias; namespace not written
- **Malli schemas** — no input/output validation yet; `malli` in `:future` alias
- **Integration tests** — no live SEC API call tests; add under `:test-integration` alias

---

## Testing

Tests cover pure/offline functions only (no HTTP calls). Run with `clj -M:test`.

| Test file | What it covers |
|---|---|
| `test/edgar/core_test.clj` | `cik-url`, `facts-endpoint`, `archives-path`, `set-identity!`, base URL constants |
| `test/edgar/filings_test.clj` | `latest-filing`, `full-index-url`, `get-filing` |
| `test/edgar/extract_test.clj` | `items-10k/10q/8k` completeness, `items-for-form` dispatch, `item-pattern` regex |
| `test/edgar/xbrl_test.clj` | `concept-frame-url` builder, default taxonomy/unit values |
| `test/edgar/test_runner.clj` | Runner namespace |

Integration tests not yet written. Add under `:test-integration` alias.

---

## Known Issues / Gotchas

1. **TMD sort StackOverflow:** `ds/sort-by-column` with a comparator lambda on string columns causes `StackOverflowError` in TMD 7.030. Use `ds/reverse-rows`.

2. **`ds/pivot->wider` does not exist in TMD 7.030.** `pivot-wide` in `edgar.dataset` uses a group-by + `ds/->dataset` workaround.

3. **`edgar.extract` text extraction is approximate.** Works well for modern iXBRL filings (post-2017). Pre-2000 plain-text may miss some items.

4. **No persistence layer.** `edgar.db` not written; deps in `:future` alias only.

5. **`malli` not integrated.** In `:future` alias only.

6. **Form parsers must be explicitly required.** `edgar.forms.form4` is not auto-loaded.

7. **No restatement deduplication in `get-facts-dataset`.** Raw XBRL facts still include all amended versions; deduplication is applied only in `edgar.financials` statement builders.

8. **HTML table extraction and iXBRL.** Modern SEC filings use inline XBRL which splits values across multiple `<td>` cells. `edgar.tables` extracts text faithfully but numeric columns may be fragmented. For precise financial data use `e/facts` via XBRL.

9. **`latest-effective-filing` uses `:filingDate` for amendment comparison.** If the amendment and original share the same date (unusual but possible), the original is returned.

---

## Development Workflow

```bash
# Start REPL
clj -M:nrepl

# Run tests
clj -M:test

# In any REPL session, set identity first
(edgar.core/set-identity! "Your Name your@email.com")
;; or via the api ns:
(require '[edgar.api :as e])
(e/init! "Your Name your@email.com")

;; Typical exploration
(require '[edgar.api :as e]
         '[edgar.forms.form4]   ; load form parsers as needed
         '[tech.v3.dataset :as ds])

;; Clear cache when testing fresh fetches
(edgar.core/clear-cache!)
```

---

## Extension Points

- **New form-type parsers:** `src/edgar/forms/form13f.clj` done. Add `form13dg.clj`, `def14a.clj`, etc., registering `defmethod edgar.filing/filing-obj`. Follow `edgar.forms.form4` / `edgar.forms.form13f` pattern.
- **Financial ratios:** add `edgar.ratios` namespace (current ratio, D/E, FCF, gross margin).
- **Text chunking:** add `edgar.chunk` wrapping `extract-items` output into fixed-size chunks for vector embedding/RAG.
- **Disk cache:** wrap `edgar-get` with optional SQLite persistence; already in `:future` alias deps.
- **Financial statement normalization:** ✅ Done. Remaining: concept fallback chain expansion; point-in-time snapshots for panel work.
- **Restatement handling:** add `edgar.restatements` with keep-latest and point-in-time snapshot strategies.
- **Footnote adjustments:** add `edgar.adjustments` with functional pipeline for lease capitalisation, non-recurring stripping, SBC add-back.
- **Daily filing index:** ✅ Done — `filings/get-daily-filings`, `e/daily-filings`.
- **HTML table extraction:** ✅ Done — `edgar.tables/extract-tables`, `e/tables`.
- **Integration tests:** `:test-integration` alias with live SEC API call tests.
- **Scicloj/Noj integration:** migrate `edgar.dataset` to Tablecloth; add `edgar.viz` with Tableplot; Clay notebooks under `:notebook` alias.
- **Async batch downloads:** replace `pmap` in `download-batch!` with `core.async` pipeline.
- **Central forms loader:** ✅ Done — `src/edgar/forms.clj`. Add new parsers to its `:require` vector.

---

## Maintenance Guidelines (for LLM assistants)

### No personal information

Never include personal information anywhere in the codebase — source files, tests, documentation, comments, or commit messages. Use generic placeholders such as `"Your Name your@email.com"` in all examples and docstrings.

### Keep README and CHANGELOG in sync

Whenever `PROJECT_SUMMARY.md` is updated, also update:

- **`README.md`** — reflect any new namespaces, features, or usage examples that are user-facing.
- **`CHANGELOG.md`** — add an entry under `## [Unreleased]` with `### Added`, `### Changed`, `### Fixed`, `### Removed` headings as appropriate.
