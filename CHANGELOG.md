# Changelog

All notable changes to edgarjure are documented here.

## [Unreleased]

### Added

**`edgar.forms.form13f`** (new file)
- 13F-HR parser ? institutional holdings (XML-era, post-2013Q2 only). Registers `filing-obj "13F-HR"` and `filing-obj "13F-HR/A"` methods. Returns `{:form :period-of-report :report-type :is-amendment? :form13f-file-number :table-entry-count :table-value-total :manager {:name :street :city :state :zip} :holdings <tech.ml.dataset> :total-value}`. Holdings dataset columns: `:name :cusip :title :value :shares :shares-type :put-call :investment-discretion :other-managers :voting-sole :voting-shared :voting-none`. `:value` and voting columns are `Long`; `:total-value` is the sum of the `:value` column (thousands of USD as SEC reports it). Uses only `clojure.xml` + `clojure.string`; no new dependencies.

**`edgar.forms`** (new file)
- Central parser loader namespace. `(require '[edgar.forms])` loads all built-in form parsers (currently `form4` and `form13f`) in a single call, solving the discoverability problem where users would forget to require individual parser namespaces. Individual requires (`[edgar.forms.form4]`, `[edgar.forms.form13f]`) continue to work unchanged.

**`edgar.tables`** (new file)
- `extract-tables` ? parses a filing's HTML with hickory, collects all `<table>` elements, extracts cell text per row (`th` + `td`), uses the first row with ?2 non-blank cells as the header, aligns data rows to header width, deduplicates column names (suffixes `_1`, `_2`), and infers numeric types (strips `$`, `,`, `%`; converts `(123)` ? `-123`). Layout tables with <2 data rows are skipped automatically. Options: `:min-rows`, `:min-cols` for post-hoc filtering; `:nth` to return a single table by index.

**`edgar.financials`**
- Concept fallback chains ? each line item now has a primary GAAP concept and one or more fallback alternatives. Revenue, for example, tries `RevenueFromContractWithCustomerExcludingAssessedTax` (post-ASC-606) before falling back to `Revenues` and `SalesRevenueNet`. The first concept present in the company's facts wins.
- Duration vs instant filtering ? balance sheet uses only observations where `:frame` ends in `"I"` (instant snapshots, e.g. `CY2023Q4I`); income statement and cash flow use only duration observations. Prevents mixing point-in-time and period values.
- Restatement deduplication ? when multiple filings report the same concept+period, the observation with the latest `:filed` date wins.
- `:line-item` column ? all normalized datasets now carry a human-readable label alongside the raw GAAP concept name.
- `:shape :wide` option ? pass `:shape :wide` to `income-statement`, `balance-sheet`, `cash-flow`, or `get-financials` to receive a pivoted dataset with one row per period and one column per line item. Default remains `:long`.
- Public concept vars ? `income-statement-concepts`, `balance-sheet-concepts`, `cash-flow-concepts` are now public `def`s. Users can inspect and override the concept fallback chains for non-standard filers.

**`edgar.company`**
- `company-metadata` ? returns a shaped map extracted from the SEC submissions JSON: `:cik :name :tickers :exchanges :sic :sic-description :entity-type :category :state-of-inc :state-of-inc-description :fiscal-year-end :ein :phone :website :investor-website :addresses {:business {...} :mailing {...}} :former-names`.

**`edgar.filings`**
- `get-daily-filings` ? lazy seq of all SEC filings submitted on a given date. Accepts an ISO date string (`"2026-03-10"`) or `java.time.LocalDate`. Optional `:form` filter and `:filter-fn` predicate. Implemented via EFTS search-index with `dateRange=custom` and lazy `from=` pagination (100 results per page).

**`edgar.api`**
- `e/company-metadata` ? thin wrapper around `company/company-metadata`.
- `e/daily-filings` ? thin wrapper around `filings/get-daily-filings`.
- `e/tables` ? thin wrapper around `tables/extract-tables`.
- `e/income`, `e/balance`, `e/cashflow`, `e/financials` ? all now accept `:shape :wide` option.

