# Changelog

All notable changes to edgarjure are documented here.

## [Unreleased]

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

**`edgar.filing/filing-by-accession` — duplicated `primary-doc` logic**
- The function contained an inline `(->> (:files idx) (filter #(= "1" ...)) first)` that duplicated `primary-doc` without benefiting from its filter chain. Replaced with a direct call to `(primary-doc idx)`.

### Added

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
