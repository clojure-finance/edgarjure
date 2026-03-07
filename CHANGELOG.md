# Changelog

All notable changes to edgarjure are documented here.

## [Unreleased]

### Added
- `edgar.forms.form4` — Form 4 parser (Statement of Changes in Beneficial Ownership / insider trades). Parses issuer, reporting owner, non-derivative and derivative transactions from XML. Registers `filing-obj "4"` via the standard multimethod. No new dependencies — uses only `clojure.xml` and `clojure.string`.

### Changed

**`edgar.filings`**
- `get-filing` converted from positional `[ticker-or-cik form]` to keyword args `[ticker-or-cik & {:keys [form n] :or {n 0}}]`. Supports `:n` for nth-latest filing (0-indexed). Old call `(get-filing "AAPL" "10-K")` must be updated to `(get-filing "AAPL" :form "10-K")`.

**`edgar.xbrl`**
- `get-facts-dataset` now accepts `& {:keys [concept form sort]}`. `:concept` accepts a string or collection; `:form` filters by form type; `:sort` defaults to `:desc` (uses `ds/reverse-rows`), pass `nil` to skip. Replaces the need for separate helper fns.
- `facts-for-concept`, `annual-facts`, `quarterly-facts` removed. Use `get-facts-dataset` options directly.
- `get-concept-frame` signature changed from positional `[taxonomy concept unit frame]` to `[concept frame & {:keys [taxonomy unit]}]` with defaults `taxonomy="us-gaap"`, `unit="USD"`. Old call `(get-concept-frame "us-gaap" "Assets" "USD" "CY2023Q4I")` must be updated to `(get-concept-frame "Assets" "CY2023Q4I")`.

**`edgar.financials`**
- `income-statement`, `balance-sheet`, `cash-flow` now accept ticker or CIK interchangeably (call `company/company-cik` internally). Previously accepted CIK only.

**`edgar.dataset`**
- `multi-company-facts` option renamed from `:concepts` to `:concept` for consistency. `:concept` accepts a string or collection (coerced to set internally).
- `pivot-wide` now returns a `tech.ml.dataset` directly (previously returned a seq of maps requiring `ds/->dataset` to re-materialise).
- `cross-sectional-dataset` updated to use new `get-concept-frame` keyword-arg signature.

**`edgar.extract`**
- `batch-extract!` arg order changed to `[filing-seq output-dir & opts]`. The unused `raw-dir` first argument has been removed. Old call `(batch-extract! raw-dir output-dir filing-seq ...)` must be updated to `(batch-extract! filing-seq output-dir ...)`.

**`edgar.api`**
- `e/facts` simplified — delegates filtering to `xbrl/get-facts-dataset` directly.
- `e/frame` updated to new `get-concept-frame` keyword-arg signature.
- `e/panel` passes `:concept` directly to `multi-company-facts` (no intermediate coercion).
- `e/pivot` unwrapped — `pivot-wide` now returns a dataset directly, so `ds/->dataset` wrap removed.
- `e/income`, `e/balance`, `e/cashflow`, `e/financials` simplified — no longer pre-resolve CIK before delegating to `edgar.financials`.
- Removed unused `require` entries for `tech.v3.dataset` and `clojure.string`.
- Removed private `coerce-concepts` helper (logic moved into `xbrl/get-facts-dataset`).