All notable changes to edgarjure are documented here.

## [Unreleased]

### Added

**`edgar.core`**
- In-memory TTL cache on `edgar-get`. JSON responses are cached keyed by URL: 5 minutes for metadata endpoints, 1 hour for `/api/xbrl/` endpoints. Cache is skipped when `:raw? true`. Evict with `(edgar.core/clear-cache!)`.
- Exponential backoff retry on 429/5xx responses. `http-get-with-retry` retries up to 3 times with delays of 2s → 4s → 8s. Throws `ex-info` with `{:type ::http-error :status N :url "..."}` on exhaustion or non-retryable 4xx. Applied to both `edgar-get` and `edgar-get-bytes`.

**`edgar.filing`**
- `filing-by-accession` — hydrates a complete filing map from an accession number string. Accepts dashed format (`0000320193-23-000106`) or undashed (`000032019323000106`). Extracts the CIK from the first 10 digits, fetches the filing index, and returns `{:cik :accessionNumber :form :primaryDocument :filingDate}` ready for all downstream functions. Throws `ex-info` with `{:type ::not-found}` if the accession does not exist.

**`edgar.filings`**
- `latest-effective-filing` — returns the most recent non-amended filing for a company and form type. If an amendment (e.g. `10-K/A`) exists with a newer `:filingDate` than the original, the amendment is returned instead.

**`edgar.xbrl`**
- `get-concepts` — returns a `tech.ml.dataset` with columns `[:taxonomy :concept :label :description]`, one row per distinct XBRL concept available for a company. Useful for discovering what data is available before calling `get-facts-dataset`.
- `:label` and `:description` columns added to all facts datasets. `flatten-facts` now preserves these fields from the XBRL taxonomy response. Affects `get-facts-dataset` and any downstream datasets built from it.

**`edgar.api`**
- `e/filing-by-accession` — thin wrapper around `filing/filing-by-accession`.
- `e/latest-effective-filing` — thin wrapper around `filings/latest-effective-filing`, defaulting `:form` to `"10-K"`.
- `e/concepts` — thin wrapper around `xbrl/get-concepts`, accepting ticker or CIK.

**`edgar.forms.form4`**
- Form 4 parser (Statement of Changes in Beneficial Ownership / insider trades). Parses issuer, reporting owner, non-derivative and derivative transactions from XML. Registers `filing-obj "4"` via the standard multimethod. No new dependencies — uses only `clojure.xml` and `clojure.string`.

### Changed

**`edgar.filings`**
- `get-filings` now filters out amended filings (`10-K/A`, `10-Q/A`, etc.) by default. Pass `:include-amends? true` to include them. This is a behaviour change: callers who previously received amendment forms in results and relied on them will need to add `:include-amends? true`.
- `get-filing` gains `:include-amends?` option (default `false`), threading through to `get-filings`.
- `get-filing` converted from positional `[ticker-or-cik form]` to keyword args `[ticker-or-cik & {:keys [form n] :or {n 0}}]`. Old call `(get-filing "AAPL" "10-K")` must be updated to `(get-filing "AAPL" :form "10-K")`.

**`edgar.api`**
- `e/filings` gains `:include-amends?` option (default `false`).
- `e/filing` gains `:include-amends?` option (default `false`).
- `e/facts` simplified — delegates filtering to `xbrl/get-facts-dataset` directly.
- `e/frame` updated to new `get-concept-frame` keyword-arg signature.
- `e/panel` passes `:concept` directly to `multi-company-facts`.
- `e/pivot` unwrapped — `pivot-wide` now returns a dataset directly.
- `e/income`, `e/balance`, `e/cashflow`, `e/financials` simplified — no longer pre-resolve CIK before delegating to `edgar.financials`.
- Removed unused `require` entries for `tech.v3.dataset` and `clojure.string`.
- Removed private `coerce-concepts` helper (logic moved into `xbrl/get-facts-dataset`).

