# Changelog

All notable changes to edgarjure are documented here.

## [Unreleased]

### Added
- `edgar.forms.form4` — Form 4 parser (Statement of Changes in Beneficial Ownership / insider trades). Parses issuer, reporting owner, non-derivative and derivative transactions from XML. Registers `filing-obj "4"` via the standard multimethod. No new dependencies — uses only `clojure.xml` and `clojure.string`.

### Fixed

**`edgar.company`**
- `search-companies` no longer passes `&forms=10-K` or `&dateRange=custom` to the EFTS query. Previously the function silently excluded companies that had never filed a 10-K (e.g. funds, small filers, foreign private issuers). The query now searches across all form types.

**`edgar.filings`**
- `get-filings` now fetches all submission chunks for active filers with >1000 filings. Previously only read `[:filings :recent]` from the main submissions JSON, silently truncating filing history. Now checks `[:filings :files]` for additional chunk references (e.g. `CIK0000320193-submissions-001.json`) and concatenates them before filtering. Companies like AAPL now return their full history back to 1993.

**`edgar.financials`**
- `build-statement` docstring corrected: it returns a **long-format** dataset, not a wide dataset. Updated docstring notes that users compose `(e/pivot (e/income "AAPL"))` for wide format.

**`edgar.extract`**
- `extract-items` now returns full section body text, not just the heading node text. Detection algorithm rewritten: flattens the full hickory tree into a document-order node sequence, identifies item heading boundaries by matching `item-pattern` across `heading-tags`, deduplicates by keeping the last match per item-id (body heading wins over TOC entry), then extracts text from the node slice between consecutive boundaries.
- Return shape changed from `{item-id "text"}` to `{item-id {:title "..." :text "..." :method ...}}`. **Breaking change:** callers that previously did `(get result "7")` and expected a string must now do `(get-in result ["7" :text])`.
- `extract-item` return shape changed accordingly: returns `{:title "..." :text "..." :method ...}` or `nil` (previously a string or `nil`).
- Plain-text fallback (`extract-items-text`) is now wired into the main `extract-items` dispatch path. Previously defined but never called.
- `:method` key added to return maps: `:html-heading-boundaries` for modern HTML filings, `:plain-text-regex` for pre-2000 plain-text fallback.
- Removed unused `hickory.zip` and `clojure.zip` requires.

### Changed

**`deps.edn`**
- Moved five unused dependencies from core `:deps` to a new `:future` alias: `next.jdbc`, `honeysql`, `sqlite-jdbc`, `malli`, and `datajure`. None are referenced in any source file. Core install weight reduced by ~15 MB.

**`edgar.filings`**
- `get-filing` converted from positional `[ticker-or-cik form]` to keyword args `[ticker-or-cik & {:keys [form n] :or {n 0}}]`. Supports `:n` for nth-latest filing (0-indexed). Old call `(get-filing "AAPL" "10-K")` must be updated to `(get-filing "AAPL" :form "10-K")`.

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
- `batch-extract!` arg order changed to `[filing-seq output-dir & opts]`. Old call `(batch-extract! raw-dir output-dir filing-seq ...)` must be updated to `(batch-extract! filing-seq output-dir ...)`.

**`edgar.api`**
- `e/facts` simplified — delegates filtering to `xbrl/get-facts-dataset` directly.
- `e/frame` updated to new `get-concept-frame` keyword-arg signature.
- `e/panel` passes `:concept` directly to `multi-company-facts`.
- `e/pivot` unwrapped — `pivot-wide` now returns a dataset directly.
- `e/income`, `e/balance`, `e/cashflow`, `e/financials` simplified — no longer pre-resolve CIK before delegating to `edgar.financials`.
- Removed unused `require` entries for `tech.v3.dataset` and `clojure.string`.
- Removed private `coerce-concepts` helper (logic moved into `xbrl/get-facts-dataset`).
