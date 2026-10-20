# Changelog

All notable changes to edgarjure are documented here.

## [Unreleased]

## [0.5.2] — 2026-10-20

`:view :compustat` gets substantially closer to Compustat. Measured on the 13 industrials (AAPL MSFT IBM INTC KO PEP PG WMT CAT CVX HON UPS CSCO, FY2009–2025, 221 firm-years, FSDS on), old vs new code on one current-vintage extract, share within 1%: COGS 44.8 → 62.9%, XOPR 41.6 → 62.9%, XSGA 50.0 → 65.7%, Gross Profit 39.8 → 51.1%, OIBDP 22.6 → 30.3%, OIADP 28.1 → 34.8%, DP 74.2 → 80.1%, XRD 83.5 → 90.0%, Revenue (Compustat) 95.9% (newly emitted for every filer); SEQ/CEQ/TEQ 97.3 → 98.2%, RE 95.9 → 97.7%, RECT 68.8 → 70.6%; banks and insurers RE/SEQ/TEQ 95.1 → 100%. Across all 22 sample firms 538 firm-year items moved to a match and 33 away from one.

### Breaking
- 10-K statements under `:view :compustat` keep each value as **first reported** (by the fiscal year's own 10-K) instead of the latest restatement — empirically how Compustat's annual items behave. Applies to all three statements, including cash flow and the bank/insurance/REIT income statements, which therefore no longer equal `:standardized`. Other views and 10-Q statements are unchanged.
- `"Revenue (Compustat)"` is emitted for every filer (the revenue line when no excise netting applies), not only for excise filers; the `:gross-profit` rule is gone (`:gross-profit-net-revenue` always applies).
- The ruleset `:fsds` spec replaces `:da-marker`/`:da-tags` with `:markers`, a vector of `{:label :tags :on-income-statement}`.

### Added
- Reclassification engine: `[:max ...]` formula operator, `[:at-least formula b ratio]` guard, and `:aux {:concepts [...]}` — individual XBRL concepts as rule operands, labeled with their concept name.
- Income rules: `:dp-largest-tag` (the largest combined D&A tag when the filer tags several — the smaller is typically a footnote subset) and a fragment guard on `:dp-components`; `:xsga-rd-off-statement` (with FSDS: R&D disclosed only in the notes is not added to SG&A again — PG, PEP, HON); oil-presentation rules `:cogs-oil-gas`, `:cogs-oil-gas-purchased`, `:xsga-oil-gas`, `:revt-oil-gas` (CVX); `:revt-incl-tax-ex-excise`; `:cogs-total-costs`/`:xopr-total-costs` for filers with neither a cost-of-sales nor an SG&A line (UPS); `:oiadp-bottom-up` for filers without an operating-income line, only when XOPR + DP ties to reported total costs within 2%.

### Fixed
- FSDS placement markers came from every 10-K that reported a period, so a later filing's presentation could flip an earlier year's D&A guard (INTC FY2016); markers now come from the period's own 10-K only, while extension operands still fall back to later filings.
- The FSDS extension patterns were unanchored and also matched stock-compensation tags (`StockBasedCompensationExpenseAssociatedWithFulfillmentExpense`), so AMZN's fulfillment and technology operands depended on row order.

## [0.5.1] — 2026-10-20

Several changes below break existing callers; they are listed under **Breaking**. Annual `:view :compustat` values are unchanged (old vs new code compared on 14 tickers, including a point-in-time `:as-of` run), so the 0.5.0 Compustat rates still stand.

### Breaking
- `e/tables` (and `edgar.tables/extract-tables`) take `:n` instead of `:nth` to pick one table — the same option name as `e/filing`.
- The `:future` alias is removed from `deps.edn` (parked `next.jdbc`/`honeysql`/`sqlite-jdbc`/`datajure` dependencies that no code used).
- Schedule 13D/G maps: `:type` → `:types` and `:rule` → `:rules`, always vectors (`[]` when absent) — filings list several (`:types ["IA" "PN"]`).
- The truncated `"SCHEDULE"` form-type dispatch is removed (it only served maps from before multi-word form types were captured in 0.4.1).
- `e/tables` output: colspan/rowspan grids collapse to one column per header cell (values are no longer repeated across spanned columns), all-blank columns are dropped, `"$"`/`"%"`/`")"` fragment cells merge into their number, a sub-header row is folded into the column names (`"2025 Amount (in millions)"`), and `"—"`/`"–"`/`"-"` read as nil so such columns still type as numbers. Number parsing is stricter.
- XBRL facts keep the full unit: per-share facts have `:unit "USD/shares"` (was `"shares"`).
- Fixed column order: facts datasets (`:taxonomy :concept :label :description :unit :start :end :val :accn :fy :fp :form :filed :frame`), long statement output, wide statement output (`:end`, then line items in chain order, identity targets, rule targets, each followed by its `(Q)`/`(LTM)` columns), and `e/pivot` (`:ticker :end`, then concepts alphabetically).
- `e/pivot` drops 10-K 3/6/9-month footnote windows (annual rows only).
- `:standardized` `"Total Liabilities"` and `"Non-Current Liabilities"` derived as L&E − Total Equity also subtract redeemable NCI and redeemable preferred stock (mezzanine). DIS FY2023: 101.6B → 92.6B.
- 10-K `:view :compustat` no longer reclassifies the 3/6/9-month footnote windows (they mixed rule regimes; rows tagged `:rule :oiadp` were plain operating income).
- Derived (`:method :derived`) and reclassified rows no longer inherit the first operand's `:label :description :taxonomy :accn :fy :fp :frame`; `:concept` is nil and `:filed` is the latest operand's filing date.
- `e/unmapped-concepts` `:count` is the number of distinct companies, and concepts of every built-in chain (all statements and industries) count as mapped — `e/income` no longer logs balance-sheet and cash-flow concepts as unmapped.
- Balance sheet with `:form "10-Q"` no longer carries all-nil `:duration-months`/`:val-q`/`:val-ltm` columns or `"(Q)"`/`"(LTM)"` wide columns.
- Filing maps from `e/filings` have `:url nil` for pre-2001 filings (blank primary document); it was the folder URL.
- `e/filing-by-accession`: only 403/404 become `::not-found`; other failures (5xx, transport errors) propagate unchanged.
- `e/daily-filings` throws when the SEC refuses access (blocked or missing User-Agent) instead of returning an empty day.
- Every filing-content function (`e/html`, `e/text`, `e/items`, `e/item`, `e/obj`, `e/save!`, `e/save-all!`, `e/filing-index`, `e/doc-url`, `e/filing-document`, `e/exhibits`, `e/exhibit`, `e/xbrl-docs`, `e/tables`) validates its filing map (`:cik` and `:accessionNumber` required) and throws `::edgar.schema/invalid-args`; `e/daily-filings`, `e/latest-effective-filing`, `e/filings-dataset` and `e/concepts` validate too.
- `edgar.extract/batch-extract!` result key `:accession-no` → `:accession-number` (as in `download-filings!`).
- Removed `edgar.filings/get-quarterly-index-by-form` (use the `:form` option of `get-quarterly-index`, or `e/quarterly-index`) and `edgar.dataset/cross-sectional-dataset`, `filter-form`, `add-market-cap-rank` (use `e/frame` for cross sections).
- `edgar.schema/PositiveInt` renamed `NonNegInt` (it always meant ≥ 0).
- `examples/compustat_validation.clj`: `validate-annual` returns `:cash-flow` (was `:cashflow`); GS and MS left its `banks` set (SIC 6211 routes to the standard chains).

### Added
- `e/quarterly-index` (`year quarter & :form`) — every filing in an EDGAR quarterly master index; previously only reachable as `edgar.filings/get-quarterly-index`.
- `e/ticker` — CIK → ticker (primary share class).
- Item lists for 10-K405, 10-KT, 10-KT405 (10-K items), 10-QT, 10-QSB (10-Q items), and 10-KSB/10-KSB40 (MD&A = Item 6, controls = Item 8A); amendments follow their base form. A JNJ 10-K405 now yields 15 items (was `{}`).
- `e/xbrl-docs` includes the instance the SEC extracts from an inline-XBRL document (`*_htm.xml`, `:type "XML"`).
- Company addresses carry `:country` and `:country-code` (foreign filers had no country).
- `edgar.core` helpers `unpad-cik`, `dashed-accession`, `archive-url`, `url-encode`.

### Fixed
- `e/search-filings` ignored a one-sided date range (EFTS needs both bounds); the missing bound is now 2001-01-01 or today. `:forms ["8-K"]` also matches 8-K/A (EFTS behaviour, now documented).
- Pre-2001 filings whose index lists only the complete submission text file: `e/html`, `e/text`, `e/items`, `e/tables` and `e/save!` returned nil or `{}`; the primary document now falls back to the `.txt` (AAPL's 1994 10-K yields items 1–14).
- `e/filings` with `:start-date` fetched every older submissions chunk (71 for JPM); the date-descending seq now stops at the start date.
- `e/latest-effective-filing` walked the full filing history when no amendment existed; it now stops at the latest original.
- `download-filings!`: a failing chunk fetch mid-list replaced the completed results with one error; the filing list is realised before any download.
- Glued all-caps headings (`ITEM 1BUSINESS`, `ITEM 2PROPERTIES`) parsed as items 1B and 2P.
- 13F `:holdings` columns came out in hash order; 13G filings spelling `issuerCusip` left `:cusip` nil.
- A plain-text SGML filing containing `<TABLE>` was parsed as HTML.
- 10-Q `:val-ltm` and derived Q4 for reclassified items were overwritten by 10-K 3-month footnote rows (PG `"OIADP (Compustat)"` LTM 6.2B → 15.6B).
- `:view :as-reported :shape :wide` put every concept in one nil column.
- `:val-ltm` was nil in every Q2/Q3 for 12/12/12/16-week fiscal calendars (COST); quarters now chain by adjacency.
- `e/financials` fetched the company facts three times.
- Reclassification: a formula whose operands are all optional and absent emitted a 0 row.
- FSDS files are read as UTF-8 (the streaming reader used ISO-8859-1); a missing FSDS quarter is detected by HTTP status rather than the error message.
- `(edgar.financials/get-financials t :view nil)` threw; a nil view means `:normalized`.

### Changed
- `edgar.api` loads the form parsers itself: `e/obj` parses Forms 3/4/5, 13F and Schedules 13D/G without `(require '[edgar.forms])`.
- New internal namespace `edgar.util` (date parsing, window arithmetic, restatement dedup, column-ordered datasets).
- `deps.edn` declares `org.clojure/clojure` 1.12.2 (the pom listed no Clojure dependency); the build's `jar` task cleans `target/` first.
- Docstrings corrected: `e/html` (XML and plain-text documents), `e/exhibit` (exact `:type` match — ask for `"EX-21.1"`), `e/exhibits`, `e/xbrl-docs`, `e/frame` (`:start`), `e/daily-filings` (`:cik` is the first listed filer — filter on `:filers`), `:remove-tables?` (drops every table), `e/income`/`e/cashflow` (`:as-reported` has no derived columns), `"XRD (Compustat)"` (only the extension-tag variant needs FSDS).

## [0.5.0] — 2026-10-15

Several changes below break existing callers; they are marked **Breaking**.

Re-measured against a current-vintage Compustat extract (21-firm sample plus MO, FY2009–2025, FSDS enabled), old vs new rules on the identical extract: 18 firm-years change, all from miss to match — none the other way. 13 industrials: OIADP 27.6% → 28.1%, OIBDP 22.2% → 22.6% (PG FY2019 now within 1%), every other income and balance-sheet rate unchanged; AMZN XSGA 13/17 → 16/17; MO Gross Profit 0/17 → 9/17 and Revenue (Compustat) 6/17 → 10/17. Special Items move toward Compustat for INTC FY2024 (−45% → −2%) and PG FY2019 (−82% → −4%).

### Changed
- **Breaking: every returned CIK is a zero-padded 10-digit string** — including `e/filing-by-accession`'s `:cik` and `:filers` (0.4.2 returned them unpadded) and the `:cik` of quarterly-index rows. Integer CIKs are accepted everywhere (`(e/cik 320193)`), share-class dots resolve (`"BRK.B"` = `"BRK-B"`), and CIK → ticker returns the primary share class (`GOOGL`, `BRK-B`) instead of an arbitrary one.
- **Breaking: `e/search` matches current entity names** through the EDGAR company-name index (the typeahead behind the SEC's company search box) and returns `{:cik :name :tickers}`, best match first, at most 10 results. It previously ran a full-text search and returned `{:entity-name :cik :location :inc-states}` built from filing hits.
- **Breaking: `e/daily-filings` reads the EDGAR daily master index.** The result is complete, one map per filing: `{:accessionNumber :form :filingDate :cik :companyName :filers}`, where `:filers` lists every filer as `{:cik :name}`. The previous full-text-search implementation returned one entry per indexed document and stopped at 10,000. `:form` is an exact match (`"8-K"` no longer includes `"8-K/A"`). Weekends, holidays, future dates, and the current day before the SEC publishes its index (around 10 pm ET) return an empty seq. `:periodOfReport` and `:items` are removed — filter 8-Ks by item with `e/search-filings`, whose hits carry `:items`.
- **Breaking: `e/search-filings` returns one map per filing** (a filing whose exhibits also matched used to appear several times): the raw search source map plus `:accessionNumber :cik :form :filingDate :companyName :document :url`, so hits work directly with `e/html`, `e/text`, `e/filing-by-accession`.
- **Breaking: Forms 3/4/5 `:date-of-change` is renamed `:date-of-original-submission`** — it always held the XML `dateOfOriginalSubmission`.
- **Breaking: amendments are saved under `10-K_A/`** instead of a nested `10-K/A/` directory (`e/save!`, `e/save-all!`, `download-filings!`).
- **Breaking: `e/pivot` output changed** — panel input keeps every company (rows keyed by `[ticker end]`, with a `:ticker` column), each cell prefers the longest window and then the latest `:filed`, and units are never mixed: a concept reported in several units gets one `"<concept> (<unit>)"` column per unit.
- **Breaking: `"Other Operating Expense"` no longer contains `OtherOperatingIncomeExpenseNet`** (income-signed, unlike `OtherCostAndExpenseOperating`); it is its own line item, `"Other Operating Income (Expense), Net"`.
- `(e/filing t :form "10-K/A")` and `(e/filings t :form "10-K/A")` return the amendments themselves without `:include-amends? true`.
- `e/tables`: `:min-rows` alone governs the row minimum (`:min-rows 1` keeps single-row tables).
- Disk cache: the full-index and daily-index files use the 24-hour JSON TTL instead of the 30-day document TTL. Quarterly index downloads (30–60 MB) bypass the in-memory cache, and `download-index!` writes the fetched bytes without caching them. `edgar.core/edgar-get` gains a `:cache` option (`:all` | `:disk` | `:none`).
- With the FSDS cache enabled, 10-Q queries no longer trigger FSDS downloads: the annual rows they build for Q4/LTM derivation are reclassified without FSDS operands, so Q4 = FY − 9M never mixes an FSDS-assisted fiscal year with a non-FSDS 9-month YTD.
- Reclassification engine: `[:neg x]` operands (negated value; nests with `[:opt]`), and `:lte` / `:ties` guards (`[:ties formula b tol]` passes when a formula ties out to a reported value within a relative tolerance).
- The missing-identity error now points to `e/init!`.
- Form parsers share a new `edgar.forms.xml` helper namespace; XML parsing disables external entities and DTD loading (XXE hardening).
- Test fixtures moved from `resources/fixtures` to `test-resources/fixtures`, so they no longer ship in the jar.

### Fixed
- **10-K `:shape :wide` picked quarterly-footnote rows.** 10-K facts include 3/6/9-month windows sharing the fiscal-year end date, which the wide pivot could take over the annual figure (AAPL FY2018 Net Income showed 14.1B instead of 59.5B). Wide output keeps only the annual windows; long 10-K output keeps every window and carries `:duration-months`.
- **Share counts and per-share amounts were differenced and summed** in `:val-q` / `:val-ltm` (AAPL LTM basic shares about 3× too high). For these units `:val-q` is the reported 3-month value or nil, and `:val-ltm` is nil.
- **`"XSGA (Compustat)"` included the other-operating line** (via the e-commerce fallback, and through the shared `"Other Operating Expense"` chain). Measured against the licensed sample, XSGA fits best without the line whichever way it is tagged, so it is now excluded (AMZN XSGA 13/17 → 16/17 firm-years within 1%).
- **`"Special Items (Compustat)"` double counted goodwill impairments** already contained in `AssetImpairmentCharges` (INTC FY2024, PG FY2019, several KO years). A guarded rule drops the separate goodwill operand when it is no larger than the aggregate.
- **`"Gross Profit (Compustat)"` uses excise-netted revenue** (`"Revenue (Compustat)"`) when available; a tie-out rule nets excise from revenue tagged `...ExcludingAssessedTax` that still contains it (MO FY2016–2019). Known gap: MO from FY2020 tags excise as `OtherCostOfOperatingRevenue`, which is not netted.
- Item extraction: 10-K items 1C and 9C no longer overwrite items 1 and 9, and items 1C, 9C, and 16 are recognized. 10-Q extraction (previously `{}`) works, with part-prefixed ids (`"I-2"` = MD&A, `"II-1A"` = Risk Factors). 8-K extraction recognizes every current item (1.01–9.01). Plain-text (pre-2001) filings honor `:only` and recognize headings with punctuation. Known limitations: PART headings are not section boundaries, and 8-K titles are empty when the title sits in a separate HTML element.
- `e/tables` preserves column order for tables with more than 8 columns.
- 13F-HR cover and summary fields now parse on real filings — `:period-of-report` (ISO date), `:report-type`, `:form13f-file-number`, `:table-entry-count`, `:table-value-total`, `:other-managers-count` were always nil; `:form` reflects `13F-HR/A`. New `:amendment-number` and `:amendment-type`. Dollar values are as filed: whole dollars from January 2023, thousands before.
- Forms 3/4/5 relationship flags accept `true`/`false` (they were always false on recent filings). New `:reporting-owners` lists every owner of a joint filing (`:reporting-owner` remains the first).
- `:view :as-reported` honors `:as-of`.
- `e/filing-by-accession` accepts undashed accession numbers as documented.

### Added
- `e/filing-index` — a filing's index: `{:files [{:sequence :name :type :description :size} ...] :formType :filingDate :filers [...]}`.
- Line item `"Other Operating Income (Expense), Net"` (income statement, all views).

### Removed
- **Breaking:** `edgar.forms.form4` (use `edgar.forms`, or `edgar.forms.ownership/parse-ownership-form`).
- **Breaking:** public vars `edgar.filings/get-filing`, `edgar.filings/latest-filing`, and `edgar.core/archives-path` (use `e/filing`).
- The legacy `:columns` + vector-of-vectors branch of the frames parser (the SEC frames API never returns that shape).

## [0.4.2] — 2026-10-15

### Fixed
- **`filing-by-accession` on agent-filed accessions.** The CIK was taken from the accession prefix, which is the *submitter* — for agent-filed filings (Donnelley `0001193125…`, Toppan, …) the agent, whose archive path 404s, so `e/html`, `e/text`, `e/exhibits` and friends failed. `:cik` now comes from the entities on the filing index (Filer, then Subject, Issuer, Reporting, Filed by), falling back to the submitter only when the index lists none.

### Added
- `filing-by-accession` maps and `filing-index` results carry `:filers` — every entity on the filing index as `{:name :role :cik}`.

## [0.4.1] — 2026-08-01

### Fixed
- **Equity reclassification for filers tagging only the including-NCI equity concept**: the SEQ rule's guard could never fire, so SEQ/CEQ included NCI and TEQ double-counted it. New standardized-view identities re-derive parent equity (`Stockholders Equity` = `Total Equity` − `Noncontrolling Interest`) and `Total Liabilities` prefers L&E − `Total Equity`: SEQ/TEQ 93.7% → **97.3%**, CEQ 95.9% → **97.3%**, standardized Total Liabilities 79.2% → **95.5%** (13 industrials, FY2009–2025).
- **`"DLC (Compustat)"` no longer double-counts leases already inside a combined current-portion tag** (new `:concept-in` guard op, which fails on identity-derived rows): DLC 55.6% → **57.5%**.
- Income `:view :compustat` is **industry-gated**: on bank, insurance and REIT chains, where the rules' operands don't exist, it equals `:standardized`. Income rates unchanged.
- Cross-statement aux rows no longer shadow same-labeled statement rows as rule operands (a REIT's first-class `"D&A"` line beats the cash-flow aux `"D&A"`).
- FSDS: only a 404 marks a quarter as missing — transient failures rethrow and stay retryable. FSDS operands inject only into USD annual windows of 340–380 days, so transition-period fiscal years no longer receive 4-quarter values.
- `e/search-filings` with only `:end-date` returned unfiltered results.
- `download-batch!` no longer aborts the whole batch when one ticker fails to resolve; that ticker gets a `{:status :error}` envelope.
- Disk cache: the key includes the `:raw?` flag (raw and JSON fetches of one URL no longer share an entry); failed tmp-file renames are cleaned up.
- Filing-index parsing no longer truncates multi-word form types (`"SCHEDULE 13D"`, `"SC 13G/A"`, `"S-1 MEF"`).
- `(e/panel [] :as-of ...)` threw instead of returning an empty dataset.
- `e/item` normalizes the item id like `e/items` (`"i 1"` finds Item I-1).
- `core/clear-cache!` also clears the cached ticker maps; the JSON response cache is bounded at 256 entries.
- **`"DLTT (Compustat)"` no longer adds `OtherLongTermDebt`**, typically a component of `LongTermDebtNoncurrent`: DLTT 78% → **86%**. Removing the operating-lease operands drops DLTT to 51% and DLC to 38%, confirming that post-ASC-842 operating leases belong in the debt items.
- **`"Special Items (Compustat)"` no longer double-counts impairments** already inside the filer's restructuring concept (`RestructuringCostsAndAssetImpairmentCharges`, `RestructuringSettlementAndImpairmentProvisions`).

### Changed
- `:concepts` is accepted (and Malli-validated) on `e/income` / `e/balance` / `e/cashflow` — previously documented but silently dropped; `e/tables` arguments are Malli-validated too.
- **One formula engine**: standardized-view identities evaluate through `edgar.reclass/eval-formula` and may use `[:opt x]` operands; `edgar.financials/duration-months` is public and the single window classifier.
- Rulesets assert their `:statement` key at load time.
- Removed the unused `:gt` guard op and the `"Fulfillment Expense"` concept chain entry (companyfacts never carries extension tags; the label arrives via FSDS).
- `examples/compustat_validation.clj` gains `validate-balance-reclass` and `annual-balance-compustat-items` for the balance-sheet study.
- Doc corrections: Compustat line items documented with their `" (Compustat)"` suffix; Malli-validation scope and retry behavior described accurately.

## [0.4.0] — 2026-07-31

### Added
- **FSDS-powered reclassification — `(e/enable-fsds!)`** feeds the SEC Financial Statement Data Sets into the income statement's `:view :compustat`: statement placement from `pre.txt` (a filer presenting D&A as its own line keeps `"COGS (Compustat)"` as reported) and extension-tag operands from `num.txt` (AMZN's fulfillment and technology lines feed `"XSGA (Compustat)"` and a new `"XRD (Compustat)"`). Quarter zips download on first use into `~/.edgarjure/fsds` (50–130 MB each, shared across companies; `e/disable-fsds!` turns it off); without the cache the new rules don't fire. New `[:present]` / `[:absent]` guards.
- Measured on 13 industrials, FY2009–2025, FSDS enabled: XRD **84%** (new), XSGA 58%, COGS 52%, DP 73%; AMZN COGS 0 → 17/17, XSGA 0 → 13/17, XRD 16/17. (The XSGA and COGS figures did not reproduce on the extract used from 0.5.0 on; absolute rates depend on the extract, so later entries compare rule versions on one extract.)
- **OIADP add-backs extended**: In-Process R&D charges join the special items, and nonrecurring asset-sale gains/losses (`"Gain (Loss) on Sale of Assets"`) leave `"OIADP (Compustat)"` by subtraction. No recurrence gate yet — every tagged asset-sale gain or loss is treated as nonrecurring.
- **Compustat balance-sheet reclassification — `:view :compustat` now covers `e/balance`** (`resources/edgar/reclass/compustat-balance.edn`), building each aggregate from the filer's tagged components: `"RE (Compustat)"` (retained earnings incl. AOCI; 12% → **96%** within 1%), `"DLTT (Compustat)"` (76%), `"DLC (Compustat)"` (63%), `"RECT (Compustat)"` (61%), `"MIB (Compustat)"` / `"MIBN (Compustat)"` / `"MIBT (Compustat)"`, `"PSTK (Compustat)"`, `"SEQ (Compustat)"` / `"CEQ (Compustat)"` / `"TEQ (Compustat)"` (93–95%) and `"CHE (Compustat)"` (76%). Post-ASC-842 operating lease liabilities count toward DLC/DLTT, as observed in current Compustat data.
- New balance-sheet chains feeding the rules (available in all views): `"AOCI"`, `"Redeemable Noncontrolling Interest"`, `"Redeemable Preferred Stock"`, `"Current Portion of Long-Term Debt"`, `"Short-Term Borrowings"`, `"Finance Lease Liability (Current)"`/`"(Non-Current)"`, `"Capital Lease Obligation (Current)"`/`"(Non-Current)"`, `"Operating Lease Liability (Current)"`/`"(Non-Current)"`, `"Other Long-Term Debt"`, `"Marketable Securities (Current)"`, `"Trading Assets"`, `"Nontrade Receivables"`, `"Income Taxes Receivable"`, `"Notes and Loans Receivable (Current)"`, plus new concepts on existing chains.
- `edgar.fsds` query functions: `find-submissions`, `presentation` (a statement's lines as presented, extension tags included), `statement-placement` (tag → statement codes), `facts-for`.
- Long-name aliases: `e/income-statement`, `e/balance-sheet`, `e/cash-flow`, `e/search-companies`. Passing a single options map (`(e/income "AAPL" {:form "10-Q"})`) and nil from `e/filing` with `:n` out of range are now documented.
- **Clerk tour notebook** — `examples/notebook.clj` walks the library as a live Clerk notebook; new `:clerk` deps alias.
- **Opt-in disk cache** — `(e/enable-disk-cache!)` persists HTTP responses across sessions under `~/.edgarjure/http-cache` (JSON endpoints 24 h, filing documents 30 days; `:dir`, `:ttl-json-ms`, `:ttl-raw-ms`). Companions: `e/disable-disk-cache!`, `e/clear-disk-cache!`, `e/disk-cache-stats`.
- **Schedule 13D/13G parsers** (`edgar.forms.schedule13`) for the structured-XML era (from December 2024: `SCHEDULE 13D`/`SCHEDULE 13G` and `/A`). Both schemas normalize to one shape: issuer with CUSIP, event date, 13G rule, and one entry per reporting person. Legacy `SC 13D`/`SC 13G` fall through to the raw-HTML default.
- **Form 3 and Form 5 parsers** — `edgar.forms.ownership` parses Forms 3, 4, 5 and their `/A` amendments through one parser. Results include `:holdings` alongside `:transactions` and `:nature-of-ownership` on indirect positions; Form 4/A now dispatches too.
- **REIT income statement chains** (`resources/edgar/concepts/reit-income.edn`, verified against SPG, PLD, O, EQR, WELL, AMT): `"Rental Revenue"`, `"Real Estate Revenue"`, `"Tenant Reimbursements"`, `"Property Operating Expense"`, `"Real Estate Taxes"`, `"Ground Rent Expense"`, `"D&A"`, `"Impairment of Real Estate"`, `"Gain on Sale of Real Estate"`. Available via `:industry :reit`.
- Industry routing fix: **SIC 6798 (REITs) now routes to `:reit`** — the previous range (6500–6553) never matched equity REITs.

### Changed
- `"Current Debt"` maps only the combined `DebtCurrent` concept; filers tagging components get it as a derived row (`"Current Portion of Long-Term Debt"` + `"Short-Term Borrowings"`, with single-component fallbacks) instead of a first-match fallback that could pick one component — the component sum Compustat DLC reflects (DLC = DD1 + NP holds throughout the licensed sample).
- `"Short-Term Investments"` no longer includes `MarketableSecuritiesCurrent` (now `"Marketable Securities (Current)"`), so filers presenting both get both.
- Identity engine: identities for one target apply first-match-wins within a pass (`"Total Equity"` could appear twice).
- New `:dp-components` rule: `"DP (Compustat)"` prefers Depreciation + Amortization of Intangibles over the combined cash-flow D&A tag, whose scope can exceed DP (AMZN FY2023: 48.7B tagged vs 30.9B); COGS and OIBDP consume it. Out-of-sample FY2016+: DP 66% → **76%**, COGS 43% → **54%**, OIBDP 21% → **33%**.

## [0.3.0] — 2026-07-28

### Added
- **Reclassification rule engine and `:view :compustat` (`edgar.reclass`)**: `(e/income "AAPL" :view :compustat)` adds line items approximating Compustat item definitions alongside the originals. Rules are EDN data (`resources/edgar/reclass/compustat-income.edn`) applied on top of the standardized view; reclassified rows carry `:method :reclassified`, `:rule`, and `:derived-from`. Inspect them with `(e/reclass-rules :income)`. Income statement only.
- Line items: `"COGS (Compustat)"` (cost of revenue less D&A from the cash flow), `"XSGA (Compustat)"` (SG&A + R&D, with an S&M + G&A fallback), `"OIADP (Compustat)"` (operating income plus restructuring, impairment and similar add-backs), `"OIBDP (Compustat)"`, `"XOPR (Compustat)"`, `"DP (Compustat)"`, `"Special Items (Compustat)"`, `"Gross Profit (Compustat)"`, `"Revenue (Compustat)"` (net of excise taxes). Fitted on a 2016-vintage extract (18 industrials, FY2010–2015): the COGS/XSGA/OIADP trio moves from ~15% to ~47% within 1%; out of sample (FY2016+, ~190 firm-years) COGS 43%, XSGA 43%, OIADP 34%, DP 66%.
- Engine semantics: formula ops `:=`/`:+`/`:-`/`:neg-sum` with `[:opt x]` operands; guards `[:lt a b]`, `[:gt a b]`, `[:concept-not-in item #{...}]`; rules apply in order, first rule per target wins, iterated to a fixpoint. 10-Q reclassified rows get `:val-q`/`:val-ltm`.
- New income statement chains feeding the rules (available in all views): `"Selling and Marketing Expense"`, `"General and Administrative Expense"`, `"Marketing Expense"`, `"Fulfillment Expense"`, `"Other Operating Expense"`, `"Excise Taxes"`, `"Restructuring Charges"`, `"Impairment Charges"`, `"Goodwill Impairment"`, `"Acquisition-Related Costs"`, `"Litigation Settlement"`.
- `examples/compustat_validation.clj`: `annual-compustat-items` and `validate-reclass`.

### Changed
- **License switched from EPL-2.0 to Apache-2.0**, from this version on; artifacts through 0.2.1 were published under EPL-2.0.
- Added CONTRIBUTING.md (no pull requests; bug reports via GitHub issues welcome).

## [0.2.1] — 2026-07-15

### Added
- **Compustat validation study — `examples/compustat_validation.clj`**: the standardized statements graded against licensed Compustat data for 19 large filers (13 industrials/tech, 3 banks, 3 insurers). Annual core items FY2010–2015: **89.4%** within 1%; quarterly `:val-q` vs single-quarter Compustat items **98.6%**, quarterly Total Assets **100%**. Reclassification-sensitive items (COGS, SG&A, R&D, Operating Income) match ~15–18%.
- `edgar.validation/compare-to-benchmark` options: `:date-tolerance-days` (Compustat month-end vs exact 52/53-week dates; exact matches win), `:value-key` (compare `:val-q` or any column), and duration-aware ranking (the longest window wins per date, so an annual benchmark never matches a Q4 row).
- Derived bank line item `"Total Gross Revenue"` = Interest Income + Noninterest Income (`:view :standardized`).
- Income chains: `"Shares Basic"`/`"Shares Diluted"` for banks and insurers; `"Interest Expense"`; `"Depreciation"` / `"Amortization of Intangibles"` with a derived `"D&A"`; more `"Cash and Equivalents"` fallbacks (`CashAndDueFromBanks`, the ASU 2016-18 restricted-cash tag).
- Balance sheet line items `"Accounts Payable"`, `"Current Debt"`, `"Income Taxes Payable"`, `"Preferred Stock"`, `"Noncontrolling Interest"`, `"Total Equity"`; cash flow line items `"Stock Issued"`, `"Deferred Taxes (CF)"`; more tag variants for `"LT Debt Issued"`/`"LT Debt Repaid"` and `"Short-Term Investments"`.
- Identities: `"Total Equity"` = Stockholders Equity + Noncontrolling Interest (the incl-NCI tag now labels as Total Equity), and derived-only `"Working Capital"`.

### Changed
- Cash-flow chains: `...ContinuingOperations` variants for operating/investing/financing cash flow; income tax gains the `...ContinuingOperations` fallback.
- Bank income chains: `"Total Revenue"` is the gross `Revenues` tag; `RevenuesNetOfInterestExpense` is a separate `"Total Net Revenue"` line item.

## [0.2.0] — 2026-07-15

Four features that passed the offline test suite were broken against the live SEC API (the fixtures encoded API formats that don't exist). All four are fixed, the fixtures now mirror the real formats, and the financial-statement layer gains standardization features.

### Fixed
- **`e/frame` returned garbage** against the real frames endpoint (map-shaped `:data`). Fixed; `:cik` is zero-padded and results are sorted by `:val` descending, as documented.
- **`get-quarterly-index` returned zero rows** (it split the fixed-width `company.idx` on `|`). It now reads `master.idx`; 2024 Q1 parses 370,304 filings.
- **10-Q `:val-q` was wrong and `:val-ltm` was always nil** (derivation keyed on the filing's `:fy`/`:fp`, and 10-Q data has no fiscal Q4). Rewritten on actual date windows: a new `:duration-months` column (3/6/9/12, tolerant of 52/53-week calendars), quarters from YTD differencing, 10-K rows giving Q4 = FY − 9M, and LTM from four consecutive quarters.
- `to-wide` prefers the longest-duration (YTD) row when a 3-month and a YTD row share `[end line-item]`.
- `add-market-cap-rank` threw on every call.
- Form 4: a filing without an XML attachment returns nil instead of crashing the XML parser.

### Added
- **Three statement views** (`:view` on `e/income`, `e/balance`, `e/cashflow`, `e/financials`): `:as-reported` (as filed, no dedup or mapping), `:normalized` (default; rows carry `:method :direct`), and `:standardized` (missing line items imputed from identities such as Gross Profit = Revenue − Cost of Revenue and Free Cash Flow = OCF − Capex; derived rows carry `:method :derived` and `:derived-from`).
- **Industry-aware routing** (`:industry`): banks (SIC 6000–6199, 6712) and insurers (6300–6399, 6411) get industry-specific income chains; `:industry :standard` forces the generic ones.
- **Concept chains as EDN** under `resources/edgar/concepts/`, exposed as public vars; `(e/concepts-for :income :industry :bank)`.
- **Unmapped-concept logging**: `e/unmapped-concepts`, `e/clear-unmapped-concepts!`, `e/save-unmapped-concepts!`.
- **`edgar.validation/compare-to-benchmark`**: match rate, mismatches with relative diffs, and missing items against a benchmark dataset.
- **`edgar.fsds`**: SEC Financial Statement Data Sets — `quarter-url`, `download-quarter!`, `load-table` (`:sub`/`:num`/`:pre`/`:tag`).
- **Bounded raw-response cache**: filing HTML, indexes and `.idx` files (64 entries, 1 h), so `e/text` → `e/items` → `e/tables` hits the network once.
- **EFTS pagination**: `e/search-filings` and `e/search` page with `from=` offsets, so `:limit` beyond one page works.

### Changed
- `get-filings` fetches extra submission chunks lazily and keeps the result date-descending past the first ~1,000 filings.
- Item extraction: `:title` no longer truncates mid-word, and the heading is no longer repeated at the start of `:text`.
- Capex chain gains `PaymentsToAcquireProductiveAssets` (AMZN).

## [0.1.9] — 2026-03-17

### Fixed
- `ticker->cik` no longer rebuilds the ticker map on every call (#2).
- `e/tables`: blank header cells no longer collapse columns and misalign data rows (#6).
- `:shape :wide` dropped `:val-q`/`:val-ltm`; 10-Q wide output now has `"<line-item> (Q)"` / `"<line-item> (LTM)"` columns (#8).
- `e/filing` no longer fetches every page to return the nth filing (#1).
- Filing index `:name` came from the wrong cell when the description had a link (#5).
- `get-quarterly-index`: fragile header skip and wrong column mapping (#3).
- Plain-text item extraction included the heading line in `:text` (#12).
- `get-concept-frame` crashed on a missing `:columns` key (#13).
- `e/cik`, `e/company`, `e/company-name`, `e/company-metadata` now validate their arguments (#15).
- Cache eviction no longer scans the whole cache on every write (#10).
- `filing-save-all!` deduplicates repeated file names in the filing index (#9).

### Added
- `e/filing-document` — fetch a named document from a filing as a string (#16).

## [0.1.8] — 2026-03-17

### Added
- Quarterly and LTM derivation for 10-Q income and cash-flow queries: `:val-q` (single quarter from YTD subtraction) and `:val-ltm` (sum of four consecutive quarters); nil when a prior period is missing.

## [0.1.7] — 2026-03-17

### Fixed
- Overlapping chain candidates produced duplicate rows per period (e.g. two cash concepts for `"Cash and Equivalents"`); the highest-priority concept now wins per `[line-item unit start end]`.

### Changed
- `"Long-Term Debt"` prefers `LongTermDebtNoncurrent` over `LongTermDebt`.
- `"Operating Expenses"` (`OperatingExpenses`) and `"Total Costs and Expenses"` (`CostsAndExpenses`) are separate line items.
- `"Net Change in Cash"` prefers the ASU 2016-18 tag (including restricted cash and FX effects).

## [0.1.6] — 2026-03-16

### Fixed
- Restatement dedup (and the `:as-of` panel path) collapsed distinct 10-Q duration windows ending on the same date; the key is now `[concept unit start end]`.
- A company that switched tags (e.g. `SalesRevenueNet` → `Revenues`) lost its historical periods; every present chain candidate now maps to its label.
- Item extraction returned `{}` for amended forms (`10-K/A`, `10-Q/A`, `8-K/A`).
- `company-cik` throws `::edgar.company/unknown-ticker` for unknown tickers instead of returning nil.
- `search-companies` returned raw search payloads; it returns `{:entity-name :cik :location :inc-states}`.
- `download-filings!` returns `{:status :skipped :reason :no-primary-doc}` when a filing has no primary document.
- The TTL cache now evicts expired entries.

## [0.1.5] — 2026-03-16

### Fixed
- HTTP retry on 429/5xx and transport errors never fired; exhausted retries throw `::http-error`.
- Plain-text filings never reached the plain-text item extractor.
- Item text bled across unrequested items when using `:items`.
- 13F-HR: manager and summary were nil for two-document submissions.
- `e/save!` / `e/save-all!` corrupted binary attachments (PDF, images, spreadsheets).
- `download-filings!` with `:download-all?` returns one `{:status :ok :paths [...]}` envelope per filing.
- nil address and period fields came back as the string `"nil"`.
- `e/tables`: one blank cell made a numeric column a string column; `<th>`/`<td>` order was lost; `colspan` was ignored; nil or plain-text HTML crashed.
- `xbrl/get-facts-dataset` `:sort :asc` was a no-op and `:desc` relied on SEC delivery order.
- Docstrings of `e/items`, `e/item`, `e/exhibits` described the wrong return shapes.

## [0.1.4] — 2026-03-16

### Fixed
- Every ticker → CIK lookup threw `MissingFormatArgumentException`; CIK → ticker lost precision on large CIKs.
- `e/text` duplicated text and leaked `<script>`/`<style>` content.
- Form 4: the inline-XBRL instance could be parsed instead of the ownership XML.
- Statement `:line-item` sorted Z→A within each period.
- `e/pivot`, `e/panel :as-of` and `:shape :wide` dropped rows with nil-valued columns (wide balance sheets came out empty).
- Filers with more than 1,000 filings (e.g. AAPL) were truncated to the first 1,000.
- `latest-effective-filing` never found amendments.
- 13F-HR: holdings were empty for modern submissions (separate `INFORMATION TABLE` attachment, `ns1:`-prefixed XML).
- Statements dropped most rows: instant/duration filtering keyed on the sparsely populated `:frame`.
- Statement functions crashed on an empty facts dataset.

## [0.1.3] — 2026-03-15

### Fixed
- Form 4 / Form 144 filings 404'd on a phantom `.html` index entry.
- Non-breaking spaces broke item-heading detection and number parsing in tables.
- `e/text` included CSS and JavaScript.
- `e/save!` threw when a filing had no primary document (now returns nil).
- `filing-by-accession` always returned `:filingDate nil`.

### Added
- `:url` on every filing map (the primary document's archive URL).
- `e/doc-url` — the archive URL of any named document in a filing.

## [0.1.2] — 2026-03-15

*(entry reconstructed 2026-08-01 from the v0.1.1..v0.1.2 git history — the release predates the consolidated changelog)*

### Added
- `:url` field on filing maps in `enrich-filing`.

### Fixed
- 404 on Form 4/144 filings: phantom `.html` entries filtered from the filing index.
- Non-breaking-space handling in `tables` node-text and item extraction; primary-doc deduplication.
- `filing-text` no longer leaks CSS/JS; `filing-save!` handles a nil primary document.
- `filing-by-accession` returned `:filingDate` always nil.

## [0.1.1] — 2026-03-14

### Fixed
- Archive URLs used the zero-padded CIK, which the SEC archives reject.
- The filing index is parsed from the HTML index page (the SEC no longer serves the JSON index for recent filings).

## [0.1.0] — 2026-03-14

Initial release.

- `edgar.api` facade (`[edgar.api :as e]`) with Malli-validated arguments (`edgar.schema`); ticker or CIK accepted everywhere; keyword options.
- Company lookup: `e/cik`, `e/company`, `e/company-name`, `e/company-metadata` (SIC, fiscal year end, state of incorporation, addresses, former names), `e/search`.
- Filings: `e/filings` and `e/filing` with full pagination for large filers and amendments excluded by default (`:include-amends? true`), `e/latest-effective-filing`, `e/filing-by-accession`, `e/daily-filings`, `e/search-filings`, `e/filings-dataset`.
- Filing content: `e/html`, `e/text`; item-section extraction (`e/items`, `e/item`) returning full section bodies as `{:title :text :method}` for HTML and plain-text 10-K and 10-Q filings, plus `extract/batch-extract!`; HTML tables as datasets (`e/tables`); exhibits and XBRL documents (`e/exhibits`, `e/exhibit`, `e/xbrl-docs`); `e/save!` and `e/save-all!`.
- Form parsers through the `filing-obj` multimethod (`e/obj`): Form 4 and 13F-HR (XML era), loaded together by `edgar.forms`.
- XBRL: `e/facts` (with `:label` and `:description`), `e/concepts`, `e/frame`.
- Financial statements: `e/income`, `e/balance`, `e/cashflow`, `e/financials` with concept fallback chains, restatement dedup, duration/instant filtering, `:shape :wide`, and point-in-time `:as-of`.
- Panels: `e/panel` (with `:as-of`) and `e/pivot`.
- Bulk downloads (`edgar.download`): `download-filings!`, `download-batch!` (bounded `:parallelism`, `:skip-existing?`), `download-index!`, returning `{:status :ok|:skipped|:error ...}` envelopes.
- HTTP layer: SEC User-Agent identity (`e/init!`), 10 requests/second rate limiter, in-memory TTL cache, retry with exponential backoff on 429/5xx.
- Four unused dependencies (`next.jdbc`, `honeysql`, `sqlite-jdbc`, `datajure`) moved to an optional `:future` alias.

[Unreleased]: https://github.com/clojure-finance/edgarjure/compare/v0.5.2...HEAD
[0.5.2]: https://github.com/clojure-finance/edgarjure/compare/v0.5.1...v0.5.2
[0.5.1]: https://github.com/clojure-finance/edgarjure/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/clojure-finance/edgarjure/compare/v0.4.2...v0.5.0
[0.4.2]: https://github.com/clojure-finance/edgarjure/compare/v0.4.1...v0.4.2
[0.4.1]: https://github.com/clojure-finance/edgarjure/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/clojure-finance/edgarjure/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/clojure-finance/edgarjure/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/clojure-finance/edgarjure/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/clojure-finance/edgarjure/compare/v0.1.9...v0.2.0
[0.1.9]: https://github.com/clojure-finance/edgarjure/compare/v0.1.8...v0.1.9
[0.1.8]: https://github.com/clojure-finance/edgarjure/compare/v0.1.7...v0.1.8
[0.1.7]: https://github.com/clojure-finance/edgarjure/compare/v0.1.6...v0.1.7
[0.1.6]: https://github.com/clojure-finance/edgarjure/compare/v0.1.5...v0.1.6
[0.1.5]: https://github.com/clojure-finance/edgarjure/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/clojure-finance/edgarjure/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/clojure-finance/edgarjure/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/clojure-finance/edgarjure/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/clojure-finance/edgarjure/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/clojure-finance/edgarjure/releases/tag/v0.1.0