**`edgar.download`**
- `download-filings!` gains `:skip-existing?` option (default `false`). When `true`, checks whether the output file already exists before downloading; returns `{:status :skipped :accession-number "..."}` if so.
- `download-batch!` `:parallelism` parameter is now honoured. Uses `(partition-all parallelism tickers)` + `pmap` per partition for bounded concurrency (previously ignored, used plain `pmap`).
- `download-batch!` now passes `:download-all?` and `:skip-existing?` through to `download-filings!`.
- All download functions (`download-filings!`, `download-batch!`, `download-index!`) now return structured result envelopes: `{:status :ok :path "..."}`, `{:status :skipped :accession-number "..."}`, or `{:status :error :accession-number "..." :type ... :message "..."}`. Previously returned bare strings or raw exception messages.

**`edgar.xbrl`**
- `get-facts-dataset` now accepts `& {:keys [concept form sort]}`. `:concept` accepts a string or collection; `:form` filters by form type; `:sort` defaults to `:desc` (uses `ds/reverse-rows`), pass `nil` to skip.
- `facts-for-concept`, `annual-facts`, `quarterly-facts` removed. Use `get-facts-dataset` options directly.
- `get-concept-frame` signature changed from positional `[taxonomy concept unit frame]` to `[concept frame & {:keys [taxonomy unit]}]` with defaults `taxonomy="us-gaap"`, `unit="USD"`. Old call `(get-concept-frame "us-gaap" "Assets" "USD" "CY2023Q4I")` must be updated to `(get-concept-frame "Assets" "CY2023Q4I")`.

**`edgar.financials`**
- `income-statement`, `balance-sheet`, `cash-flow` now accept ticker or CIK interchangeably (call `company/company-cik` internally). Previously accepted CIK only.

**`edgar.dataset`**
- `multi-company-facts` option renamed from `:concepts` to `:concept` for consistency. `:concept` accepts a string or collection.
- `pivot-wide` now returns a `tech.ml.dataset` directly (previously returned a seq of maps).
- `cross-sectional-dataset` updated to use new `get-concept-frame` keyword-arg signature.

**`edgar.extract`**
- `extract-items` now returns full section body text, not just the heading node text. Detection algorithm rewritten: flattens the full hickory tree into a document-order node sequence, identifies item heading boundaries by matching `item-pattern` across `heading-tags`, deduplicates by keeping the last match per item-id (body heading wins over TOC entry), then extracts text from the node slice between consecutive boundaries.
- Return shape changed from `{item-id "text"}` to `{item-id {:title "..." :text "..." :method ...}}`. **Breaking change:** callers that previously did `(get result "7")` and expected a string must now do `(get-in result ["7" :text])`.
- `extract-item` return shape changed accordingly: returns `{:title "..." :text "..." :method ...}` or `nil` (previously a string or `nil`).
- Plain-text fallback (`extract-items-text`) is now wired into the main `extract-items` dispatch path. Previously defined but never called.
- `:method` key added to return maps: `:html-heading-boundaries` for modern HTML filings, `:plain-text-regex` for pre-2000 plain-text fallback.
- `batch-extract!` arg order changed to `[filing-seq output-dir & opts]`.
- Removed unused `hickory.zip` and `clojure.zip` requires.

**`deps.edn`**
- Moved five unused dependencies from core `:deps` to a new `:future` alias: `next.jdbc`, `honeysql`, `sqlite-jdbc`, `malli`, and `datajure`. None are referenced in any source file. Core install weight reduced by ~15 MB.

### Fixed

**`edgar.company`**
- `search-companies` no longer passes `&forms=10-K` to the EFTS query. Previously excluded companies that had never filed a 10-K.

**`edgar.filings`**
- `get-filings` now fetches all submission chunks for active filers with >1000 filings. Previously only read `[:filings :recent]`, silently truncating filing history for large filers such as AAPL.

**`edgar.financials`**
- `build-statement` docstring corrected: returns long-format dataset, not wide. Directs users to `(e/pivot (e/income "AAPL"))` for wide format.
