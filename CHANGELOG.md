# Changelog

All notable changes to edgarjure are documented here.

## [Unreleased]

### Fixed

**`edgar.company` — `tickers-by-ticker` map rebuilt on every call (Issue #2)**
- Added `tickers-by-ticker-cache` atom populated once in `load-tickers!` alongside `tickers-cache`. Previously the full 13,000-entry ticker→CIK map was re-derived on every `ticker->cik` invocation.
- `clear-cache!` resets both atoms. `tickers-by-ticker` is now a simple deref.

**`edgar.dataset` — removed unused `tech.v3.dataset.rolling` require (Issue #14)**
- `[tech.v3.dataset.rolling :as ds-roll]` was required but never referenced in any source form. Removed.

**`edgar.tables` — blank header cells broke column alignment (Issue #6)**
- `row-texts` stripped blank cells via `filterv (not blank?)`, causing headers like `["A" "" "B"]` to collapse to 2 columns and silently misalign all data rows.
- Fixed: `row-texts` now preserves all cells. `layout-table?` and the header-finder use non-blank cell count for the ≥2 threshold.

**`edgar.financials` — `:val-q` and `:val-ltm` dropped in wide format (Issue #8)**
- `to-wide` only emitted `(:line-item r) -> (:val r)`, silently discarding the `:val-q` and `:val-ltm` columns present in 10-Q flow statement output.
- Fixed: `to-wide` detects `:val-q`/`:val-ltm` on the dataset and emits `"<line-item> (Q)"` / `"<line-item> (LTM)"` columns alongside the plain YTD value.

**`edgar.api` / `edgar.filings` — `e/filing` fetched all pages to return nth element (Issue #1)**
- `e/filing` and `filings/get-filing` called `get-filings` without `:limit`, forcing full pagination even when only the nth element was needed.
- Fixed: both now pass `:limit (inc n)` so pagination stops after the needed element.

**`edgar.filing` — `parse-filing-index-html` `:name` extracted from wrong cell (Issue #5)**
- The `:name` selector used `(sel/select (sel/descendant :td :a) row)` — searching all `<a>` tags across all cells. If the description cell had a link and the filename cell did not, the wrong text was returned.
- Fixed: extract `:name` only from `(nth cells 2 nil)` (the document column); fall back to cell text if no `<a>` is present.

**`edgar.filings` — `get-quarterly-index` fragile header skip and wrong column mapping (Issue #3)**
- `(drop 10)` assumed exactly 10 header lines; the actual header length varies. Also `:cik` was mapped to `(nth parts 0)` when the actual column order is `company-name|form-type|date-filed|filename|cik`.
- Fixed: replaced `(drop 10)` with `(drop-while (complement data-line?))` keyed on `YYYY-MM-DD` in field 2. All five column mappings corrected; `str/trim` added to all fields.

**`edgar.extract` — `extract-items-text` included heading line in `:text` (Issue #12)**
- The plain-text path sliced body as `(subs text :start next-start)` — `:start` being the beginning of the `ITEM X. TITLE` match — so the heading was included. The HTML path excludes heading nodes.
- Fixed: store `:end` (position after the heading) per match; body is now `(subs text :end next-start)`.

**`edgar.xbrl` — `get-concept-frame` crashed on missing `:columns`; broke with row-vector data (Issue #13)**
- Two bugs: (1) absent `:columns` key caused `(mapv keyword nil)` crash; (2) `ds/->dataset` does not accept row-vectors with `:column-names` — the implementation was always broken for non-empty `:data`.
- Fixed: nil/empty `:columns` falls back to canonical `[:accn :cik :entityName :loc :end :val]` (or positional `:colN` for non-standard counts). Row-vectors converted to seq-of-maps via `(map #(zipmap cols %) data)`.

**`edgar.api` — `e/exhibit` docstring referenced non-public `e/filing-document` (Issue #16)**
- The docstring example `(e/filing-document f (:name ex))` referred to a function that was not exposed in `edgar.api`.
- Fixed: `e/filing-document` is now a public wrapper in `edgar.api`. The `e/exhibit` docstring updated to show both `e/doc-url` and `e/filing-document` as valid patterns.

**`edgar.api` / `edgar.schema` — four company functions lacked Malli validation (Issue #15)**
- `e/cik`, `e/company`, `e/company-name`, `e/company-metadata` accepted any input without validation, inconsistent with all other `edgar.api` public functions.
- Fixed: added `CompanyArgs` schema to `edgar.schema`; each function now calls `(schema/validate! schema/CompanyArgs ...)` at entry.

**`edgar.core` — `cache-evict!` ran O(n) scan on every `cache-put!` (Issue #10)**
- Every write to the cache triggered a full expired-entry scan, becoming expensive for large caches in long-running processes.
- Fixed: throttled to every 100th put via `put-count` atom and `eviction-interval` (100). `clear-cache!` resets the counter so the first post-clear put always evicts.

**`edgar.financials` — `dedup-restatements` and `dedup-point-in-time` used unnecessary `mapcat`+vector-wrap (Issue #11)**
- Both functions used `(mapcat (fn [[_ g]] [(reduce ...)]) ...)` — wrapping the `reduce` result in a single-element vector `[...]` solely to unwrap it via `mapcat`. Equivalent to `map`.
- Fixed: replaced `mapcat` + vector-wrap with `map` in both functions. `dedup-by-priority` retains `mapcat` as it genuinely emits variable-length results.

**`edgar.filing` — `filing-save-all!` silently overwrote duplicate filenames (Issue #9)**
- The SEC filing index can (rarely) list duplicate `:name` entries. Without deduplication, `filing-save-all!` would download and write the same file twice, potentially clobbering a good copy.
- Fixed: deduplicate `(:files idx)` by `:name` before the `for` loop using `(vals (into {} (map (juxt :name identity) ...)))`. Last entry wins on collision.
- Tests: `filing-save-all-dedup-test` (2 cases: download count equals unique filenames, return value has no duplicate paths).

### Added

**`edgar.api` — `e/filing-document` exposed as public function**
- Fetches a specific named document from a filing as a raw string. Complements `e/doc-url` and `e/exhibit`.

**`edgar.schema` — `CompanyArgs` schema**
- `[:map [:ticker-or-cik TickerOrCIK]]` — used by `e/cik`, `e/company`, `e/company-name`, `e/company-metadata`.

**Offline test suite expanded from 139 to 157 tests (633 → 777 assertions)**
- `company_test.clj` — `tickers-by-ticker-cache-test` (2 cases)
- `tables_test.clj` — `row-texts-preserves-blanks-test`, `extract-table-blank-header-cell-alignment-test`, updated `layout-table-test`
- `financials_test.clj` — `to-wide-quarterly-columns-test`, `to-wide-no-quarterly-columns-test`, `dedup-restatements-returns-flat-seq-test`, `dedup-point-in-time-returns-flat-seq-test`
- `api_docstring_test.clj` — `e-filing-limit-passthrough-test`, `filing-document-test`, `company-functions-malli-validation-test`
- `filings_test.clj` — `get-filing-limit-passthrough-test`, `get-quarterly-index-test`
- `filing_test.clj` — `parse-filing-index-html-name-scoping-test`, `filing-save-all-dedup-test`
- `extract_test.clj` — `extract-items-text-heading-exclusion-test`, updated `extract-items-plain-text-fallback-test`
- `xbrl_test.clj` — `get-concept-frame-test` (7 cases), updated ns require
- `schema_test.clj` — `CompanyArgs-test`
- `core_test.clj` — `cache-eviction-throttled-test`

## [0.1.8] — 2026-03-17

### Added

**`edgar.financials` — quarterly and LTM derivation for 10-Q flow statements**
- For income statement and cash flow queries with `:form "10-Q"`, `normalized-statement` now computes two additional columns: `:val-q` (single-quarter value from YTD subtraction) and `:val-ltm` (trailing twelve months as sum of four consecutive `:val-q` values).
- Q1 `:val-q` equals the reported value (already single quarter). Q2/Q3/Q4 subtract the prior quarter's cumulative YTD. LTM sums the current plus three prior quarters, crossing fiscal year boundaries via `:fy`/`:fp`. Either column is nil when required prior-period data is missing.
- These columns are absent for 10-K queries and for balance sheet (instant) data.
- New private functions: `prior-quarter`, `quarter-seq`, `build-ytd-lookup`, `compute-val-q`, `compute-val-ltm`, `add-quarterly-and-ltm`.

**Offline test suite expanded from 131 to 139 tests (596 → 633 assertions)**
- `financials_test.clj` — 8 new tests: `prior-quarter-test`, `quarter-seq-test`, `build-ytd-lookup-test`, `compute-val-q-test`, `compute-val-ltm-test`, `add-quarterly-and-ltm-test`, `normalized-statement-quarterly-test`, `normalized-statement-ltm-test`.

## [0.1.7] — 2026-03-17

### Fixed

**`edgar.financials/normalized-statement` — overlapping chain candidates produced duplicate rows per period**
- When a company filed multiple XBRL concepts from the same fallback chain for the same period (e.g. Google filing both `CashAndCashEquivalentsAtCarryingValue` and `CashCashEquivalentsAndShortTermInvestments` for "Cash and Equivalents"), both survived `dedup-restatements` and appeared as duplicate `:line-item` rows in the output.
- Fixed: added `dedup-by-priority` step after restatement dedup. Groups by `[line-item unit start end]` and keeps only the concept with the lowest chain index (= highest priority). Chain ordering encodes preference: index 0 is most specific/preferred. Temporally non-overlapping substitutes (e.g. old XBRL tag pre-2018, new tag post-2018) are unaffected since they occupy different periods.

### Changed

**`edgar.financials/balance-sheet-concepts` — "Long-Term Debt" chain reordered**
- `LongTermDebtNoncurrent` is now index 0 (preferred) ahead of `LongTermDebt`. Standard balance sheets report the noncurrent portion under long-term liabilities; `LongTermDebt` includes current maturities that belong in Current Liabilities. Confirmed with Google ($10.9B noncurrent vs $12.0B total) and Apple ($85.8B vs $96.7B).

**`edgar.financials/income-statement-concepts` — "Operating Expenses" / "CostsAndExpenses" split into separate line items**
- `CostsAndExpenses` (total costs *including* COGS) was bundled as a fallback for `OperatingExpenses` (opex *excluding* COGS). These are semantically different quantities — Google reports $238B in `CostsAndExpenses` while Apple reports $57B in `OperatingExpenses`. They are now two separate line items: "Operating Expenses" (`OperatingExpenses`) and "Total Costs and Expenses" (`CostsAndExpenses`).

**`edgar.financials/cash-flow-concepts` — "Net Change in Cash" chain reordered**
- `CashCashEquivalentsRestrictedCashAndRestrictedCashEquivalentsPeriodIncreaseDecreaseIncludingExchangeRateEffect` (ASU 2016-18 tag, includes restricted cash + FX) is now index 0 (preferred) ahead of `CashAndCashEquivalentsPeriodIncreaseDecrease` (legacy, cash only). All filers use the new tag exclusively post-2018. During the 2017–2019 transition period, both tags co-existed — BRK-A showed a ~$154M difference (restricted cash + FX adjustment). The new tag is the GAAP-required figure.

## [0.1.6] — 2026-03-16

### Fixed

**`edgar.financials/dedup-restatements` and `dedup-point-in-time` — coarse grouping key silently dropped 10-Q duration windows**
- Both functions grouped by `(juxt :concept :end)` only. For 10-Q data a company can have a 3-month Q3 observation (`:start` = July 1) and a 9-month YTD observation (`:start` = January 1) both ending September 30. The old key collapsed these into one row, keeping whichever was filed more recently.
- Fixed: group by `(juxt :concept :unit :start :end)`. Distinct duration windows are now preserved; genuine restatements of the same observation are still collapsed.

**`edgar.dataset/multi-company-facts` — same coarse dedup key in `:as-of` panel path**
- The as-of dedup reduce keyed on `[ticker concept end]`, missing `:unit` and `:start`. Same class of bug as above.
- Fixed: key is now `[ticker concept unit start end]`.

**`edgar.financials/resolve-fallback` — global winner silently dropped historical periods**
- `resolve-fallback` returned only the first matching candidate `[label winner]`. If a company used `SalesRevenueNet` before 2018 and `Revenues` after, `Revenues` won globally and all pre-2018 rows were filtered out downstream.
- Fixed: `resolve-fallback` now returns `[label [winner1 winner2 ...]]` — all candidates present in the data. `normalized-statement` flatmaps these into `concept->label` so every present candidate maps to the correct label, and the dedup step picks the right survivor per `[concept unit start end]` group.

**`edgar.extract/items-for-form` — returned `{}` for amended form types**
- `"10-K/A"`, `"10-Q/A"`, `"8-K/A"` fell through the `case` to the default `{}`, causing `extract-items` to return an empty map for any amended filing. `nil` also produced `{}`.
- Fixed: strip trailing `/A` suffix via `(str/replace (or form "") #"/A$" "")` before the `case` dispatch.

**`edgar.company/company-cik` — returned nil for unknown tickers instead of throwing**
- `company-cik` silently returned nil when `ticker->cik` could not resolve a symbol. Downstream callers such as `xbrl/get-facts-dataset` passed nil to `(Long/parseLong cik)`, throwing a `NullPointerException` with no useful context.
- Fixed: throws `ex-info` with `:type ::edgar.company/unknown-ticker` and `:ticker` when `ticker->cik` returns nil. `ticker->cik` itself continues to return nil.

**`edgar.company/search-companies` — returned raw EFTS `_source` payloads that did not match docstring**
- Docstring claimed `{:entity-name :cik :category}` keys. Actual code returned raw SEC field names, and the requested fields (`entity_name`, `file_num`, `biz_location`) do not exist in the EFTS `edgar_file` index.
- Fixed: requests correct fields (`display_names`, `ciks`, `biz_locations`, `inc_states`). Strips CIK/ticker suffixes from `display_names`. Deduplicates by CIK. Returns shaped `{:entity-name :cik :location :inc-states}` maps.

**`edgar.download/download-filings!` — nil save path produced misleading `:ok` envelope**
- When `filing/filing-save!` returned nil (filing has no primary document), the result was `{:status :ok :path nil}`.
- Fixed: nil path emits `{:status :skipped :accession-number "..." :reason :no-primary-doc}`.

**`edgar.core` TTL cache — expired entries accumulated indefinitely**
- The cache atom was never pruned. In long-running processes or broad crawls, stale entries accumulated as a slow memory leak.
- Fixed: added private `cache-evict!` which removes all entries where `:expires-at` is in the past, called at the start of every `cache-put!`. No background thread required.

### Added

**Offline test suite expanded from 124 to 129 tests (545 → 584 assertions)**
- `financials_test.clj` — duration-window preservation assertions in `dedup-restatements-test` and `dedup-point-in-time-test`; updated `resolve-fallback-test` and `resolve-all-chains-test` for new all-candidates return shape; new `normalized-statement-multi-candidate-test`.
- `dataset_test.clj` — duration-window preservation assertion in `multi-company-facts-as-of-dedup-test`.
- `extract_test.clj` — amended-form and nil-form cases in `items-for-form-test`.
- `company_test.clj` — `company-cik-unknown-ticker-test`; `search-companies-shape-test` (5 cases: output shape, name stripping, CIK dedup, limit, bad-hit skipping).
- `download_test.clj` — `download-filings-nil-primary-doc-test`.
- `core_test.clj` — `cache-eviction-test` (expired entries removed, non-expired entries survive).

---

## [0.1.5] — 2026-03-16

### Fixed

**`edgar.core/http-get-with-retry` — retry branch was unreachable (issue 1)**
- `hato/get` throws by default on non-2xx responses, so the `(< status 400)` cond branch was never reached for 4xx/5xx errors. Retry logic never fired.
- Fixed: added `:throw-exceptions? false` to every `hato/get` call inside `http-get-with-retry`. Wrapped in `try/catch` to also handle transport-level errors (timeouts, connection resets) as retryable. Throws `ex-info ::http-error` on exhaustion or non-retryable 4xx.

**`edgar.extract/extract-items` — plain-text fallback unreachable (issue 2)**
- The HTML branch condition `(and html (not (str/blank? html)))` was always true for plain-text filings because `filing/filing-html` returns raw content regardless of content type. The `extract-items-text` function was defined but never called.
- Fixed: added private `html-content?` predicate checking for `<(!DOCTYPE|html)` (case-insensitive). HTML path requires `(html-content? html)`. Plain-text content is passed directly to `extract-items-text`.

**`edgar.extract/extract-items-html` — item text bled across unrequested items (issue 3)**
- End-position pairs were built from `(partition-all 2 1 relevant)` where `relevant` was the filtered (requested-only) boundary list. Requesting `#{"1A" "7"}` in a doc with items 1, 1A, 2, 3, 7, 7A, 8 would pair 1A→7, making item 1A's text span all the way to item 7's heading — consuming items 2 and 3 in between.
- Fixed: iterate over all boundaries via `keep-indexed`; emit only entries for `target-ids`, but always use the next boundary in full document order for end-position calculation.

**`edgar.forms.form13f/parse-form13f` — manager/summary nil for modern two-document submissions (issue 4)**
- Modern 13F-HR filings package the cover page and holdings infotable as two separate XML attachments. `parse-form13f` fetched whichever single XML came first and ran both header and holdings parsing on it. The infotable XML has no `<filingManager>` or `<reportSummary>`, so `:manager` and `:period-of-report` were always nil.
- Fixed: introduced `find-primary-cover-xml` (excludes `INFORMATION TABLE`-typed entries). `parse-form13f` fetches cover and infotable separately; parses manager/summary from `cover-root`, holdings from `(or info-root cover-root)`. Single-document filings fall back correctly.

**`edgar.filing/filing-save!` and `filing-save-all!` — binary attachments corrupted by `spit` (issue 5)**
- Both save functions used `spit` (text mode) for every file, including PDFs, images, XLS, ZIP, causing encoding corruption.
- Fixed: introduced `binary-filename?` predicate (extensions `.pdf .xls .xlsx .zip .gif .jpg .jpeg .png .doc .docx`, case-insensitive) and `save-doc!` helper. Binary files fetched via `edgar-get-bytes` + `java.io.FileOutputStream`; text files continue with `edgar-get :raw?` + `spit`.

**`edgar.download/download-filings!` — inconsistent return shape for `:download-all?` (issue 6)**
- When `:download-all? true`, the branch returned `(mapv ok (filing/filing-save-all! f dir))` — a vector of `{:status :ok :path ...}` maps (one per attachment) instead of a single map per filing.
- Fixed: `:download-all?` now returns `{:status :ok :paths [...]}` — one envelope per filing in all modes.

**`edgar.company/shape-address` and `edgar.filings/shape-daily-hit` — nil values became the string `"nil"` (issue 7)**
- `(not-empty (str nil))` → `"nil"`. Affected `:street2` in `shape-address` and `:periodOfReport` in `shape-daily-hit`.
- Fixed both with `(some-> value not-empty)`.

**`edgar.tables/infer-column` — single blank cell caused entire numeric column to fall back to strings (issue 8)**
- `infer-column` required every cell to parse as a number. One blank cell in an otherwise numeric column made the whole column strings.
- Fixed: infer type from non-blank cells only; blank cells become `nil` in numeric columns.

**`edgar.tables/row-cells` — `<th>` cells collected before `<td>`, losing DOM order (issue 9)**
- `row-cells` collected all `<th>` nodes first, then all `<td>` nodes. A row with interleaved `[td th td]` produced `[th td td]`, misaligning headers with data.
- Fixed: single ordered pass over direct child nodes, emitting each cell in DOM order.

**`edgar.tables/row-cells` — colspan docstring claim with no implementation (issue 10)**
- Namespace docstring claimed colspan support; no code existed. A header cell with `colspan="3"` appeared as one entry, misaligning subsequent columns.
- Fixed: `row-cells` reads `:colspan` attribute and expands each cell into N repeated `[text hdr?]` entries. Invalid or missing colspan defaults to 1.

**`edgar.tables/extract-tables` — crash on nil or plain-text HTML (issue 11)**
- `filing/filing-html` result was passed directly to `hickory/parse`, which throws on nil. Plain-text filings and filings with no primary doc both reach this path.
- Fixed: `html-content?` guard at top of `extract-tables` (matches `<(!DOCTYPE|html|table)`). When HTML is nil, blank, or non-HTML: `:nth` → nil; seq → `[]`.

**`edgar.xbrl/get-facts-dataset` — `:desc` sort relied on SEC delivery order; `:asc` was a no-op (issue 12)**
- `:desc` used `ds/reverse-rows`, only correct if SEC delivers observations ascending — not guaranteed. `:asc` had no matching `cond->` clause and was a silent no-op. `ds/sort-by-column` with a comparator lambda on string columns causes `StackOverflowError` in TMD 7.030.
- Fixed: both branches now use `ds/sort-by` with an explicit key fn and comparator on the `:end` string column.

**`edgar.api` — docstring mismatches for `items`, `item`, and `exhibits` (issue 13)**
- `items`: `"item-id → text string"` corrected to `"item-id → {:title \"...\" :text \"...\" :method ...}"`.
- `item`: `"Returns text string or nil"` corrected to `"Returns {:title ... :text ... :method ...} or nil"`.
- `exhibits`: removed non-existent `:document` field; actual key is `:name`. Example corrected from non-public `(e/filing-document ...)` to `(e/doc-url ...)`.

### Added

**Offline test suite expanded from 99 to 124 tests (545 assertions)**

Two new test files added to `test_runner.clj`:
- `test/edgar/download_test.clj` — envelope shape for primary-doc, `:download-all?`, skipped, error, and multi-filing modes; all results are plain maps.
- `test/edgar/api_docstring_test.clj` — return shapes for `e/items`, `e/item`, `e/exhibits` (pins map shape, `:name` vs `:document`).

Additional tests in existing files:
- `core_test.clj` — retry on 429, transport error retry, exhausted retries throw, non-retryable 4xx throw.
- `extract_test.clj` — `html-content-predicate-test`, `extract-items-plain-text-fallback-test`, `extract-items-html-boundary-slicing-test`.
- `forms/form13f_test.clj` — `parse-form13f-two-document-test`, `parse-form13f-single-document-fallback-test`.
- `filing_test.clj` — `binary-filename-test`, `save-doc-uses-bytes-for-binary-test`.
- `company_test.clj` — `shape-address-nil-street2-test`.
- `filings_test.clj` — `shape-daily-hit-nil-period-test`.
- `tables_test.clj` — `infer-column-blank-cells-test`, `row-cells-dom-order-test`, `row-cells-colspan-test`, `extract-tables-nil-html-test`.
- `xbrl_test.clj` — `get-facts-dataset-sort-test` (desc, asc, nil, default, determinism).

---

## [0.1.4] — 2026-03-16

### Fixed

**`edgar.company/ticker->cik` — `MissingFormatArgumentException` at runtime**
- `format "%010d"` was applied directly to `:cik_str`, which is a string in the SEC tickers JSON. `%d` requires a `Long`/`Integer`, not a string, so every ticker→CIK lookup threw a `MissingFormatArgumentException`. Fixed by parsing with `(Long/parseLong (str (:cik_str entry)))` before formatting; the `str` wrapper also handles the case where jsonista delivers `cik_str` as an integer rather than a string.

**`edgar.company/cik->ticker` — precision loss for large CIKs**
- CIK comparison used `Double/parseDouble`, which loses integer precision for values with more than 15 significant digits. Fixed to `Long/parseLong`, consistent with every other CIK-handling path in the codebase.

**`edgar.filing/filing-text` — duplicated and garbled plain-text output**
- `filing-text` used `sel/select` to find every non-script/non-style node in the hickory tree, then called `(map :content ...)` + `flatten` + `(filter string? ...)` on the result. Because `sel/select` returns *all* matching nodes — including every ancestor of every text node — each string was collected once per ancestor, producing heavily duplicated output. Additionally, string children of excluded `<script>` and `<style>` nodes still passed through because `sel/not` only excluded the tag node itself, not its subtree content.
- Fixed by replacing the `sel/select` approach with a recursive `extract-text` function that mirrors the existing `cell-text` helper: walk `:content`, return `""` for any `:script` or `:style` subtree (dropping all descendant content), normalise `\u00A0`, and join. Single-pass, no duplication, correct exclusion.

**`edgar.forms.form4/form4-xml` — iXBRL instance document selected instead of ownership XML**
- `form4-xml` picked the first `.xml` file in the filing index. Modern Form 4 filings with inline XBRL contain both the real `ownership.xml` and an iXBRL instance document named `*_htm.xml`. If the iXBRL doc sorted first, the parser received the wrong file and returned empty/nil results for every field.
- Fixed by excluding filenames ending in `_htm.xml` from the candidate list, matching the filter already used in `edgar.forms.form13f/find-infotable-xml`.

**`edgar.financials/normalized-statement` — `:line-item` sorted descending (Z→A) within each period**
- The sort used `(ds/sort-by key-fn #(compare %2 %1))` where `key-fn` returned `[end line-item]`. The reversed comparator applied to the entire vector, flipping *both* components — so `:line-item` was sorted Z→A instead of A→Z within each period.
- Fixed with an explicit two-key comparator: `(compare (first b) (first a))` for `:end` descending, falling through to `(compare (second a) (second b))` for `:line-item` ascending.

**`edgar.dataset/pivot-wide` — nil-valued columns caused missing keys in flyweight maps**
- `pivot-wide` called `(ds/rows deduped)` without `{:nil-missing? true}`. TMD flyweight maps omit nil-valued keys, so columns like `:frame` (nil for most XBRL rows) were absent rather than explicitly nil, causing `:concept` and `:val` lookups to silently fail for affected rows and producing an incomplete wide dataset.
- Fixed by using `(ds/rows deduped {:nil-missing? true})`.

**`edgar.dataset/multi-company-facts` (`:as-of` path) — nil-valued columns caused missing keys in flyweight maps**
- The `:as-of` dedup `reduce` called `(ds/rows filtered)` without `{:nil-missing? true}`. Facts datasets have nil `:frame` columns; without the option, flyweight maps silently omit nil keys, making `(:filed row)` unreliable in the reduce comparator.
- Fixed by using `(ds/rows filtered {:nil-missing? true})`.

**`edgar.filings/enrich-filing` — unaliased `clojure.string/replace`**
- `enrich-filing` used the fully-qualified `clojure.string/replace` despite the namespace aliasing `[clojure.string :as str]`. Works at runtime but inconsistent with the rest of the file. Fixed to `str/replace`.

**`edgar.filings/fetch-extra-filings` — silent truncation for filers with >1000 filings**
- `fetch-extra-filings` was calling `(parse-filings-recent (:recent chunk))` on paginated submission chunks. These chunks are flat columnar objects (same format as `[:filings :recent]`) — NOT wrapped in a `{:recent {...}}` envelope. The call to `:recent` returned `nil`, causing `parse-filings-recent` to throw on any company with >1000 filings (e.g. AAPL), silently returning only the first 1000. Fixed by calling `(parse-filings-recent chunk)` directly.

**`edgar.filings/latest-effective-filing` — amendments never found**
- Was passing `:form form` to `get-filings`, which filters by exact form equality and excludes `"10-K/A"` when searching for `"10-K"`. The amendment branch was always empty. Fixed by calling `get-filings` without a `:form` filter and manually partitioning results into base-form vs. amendment lists.

**`edgar.forms.form13f/find-infotable-xml` — wrong document parsed for modern 13F submissions**
- Modern 13F-HR submissions package the holdings infotable as a separate attachment with `:type "INFORMATION TABLE"`. `find-infotable-xml` used filename heuristics and selected `primary_doc.xml` (cover page only), producing an empty `:holdings` dataset. Fixed by preferring the `"INFORMATION TABLE"` typed index entry over the filename-based fallback.

**`edgar.forms.form13f` — XML namespace prefix matching failure**
- `find-tag`, `find-tags`, and `child-tag-text` used exact keyword equality for tag matching. Modern 13F infotable XML uses `ns1:` namespace prefixes (`:ns1:infoTable`, `:ns1:nameOfIssuer`, etc.), so all tag lookups returned nil and holdings were empty. Fixed by matching on the tag name suffix after splitting on `":"`.

**`edgar.financials/to-wide` — nil-valued columns caused missing keys in flyweight maps**
- `to-wide` called `(ds/rows deduped)` without `{:nil-missing? true}`. TMD flyweight maps omit nil-valued keys, so columns like `:start` (nil for all balance-sheet rows) were absent rather than explicitly nil, corrupting `:end`/`:line-item` lookups and producing an empty wide balance sheet. Fixed by using `(ds/rows deduped {:nil-missing? true})`.

**`edgar.financials/concepts-in-data` — crash on empty dataset**
- `(ds/column facts-ds :concept)` throws when the dataset is empty (no columns). Fixed by returning `#{}` early when `(ds/row-count facts-ds)` is zero.

**`edgar.financials` — instant/duration filtering dropped majority of rows (two bugs)**
- `instant?` and `duration?` tested whether `:frame` ended in `"I"`. The `:frame` field is only populated on ~25-50% of XBRL observations, silently dropping most rows from every statement. Fixed: `instant?` now checks `(nil? (:start row))`; `duration?` checks `(some? (:start row))`.
- `normalized-statement` called `(ds/rows filtered-ds)` without `{:nil-missing? true}`. TMD flyweight maps omit nil-valued keys, so balance-sheet rows had no `:start` key at all, making `instant?` return `true` for every row. Fixed by using `(ds/rows filtered-ds {:nil-missing? true})`.

### Added

**`edgar.company-test` — `ticker->cik` and `cik->ticker` correctness tests**
- `ticker->cik-format-test`: verifies correct zero-padded output for both string and integer `cik_str` values, and nil return for unknown tickers. Uses `with-redefs` on `tickers-by-ticker` — no network access.
- `cik->ticker-test`: verifies Long-based CIK comparison (not Double), nil for unknown CIK. Uses `with-redefs` on `load-tickers!`.

**`edgar.filing-test` — `filing-text` subtree exclusion regression test**
- Extended `filing-text-excludes-script-style-test` with a second case using deeply nested `<script>` (`var x = 'injected'`) and `<style>` (`display: none`) blocks. Confirms that inner string content of excluded tags is fully absent from output, not just the tag nodes themselves.

**`edgar.forms.form4-test` — `form4-xml` iXBRL exclusion test**
- `form4-xml-excludes-ixbrl-test`: two cases using `with-redefs` on `filing/filing-index` and `filing/filing-document`. First case: index contains both `*_htm.xml` (iXBRL) and `ownership.xml`; verifies `ownership.xml` is selected. Second case: index contains only a `.htm` file; verifies fallback to first doc works.

**`edgar.financials-test` — `normalized-statement` sort-order test**
- `normalized-statement-sort-order-test`: four-row fixture with two periods and two concepts. Verifies `:end` column is descending (most recent first) and `:line-item` is ascending (A→Z) within each period.

**`edgar.dataset-test` — `multi-company-facts` `:as-of` and `pivot-wide` tests**
- `multi-company-facts-as-of-dedup-test`: two sub-cases with `with-redefs`. First verifies that `:as-of` excludes a later restatement and keeps only the earlier observation. Second verifies that nil `:as-of` returns all rows unfiltered.
- `pivot-wide-test`: verifies correct wide-format output from a facts dataset with nil `:frame` and `:start` columns — specifically that `{:nil-missing? true}` prevents missing-key errors.

**`edgar.financials` — expanded unit test coverage**
- New tests: `concepts-in-data` (including empty-dataset guard), `filter-by-duration-type` (all three branches), `add-line-item-col`, `raw-statement`, `normalized-statement` (empty-winning-concepts early-return path), `to-wide-nil-columns-test` (nil `:start`/`:frame` columns regression).

**`edgar.filings` — expanded unit test coverage**
- `fetch-extra-filings-flat-chunk-test`: verifies `parse-filings-recent` handles a flat chunk with no `:recent` wrapper.
- `latest-effective-filing-logic-test`: four `with-redefs` cases covering amendment newer/older/absent/only.

**`edgar.forms.form13f` — namespace-prefix regression test**
- `find-tags-namespace-prefix-test`: `ns1:`-prefixed XML fixture verifying `find-tags`, `find-tag`, and `parse-holding` round-trip on namespaced XML. Also covers the `child-tag-text` namespace fix via `:shares` field extraction.

**Integration test suite — complete `edgar.api` coverage**
- Expanded from 15 to 32 tests (137 assertions), covering every public function in `edgar.api` including `search`, `latest-effective-filing`, `filings-dataset`, `search-filings`, `daily-filings`, `html`, `item`, `obj` (Form 4 + 13F-HR), `tables`, `doc-url`, `exhibits`, `exhibit`, `xbrl-docs`, `save!`, `save-all!`, `frame`, `financials` (all with `:shape :wide` and `:as-of`), `pivot`, and Malli validation errors.
- Canonical 13F filer changed from `BRK-A` to `GS` (Goldman Sachs), which uses the modern `"INFORMATION TABLE"` document format with `ns1:` namespace prefixes.

## [0.1.3] — 2026-03-15

### Fixed

**`edgar.filing/cell-text` and `parse-filing-index-html` — phantom `.html` entries in Form 4 / Form 144 filing indexes cause 404**
- The SEC filing index HTML for Form 4 and Form 144 filings contains two entries with sequence `"1"`: a phantom `.html` entry (e.g. `ownership.html`) with a non-breaking space (`\u00A0`) as the size, and the real `.xml` file (e.g. `ownership.xml`) with a proper byte count. `cell-text` was returning `\u00A0` verbatim, so `str/trim` left it non-blank and `str/blank?` returned `false`. As a result `primary-doc` picked the phantom `.html` entry, which does not exist on SEC's servers, producing a 404 on every subsequent `filing-html`, `filing-text`, or `e/items` call.
- Fixed by normalising `\u00A0` → `" "` in `cell-text`, and adding a `(remove #(str/blank? (:size %)))` filter in `parse-filing-index-html` so phantom zero-size entries never enter the `:files` list.

**`edgar.extract/node-text` — non-breaking spaces not normalised, causing silent item-extraction failures**
- `node-text` was returning `\u00A0` verbatim. The `item-pattern` regex uses `\s`, which does not match `\u00A0` in Java. Headings like `"Item\u00A07"` (non-breaking space between "Item" and the number — present in some SEC filings) silently produced no boundary match, so those items were absent from `e/items` output with no error. Fixed by normalising `\u00A0` → `" "` at the `node-text` level, consistent with the same fix in `edgar.filing/cell-text` and `edgar.tables/node-text`.

**`edgar.tables/node-text` — non-breaking spaces not normalised**
- `node-text` was returning `\u00A0` verbatim. `clean-text`'s `\s+` regex and `parse-number`'s `[$,%\s]` strip both use Java's `\s`, which does not match `\u00A0`. Financial table cells with non-breaking space separators (e.g. `"1\u00A0234"`) were not parsed as numbers — `parse-number` returned `nil`, treating the cell as a string column instead of numeric. Fixed by normalising `\u00A0` → `" "` at the `node-text` level.

**`edgar.filing/filing-text` — included CSS and JavaScript content**
- `filing-text` used `sel/any` which selects all nodes including `:script` and `:style`, causing CSS rules and JS code to appear in the plain-text output of `(e/text filing)`. Fixed by excluding `:script` and `:style` nodes via `(sel/not (sel/tag :script/style))` before collecting text strings.

**`edgar.filing/filing-save!` — NPE when primary document is absent**
- If a filing's index has no sequence-`"1"` document, `primary-doc` returns `nil`. The old code called `(:name nil)` → `nil`, building a URL ending in `"/null"` and throwing on HTTP. Fixed with a `when primary` guard; the function now returns `nil` when no primary document exists, consistent with `filing-html`.

**`edgar.filing/parse-filing-index-html` — `:filingDate` always nil in `filing-by-accession`**
- `parse-filing-index-html` was only extracting `:files` and `:formType` from the index HTML, leaving `:filingDate nil` in every map returned by `filing-by-accession`. The filing date is present in the index page inside `.infoHead` / `.info` div pairs. Fixed by zipping those pairs and extracting the value for the `"Filing Date"` label. `filing-by-accession` already passed `(:filingDate idx)` through; it now receives a real `"YYYY-MM-DD"` string instead of `nil`.

**`edgar.filing/filing-by-accession` — duplicated `primary-doc` logic**
- The function contained an inline `(->> (:files idx) (filter #(= "1" ...)) first)` that duplicated `primary-doc` without benefiting from its filter chain. Replaced with a direct call to `(primary-doc idx)`.

### Added

**`:url` field on all filing maps**
- `enrich-filing` in `edgar.filings` now computes and asserts a `:url` key on every filing map returned by `get-filings`, `get-filing`, `latest-effective-filing`, and related functions. `filing-by-accession` also now includes `:url`. The value is the direct HTTPS URL to the primary document. `nil` only when `:primaryDocument` is absent.

**`edgar.filing/filing-doc-url` and `edgar.api/doc-url` — public URL construction**
- `doc-url` (previously private) is now public as `filing-doc-url` in `edgar.filing` and exposed as `e/doc-url` in `edgar.api`. Builds the SEC archives URL for any named document within a filing map — useful for constructing URLs for exhibits, attachments, and XBRL linkbase files without fetching the document.
- `(e/doc-url f "R2.htm")` or `(e/doc-url f (:name (e/exhibit f "EX-21")))`

**`edgar.filing-test` — fixture-based offline tests for `parse-filing-index-html`**
- Two HTML fixtures (`form4-index-html`, `form10k-index-html`) covering phantom-entry exclusion, `primary-doc` selection, form-type parsing, iXBRL viewer href handling, and standard field extraction.

**`edgar.filing-test` — `filing-text` script/style exclusion and `filing-save!` nil-guard tests**
- `filing-text-excludes-script-style-test`: verifies CSS and JS are stripped from plain-text output.
- `filing-save-nil-primary-doc-test`: verifies `filing-save!` returns `nil` rather than throwing when the filing index has no primary document.

**`edgar.extract-test` — `\u00A0` normalisation test in `find-item-boundaries`**
- Inline HTML fixture with `"Item\u00A07"` heading verifies that non-breaking space between "Item" and the item number does not prevent boundary detection.

**`edgar.tables-test` — `\u00A0` normalisation tests in `cell-text`**
- Two new cases in `cell-text-test`: nbsp in a number cell is normalised to regular space; nbsp-only cell is blank after normalisation.

## [0.1.1] — 2026-03-14

### Fixed

**`edgar.filing/filing-index-url` and `doc-url` — CIK zero-padding in archives path**
- Both functions were passing the zero-padded 10-digit CIK (e.g. `"0000320193"`) directly into the SEC archives URL path, producing `edgar/data/0000320193/...`. The SEC archives endpoint requires the unpadded numeric CIK (`edgar/data/320193/...`). Fixed by stripping leading zeros via `(str (Long/parseLong cik))` before constructing the URL in both functions.

**`edgar.filing/parse-filing-index-html` — switched from defunct JSON index to HTML index**
- The SEC no longer serves a structured JSON filing index at the `{accession}-index.json` URL for recent filings. `filing-index-url` now builds the `{accession}-index.html` URL instead. `parse-filing-index-html` was rewritten to parse the HTML index page using hickory. A new private helper `cell-text` recursively extracts all text content from a hickory node, correctly handling filenames wrapped in `<a>` tags (e.g. the iXBRL primary document). The returned `{:files [...] :formType "..."}` shape is unchanged.

### Added

**Integration test suite (`:test-integration` alias)**
- New `test-integration/edgar/integration_test.clj` — 15 tests covering the full live SEC API round-trip: company lookup (`cik`, `company-name`, `company-metadata`), filings query and amendment flag, `filing-by-accession` with a pinned accession number, filing content (`text`, `items`, `exhibits`), XBRL (`facts`, `concepts`), financial statements (`income`, `balance`, `cashflow`) including `:shape :wide` and `:as-of`, panel with `:ticker` column, and rate limiter burst. Uses hardcoded `"Test User test@example.com"` identity — never runs in CI.
- New `test-integration/edgar/integration_test_runner.clj` — entry point mirroring the offline runner.
- New `:test-integration` alias in `deps.edn` pointing at `test-integration/` source path.
- Run manually before releases with `clj -M:test-integration`.

---

## [0.1.0] — 2026-03-14

### Fixed

**`edgar.filing/filing-by-accession` — silent nil `:form` on missing index key**
- The accession lookup previously contained a fallback `:form-type` key that does not exist in the SEC filing index JSON (the correct key is `:formType`, keywordised by jsonista). When `:formType` was absent for any reason, `:form` silently became `nil`, causing `filing-obj` to dispatch on `nil` rather than throwing a useful error. Fixed by removing the fallback entirely; the function now throws `ex-info` with `{:type ::not-found :accession-number "..."}` when `:formType` is absent. Tested in `filing-by-accession-form-type-test`.

**`edgar.schema/FilingArgs` — `:n` and `:include-amends?` required instead of optional**
- `FilingArgs` declared `:n` and `:include-amends?` as required fields, while `FilingsArgs` (plural) marked its optional fields with `{:optional true}`. In practice `edgar.api/filing` always supplies defaults for these keys before calling `validate!`, so there was no user-visible failure — but calling `validate!` directly with a minimal map would throw confusingly. Fixed by adding `{:optional true}` to `:n` and `:include-amends?` in `FilingArgs`, aligning the schema with the actual call-site behaviour. Covered by new `schema_test.clj`.

**`edgar.dataset/multi-company-facts` — `(apply ds/concat [])` arity error on empty result**
- When all tickers threw (bad CIK, HTTP error, etc.) or the input vector was empty, the `for` comprehension produced an empty sequence and `(apply ds/concat [])` threw an arity exception. Fixed in two parts: (1) each ticker fetch is now wrapped in `try/catch` and failed tickers return `nil`, collected via `keep identity`; (2) `ds/concat` is only called when `(seq rows)` is truthy, otherwise an empty dataset is returned. Tested in new `dataset_test.clj` with `with-redefs` stubs covering all three cases: empty input, all-fail, and partial-fail.

**`edgar.tables/extract-table` — `sel/select` recursively included nested-table `<tr>` rows**
- `extract-table` used `(sel/select (sel/tag :tr) table-node)` to collect rows, which recursively traverses the entire subtree including nested `<table>` elements. This inflated the row count for filings with nested tables (common in SEC HTML exhibits) and caused column misalignment. Fixed by replacing `sel/select` with a new private function `direct-rows` that collects only `<tr>` nodes that are direct children of the `<table>`, `<thead>`, `<tbody>`, or `<tfoot>` elements — the same direct-child pattern already used by `row-cells` for cells. Tested in `extract-table-nested-no-double-count-test`.

**`edgar.extract/item-pattern` — regex could not match 10-Q Roman-numeral item IDs**
- The `item-pattern` regex only matched `\d{1,2}[AB]?` for the item number, making it impossible to match 10-Q item headings like `"Item I-1. Financial Statements"` or `"Item II-1A. Risk Factors"`. As a result, `extract-items` on 10-Q filings always returned `{}`. Fixed by extending the regex with an optional `(?:[IVXivx]+\s*[-\s]\s*)?` prefix before the digit group. ID normalization (`str/replace #"\s*[-\s]\s*(?=\d)" "-"` + `str/upper-case`) is applied in both `find-item-boundaries` (to produce correctly cased boundary IDs) and in `extract-items` (to normalize user-supplied `:items` to match `items-10q` map keys). Tested in `item-pattern-test`, `find-item-boundaries-10q-test`, and `extract-items-10q-normalized-ids-test`.

**`edgar.filings/parse-filings-recent` — double `(keys recent)` call**
- `(keys recent)` was called twice: once to build the keyword key sequence and once to get the column values. Although Clojure maps have stable iteration order within a single JVM session, calling `keys` twice is fragile. Bound the result once to `raw-ks` and reused it for both `ks` (keywordised) and `cols` (value extraction). Eliminates any theoretical risk of column/key misalignment.

**`edgar.company` — `search-companies` hardcoded form filter**
- `search-companies` no longer passes `&forms=10-K` to the EFTS query. Previously excluded companies that had never filed a 10-K.

**`edgar.filings` — silent truncation for large filers**
- `get-filings` now fetches all submission chunks for active filers with >1000 filings. Previously only read `[:filings :recent]`, silently truncating filing history for large filers such as AAPL.

**`edgar.financials` — `build-statement` docstring mismatch**
- `build-statement` docstring corrected: returns long-format dataset, not wide. Directs users to `(e/pivot (e/income "AAPL"))` for wide format.

**`edgar.financials/dedup-restatements` and `dedup-point-in-time` — bad `max-key` comparator**
- Both functions previously used `(apply max-key #(compare (:filed %) "") group)`. `max-key` expects a key function returning a comparable value, not a comparator; using `compare` against `""` returned `-1/0/1` integers, which accidentally selected the lexicographically largest `:filed` string for non-empty values but would silently return a wrong entry for `nil` or empty `:filed`. Replaced with `reduce` + `(pos? (compare (:filed %1) (:filed %2)))`, which is semantically correct in all cases.

**`edgar.extract/remove-tables` — nil nodes in `:content` causing NPE**
- `clojure.walk/postwalk` replaced `<table>` nodes with `nil`, leaving `nil` entries in parent `:content` vectors. Downstream `node-text` and `flatten-nodes` did not guard against `nil` content entries, causing NullPointerExceptions when extracting items from filings with tables near item boundaries. Fixed by additionally filtering `nil` from each node's `:content` during postwalk.

**`edgar.tables/row-cells` — recursive subtree selection double-counting nested cells**
- Previously used `(sel/select (sel/tag :th) tr-node)` and `(sel/select (sel/tag :td) tr-node)`, which select all matching elements in the entire subtree including nested tables. For filings with nested tables this double-counted cells and produced misaligned columns. Fixed by filtering direct `<th>` and `<td>` children from `:content` of each `<tr>` node.

**`edgar.forms.form13f/is-amendment?`**
- Previously checked the XML `reportType` field for the string `"RESTATEMENT"`, which is never present (the field contains values like `"13F HOLDINGS REPORT"`). This caused `:is-amendment?` to always be `false`. Fixed by overriding the value in the `merge` call inside `parse-form13f` using `(= "13F-HR/A" (:form filing))` — the `:form` key of the filing map is the correct source.

### Added

**`test/edgar/schema_test.clj`** (new file)
- Comprehensive Malli schema unit tests: `validate!` helper (nil on success, `ex-info` on failure, `:args` and `:errors` in ex-data), `FilingArgs` required/optional fields, `FilingsArgs` optional `:form` with `[:maybe FormType]`, `StatementArgs` (`:shape` enum, `:as-of` regex), `AccessionNumber` primitive (dashed format required). Added to `test_runner.clj`.

**`test/edgar/dataset_test.clj`** (new file)
- Offline tests for `multi-company-facts` robustness using `with-redefs`: empty tickers vector returns empty dataset; all tickers failing returns empty dataset; partial failure returns only the rows from the succeeding ticker. Added to `test_runner.clj`.

**Fixture-based offline tests**
- `test/edgar/tables_test.clj` — fixture HTML string (`fixture-tables-html`) embedded in the test namespace; fixture-driven tests cover `extract-tables` end-to-end (`:nth`, `:min-rows`, dedup column names), `cell-text`, `row-cells` (direct-child-only extraction), `extract-table`, `matrix->dataset` column deduplication, `parse-number`, `infer-column`, and `layout-table?`. No network access required.
- `test/edgar/forms/form4_test.clj` — fixture XML string; covers `parse-issuer`, `parse-owner`, `parse-non-derivative`.
- `test/edgar/forms/form13f_test.clj` — fixture XML string; covers `parse-report-summary`, `parse-holding`, `is-amendment?`.
- All fixture tests are offline (no HTTP calls) and run under `clj -M:test`.

**Exhibit and XBRL document API (`edgar.filing` / `edgar.api`)**
- `filing/filing-exhibits` — filters the filing index for entries whose `:type` starts with `"EX-"`. Returns a seq of maps `{:name :type :document :description :sequence}`.
- `filing/filing-exhibit` — returns the first index entry matching a given exhibit type string (e.g. `"EX-21"`), or `nil`. Fetch its content with `(filing/filing-document filing (:name exhibit))`.
- `filing/filing-xbrl-docs` — returns index entries whose `:type` starts with `"EX-101"` or whose `:name` ends with `".xsd"`. Covers instance, schema, calculation, label, presentation, and definition linkbases.
- Exposed via `edgar.api` as `e/exhibits`, `e/exhibit`, and `e/xbrl-docs`.

**`:as-of` on `e/panel` / `edgar.dataset/multi-company-facts`**
- `dataset/multi-company-facts` now accepts `:as-of "YYYY-MM-DD"`. When set, observations where `:filed > as-of-date` are excluded, then deduplication keeps the most recently filed survivor per `[ticker concept end]` key — identical look-ahead-safe semantics to `edgar.financials/dedup-point-in-time`.
- Exposed via `(e/panel [...] :as-of "2022-01-01")`. The `:as-of` key is now part of `schema/PanelArgs` and Malli-validated.

**Malli input validation (`edgar.schema` / `edgar.api`)**
- New namespace `src/edgar/schema.clj` — defines Malli map schemas for every public `edgar.api` function argument and a shared `validate!` helper. Invalid args throw `ex-info` with `{:type ::edgar.schema/invalid-args :args {...} :errors {...}}`.
- Schemas: `InitArgs`, `FilingsArgs`, `FilingArgs`, `FactsArgs`, `StatementArgs`, `FrameArgs`, `PanelArgs`, `SearchArgs`, `SearchFilingsArgs`, `TablesArgs`, `FilingByAccessionArgs`.
- Primitive schemas: `NonBlankString`, `TickerOrCIK`, `ISODate`, `FormType`, `ShapeKw`, `ConceptArg`, `AccessionNumber`, `PositiveInt`, `TaxonomyStr`, `FrameStr`.
- All public functions in `edgar.api` call `schema/validate!` at entry. Inner namespaces (`edgar.filings`, `edgar.xbrl`, etc.) do not validate — validation is centralised in the facade.
- `metosin/malli` 0.16.4 promoted from `:future` alias to main `deps.edn` deps.

**`edgar.financials` / `edgar.api` — Point-in-time `:as-of` option**
- All four financial-statement functions (`income-statement`, `balance-sheet`, `cash-flow`, `get-financials` and their `e/` wrappers) now accept `:as-of "YYYY-MM-DD"`. When set, observations where `:filed > as-of-date` are excluded before restatement deduplication, giving look-ahead-safe point-in-time results. Default behaviour (no `:as-of`) is unchanged: latest restated value is returned. Implemented in new private function `edgar.financials/dedup-point-in-time`.

**`edgar.forms.form13f`** (new file)
- 13F-HR parser — institutional holdings (XML-era, post-2013Q2 only). Registers `filing-obj "13F-HR"` and `filing-obj "13F-HR/A"` methods. Returns `{:form :period-of-report :report-type :is-amendment? :form13f-file-number :table-entry-count :table-value-total :manager {:name :street :city :state :zip} :holdings <tech.ml.dataset> :total-value}`. Holdings dataset columns: `:name :cusip :title :value :shares :shares-type :put-call :investment-discretion :other-managers :voting-sole :voting-shared :voting-none`. `:value` and voting columns are `Long`; `:total-value` is the sum of the `:value` column (thousands of USD as SEC reports it). Uses only `clojure.xml` + `clojure.string`; no new dependencies.

**`edgar.forms`** (new file)
- Central parser loader namespace. `(require '[edgar.forms])` loads all built-in form parsers (currently `form4` and `form13f`) in a single call, solving the discoverability problem where users would forget to require individual parser namespaces. Individual requires (`[edgar.forms.form4]`, `[edgar.forms.form13f]`) continue to work unchanged.

**`edgar.tables`** (new file)
- `extract-tables` — parses a filing's HTML with hickory, collects all `<table>` elements, extracts cell text per row (`th` + `td`), uses the first row with ≥2 non-blank cells as the header, aligns data rows to header width, deduplicates column names (suffixes `_1`, `_2`), and infers numeric types (strips `$`, `,`, `%`; converts `(123)` → `-123`). Layout tables with <2 data rows are skipped automatically. Options: `:min-rows`, `:min-cols` for post-hoc filtering; `:nth` to return a single table by index. `row-cells` uses direct-child filtering (not recursive subtree selection) to avoid double-counting cells in nested tables.

**`edgar.financials`**
- Concept fallback chains — each line item now has a primary GAAP concept and one or more fallback alternatives. Revenue, for example, tries `RevenueFromContractWithCustomerExcludingAssessedTax` (post-ASC-606) before falling back to `Revenues` and `SalesRevenueNet`. The first concept present in the company's facts wins.
- Duration vs instant filtering — balance sheet uses only observations where `:frame` ends in `"I"` (instant snapshots, e.g. `CY2023Q4I`); income statement and cash flow use only duration observations. Prevents mixing point-in-time and period values.
- Restatement deduplication — when multiple filings report the same concept+period, the observation with the latest `:filed` date wins. Implemented via `reduce` + `(pos? (compare (:filed %1) (:filed %2)))`.
- `:line-item` column — all normalized datasets now carry a human-readable label alongside the raw GAAP concept name.
- `:shape :wide` option — pass `:shape :wide` to `income-statement`, `balance-sheet`, `cash-flow`, or `get-financials` to receive a pivoted dataset with one row per period and one column per line item. Default remains `:long`.
- Public concept vars — `income-statement-concepts`, `balance-sheet-concepts`, `cash-flow-concepts` are now public `def`s. Users can inspect and override the concept fallback chains for non-standard filers.

**`edgar.company`**
- `company-metadata` — returns a shaped map extracted from the SEC submissions JSON: `:cik :name :tickers :exchanges :sic :sic-description :entity-type :category :state-of-inc :state-of-inc-description :fiscal-year-end :ein :phone :website :investor-website :addresses {:business {...} :mailing {...}} :former-names`.

**`edgar.filings`**
- `get-daily-filings` — lazy seq of all SEC filings submitted on a given date. Accepts an ISO date string (`"2026-03-10"`) or `java.time.LocalDate`. Optional `:form` filter and `:filter-fn` predicate. Implemented via EFTS search-index with `dateRange=custom` and lazy `from=` pagination (100 results per page).

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

**`edgar.forms.form4`**
- Form 4 parser (Statement of Changes in Beneficial Ownership / insider trades). Parses issuer, reporting owner, non-derivative and derivative transactions from XML. Registers `filing-obj "4"` via the standard multimethod. No new dependencies — uses only `clojure.xml` and `clojure.string`.

**`edgar.api`**
- `e/company-metadata`, `e/daily-filings`, `e/tables`, `e/filing-by-accession`, `e/latest-effective-filing`, `e/concepts`, `e/exhibits`, `e/exhibit`, `e/xbrl-docs` — new wrapper functions.
- `e/income`, `e/balance`, `e/cashflow`, `e/financials` — all now accept `:shape :wide` option.

### Changed

**`edgar.filings`**
- `get-filings` now filters out amended filings (`10-K/A`, `10-Q/A`, etc.) by default. Pass `:include-amends? true` to include them. This is a behaviour change: callers who previously received amendment forms in results and relied on them will need to add `:include-amends? true`.
- `get-filing` gains `:include-amends?` option (default `false`), threading through to `get-filings`.
- `get-filing` converted from positional `[ticker-or-cik form]` to keyword args `[ticker-or-cik & {:keys [form n] :or {n 0}}]`. Old call `(get-filing "AAPL" "10-K")` must be updated to `(get-filing "AAPL" :form "10-K")`.

**`edgar.api`**
- `e/filings` and `e/filing` gain `:include-amends?` option (default `false`).
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
- Moved five unused dependencies from core `:deps` to a new `:future` alias: `next.jdbc`, `honeysql`, `sqlite-jdbc`, and `datajure`. None are referenced in any source file.
