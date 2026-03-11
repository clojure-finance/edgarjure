(ns edgar.api
  "Unified power-user entry point for edgarjure.
   Require as: (require '[edgar.api :as e])

   Design principles:
   - Every function accepts ticker or CIK interchangeably
   - Keyword args throughout; no positional form/type args
   - Sensible defaults for taxonomy (us-gaap), unit (USD), form (10-K)
   - :concept accepts a string or a collection of strings
   - Functions that return datasets always return tech.ml.dataset, never seq-of-maps"
  (:require [edgar.core :as core]
            [edgar.company :as company]
            [edgar.filings :as filings]
            [edgar.filing :as filing]
            [edgar.extract :as extract]
            [edgar.xbrl :as xbrl]
            [edgar.financials :as financials]
            [edgar.dataset :as dataset]
            [edgar.tables :as tables-ns]
            [edgar.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Identity
;;; ---------------------------------------------------------------------------

(defn init!
  "Set the SEC User-Agent identity required for all HTTP requests.
   Must be called once before any other function.
   Example: (e/init! \"Your Name your@email.com\")"
  [name-and-email]
  (schema/validate! schema/InitArgs {:name-and-email name-and-email})
  (core/set-identity! name-and-email))

;;; ---------------------------------------------------------------------------
;;; Company
;;; ---------------------------------------------------------------------------

(defn cik
  "Resolve a ticker to a zero-padded 10-digit CIK string, or normalise a CIK.
   (e/cik \"AAPL\") => \"0000320193\""
  [ticker-or-cik]
  (company/company-cik ticker-or-cik))

(defn company
  "Return full SEC submissions metadata map for a company.
   Accepts ticker or CIK."
  [ticker-or-cik]
  (company/get-company ticker-or-cik))

(defn company-name
  "Return the company name string for a ticker or CIK."
  [ticker-or-cik]
  (company/company-name ticker-or-cik))

(defn company-metadata
  "Return a shaped metadata map for a company. Richer than (e/company ...).
   Extracts SIC, exchanges, addresses, fiscal year end, EIN, phone, website,
   former names, and more from the SEC submissions endpoint.
   Accepts ticker or CIK.

   Example:
     (e/company-metadata \"AAPL\")
     ;=> {:cik \"0000320193\" :name \"Apple Inc.\" :tickers [\"AAPL\"]
     ;    :exchanges [\"Nasdaq\"] :sic \"3571\"
     ;    :sic-description \"Electronic Computers\"
     ;    :entity-type \"operating\" :category \"Large accelerated filer\"
     ;    :state-of-inc \"CA\" :fiscal-year-end \"0926\"
     ;    :ein \"942404110\" :phone \"(408) 996-1010\"
     ;    :addresses {:business {:street1 ... :city ... :state ... :zip ...}
     ;                :mailing  {:street1 ... :city ... :state ... :zip ...}}
     ;    :former-names []}"
  [ticker-or-cik]
  (company/company-metadata ticker-or-cik))

(defn search
  "Search EDGAR for companies matching a name query.
   Options:
     :limit - max results (default 10)"
  [query & {:keys [limit] :or {limit 10}}]
  (schema/validate! schema/SearchArgs {:query query :limit limit})
  (company/search-companies query :limit limit))

;;; ---------------------------------------------------------------------------
;;; Filings
;;; ---------------------------------------------------------------------------

(defn filings
  "Return a lazy seq of filing metadata maps for a company.
   Options:
     :form            - form type string e.g. \"10-K\" \"10-Q\" \"8-K\" \"4\"
     :start-date      - \"YYYY-MM-DD\"
     :end-date        - \"YYYY-MM-DD\"
     :limit           - max results
     :include-amends? - include amended filings e.g. 10-K/A (default false)"
  [ticker-or-cik & {:keys [form start-date end-date limit include-amends?]
                    :or {include-amends? false}}]
  (schema/validate! schema/FilingsArgs {:ticker-or-cik ticker-or-cik
                                        :form form
                                        :start-date start-date
                                        :end-date end-date
                                        :limit limit
                                        :include-amends? include-amends?})
  (filings/get-filings ticker-or-cik
                       :form form
                       :start-date start-date
                       :end-date end-date
                       :limit limit
                       :include-amends? include-amends?))

(defn filing
  "Return the latest filing of a given form type for a company.
   Options:
     :form            - form type string (default \"10-K\")
     :n               - return the nth latest (0-indexed, default 0)
     :include-amends? - include amended filings (default false)"
  [ticker-or-cik & {:keys [form n include-amends?] :or {form "10-K" n 0 include-amends? false}}]
  (schema/validate! schema/FilingArgs {:ticker-or-cik ticker-or-cik
                                       :form form
                                       :n n
                                       :include-amends? include-amends?})
  (nth (filings/get-filings ticker-or-cik :form form :include-amends? include-amends?) n nil))

(defn latest-effective-filing
  "Return the most recent effective filing for a company and form type.
   If an amendment (e.g. 10-K/A) is newer than the original, it is returned instead.
   Options:
     :form - form type string (default \"10-K\")"
  [ticker-or-cik & {:keys [form] :or {form "10-K"}}]
  (filings/latest-effective-filing ticker-or-cik :form form))

(defn filings-dataset
  "Return a filing index for a company as a tech.ml.dataset.
   Same options as e/filings."
  [ticker-or-cik & opts]
  (apply dataset/get-filings-dataset ticker-or-cik opts))

(defn search-filings
  "Full-text search across EDGAR filings.
   Options:
     :forms      - vector of form types e.g. [\"10-K\" \"10-Q\"]
     :start-date - \"YYYY-MM-DD\"
     :end-date   - \"YYYY-MM-DD\"
     :limit      - max results (default 10)"
  [query & {:keys [forms start-date end-date limit] :or {limit 10}}]
  (schema/validate! schema/SearchFilingsArgs {:query query
                                              :forms forms
                                              :start-date start-date
                                              :end-date end-date
                                              :limit limit})
  (filings/search-filings query
                          :forms forms
                          :start-date start-date
                          :end-date end-date
                          :limit limit))

(defn daily-filings
  "Return a lazy seq of all filings submitted on a given date.

   date — a java.time.LocalDate or a \"YYYY-MM-DD\" string.
   Options:
     :form      — filter by form type e.g. \"10-K\" \"8-K\" \"4\"
     :filter-fn — arbitrary predicate applied to each result map

   Lazily pages through the EFTS search-index (100 per page).
   Typical trading day: ~1,000–2,000 filings; busy days up to ~6,000.

   Each result map has:
     :accessionNumber  — dashed accession number (use with e/filing-by-accession)
     :form             — form type string
     :filingDate       — \"YYYY-MM-DD\"
     :cik              — zero-padded 10-digit CIK (first filer)
     :companyName      — display name (may include ticker in parens)
     :periodOfReport   — \"YYYY-MM-DD\" or nil
     :items            — vector of 8-K item strings (or empty)

   Examples:
     ;; All filings on a date
     (e/daily-filings \"2026-03-10\")

     ;; Only 8-Ks
     (e/daily-filings \"2026-03-10\" :form \"8-K\")

     ;; 10-Ks filed by large-cap tech (filter by known CIKs)
     (e/daily-filings \"2026-03-10\" :form \"10-K\"
                      :filter-fn #(= (:cik %) \"0000320193\"))

     ;; Works with java.time.LocalDate too
     (e/daily-filings (java.time.LocalDate/of 2026 3 10))"
  [date & {:keys [form filter-fn]}]
  (filings/get-daily-filings date :form form :filter-fn filter-fn))

;;; ---------------------------------------------------------------------------
;;; Filing content
;;; ---------------------------------------------------------------------------

(defn filing-by-accession
  "Hydrate a filing map from an accession number string.
   Accepts dashed format: \"XXXXXXXXXX-YY-ZZZZZZ\"
   Returns a filing map ready for e/html, e/text, e/items, e/obj, etc.
   Example: (e/filing-by-accession \"0000320193-23-000106\")"
  [accession-number]
  (schema/validate! schema/FilingByAccessionArgs {:accession-number accession-number})
  (filing/filing-by-accession accession-number))

(defn html
  "Fetch the primary HTML document of a filing as a string."
  [filing-map]
  (filing/filing-html filing-map))

(defn text
  "Fetch the primary document of a filing as plain text (HTML stripped)."
  [filing-map]
  (filing/filing-text filing-map))

(defn items
  "Extract item sections from a filing as a map of item-id → text string.
   Options:
     :only           - set of item ids to extract e.g. #{\"7\" \"1A\"} (default all)
     :remove-tables? - strip numeric tables before extraction (default false)"
  [filing-map & {:keys [only remove-tables?] :or {remove-tables? false}}]
  (extract/extract-items filing-map
                         :items only
                         :remove-tables? remove-tables?))

(defn item
  "Extract a single item section from a filing. Returns text string or nil.
   (e/item f \"7\")  => MD&A text"
  [filing-map item-id]
  (extract/extract-item filing-map item-id))

(defn obj
  "Parse a filing into a structured form-specific map via filing-obj multimethod.
   Dispatches on :form. Requires form-specific parsers to be loaded, e.g.:
   (require '[edgar.forms.form4])"
  [filing-map]
  (filing/filing-obj filing-map))

(defn tables
  "Extract HTML tables from a filing as a seq of tech.ml.dataset objects.

   Options:
     :nth      — return only the nth table (0-indexed); returns a single dataset or nil
     :min-rows — only include tables with at least this many data rows (default 2)
     :min-cols — only include tables with at least this many columns (default 2)

   Layout and navigation tables (single-column, <2 data rows) are filtered out.
   Column names come from the first substantive row; duplicates are suffixed _1, _2, etc.
   Numeric columns are auto-detected: commas stripped, parentheses → negative.

   Examples:
     (def f (e/filing \"AAPL\" :form \"10-K\"))

     ;; All data tables
     (e/tables f)

     ;; Only substantial tables
     (e/tables f :min-rows 5)

     ;; Specific table by index
     (e/tables f :nth 0)
     (e/tables f :nth 2)"
  [filing-map & {:keys [nth min-rows min-cols] :or {min-rows 2 min-cols 2}}]
  (tables-ns/extract-tables filing-map :nth nth :min-rows min-rows :min-cols min-cols))

(defn save!
  "Download a filing's primary document to a directory.
   Returns the saved file path."
  [filing-map dir]
  (filing/filing-save! filing-map dir))

(defn save-all!
  "Download all documents in a filing to a directory.
   Returns a seq of saved file paths."
  [filing-map dir]
  (filing/filing-save-all! filing-map dir))

(defn exhibits
  "Return all exhibit entries from a filing's index as a seq of maps.
   Each map has :name :type :document :description :sequence.
   Exhibits have :type values beginning with \"EX-\" (e.g. \"EX-21\", \"EX-31.1\").

   Example:
     (def f (e/filing \"AAPL\" :form \"10-K\"))
     (e/exhibits f)
     ;=> ({:type \"EX-21\" :name \"aapl-20230930_g2.htm\" :description \"Subsidiaries\" ...} ...)

   Fetch an exhibit's content:
     (let [ex (e/exhibit f \"EX-21\")]
       (e/filing-document f (:name ex)))"
  [filing-map]
  (filing/filing-exhibits filing-map))

(defn exhibit
  "Return the first exhibit matching exhibit-type (e.g. \"EX-21\").
   Returns nil if no matching exhibit is found.

   Common exhibit types:
     \"EX-21\"   — subsidiaries list
     \"EX-23\"   — auditor consent
     \"EX-31.1\" — CEO Sarbanes-Oxley certification
     \"EX-31.2\" — CFO Sarbanes-Oxley certification
     \"EX-32\"   — Section 906 certifications

   Example:
     (def f (e/filing \"AAPL\" :form \"10-K\"))
     (def ex (e/exhibit f \"EX-21\"))
     (e/filing-document f (:name ex))"
  [filing-map exhibit-type]
  (filing/filing-exhibit filing-map exhibit-type))

(defn xbrl-docs
  "Return all XBRL-related document entries from a filing's index as a seq of maps.
   Covers EX-101.* linkbases: instance (.xml), schema (.xsd), calculation,
   label, presentation, and definition linkbases.

   Example:
     (def f (e/filing \"AAPL\" :form \"10-K\"))
     (e/xbrl-docs f)
     ;=> ({:type \"EX-101.SCH\" :name \"aapl-20230930.xsd\" ...}
     ;    {:type \"EX-101.CAL\" :name \"aapl-20230930_cal.xml\" ...} ...)"
  [filing-map]
  (filing/filing-xbrl-docs filing-map))

;;; ---------------------------------------------------------------------------
;;; XBRL / company facts
;;; ---------------------------------------------------------------------------

(defn facts
  "Fetch XBRL company facts as a tech.ml.dataset.
   Options:
     :concept - string or collection of strings to filter concepts
     :form    - \"10-K\" | \"10-Q\" (default: no filter)
   Example:
     (e/facts \"AAPL\")
     (e/facts \"AAPL\" :concept \"Assets\")
     (e/facts \"AAPL\" :concept [\"Assets\" \"NetIncomeLoss\"] :form \"10-K\")"
  [ticker-or-cik & {:keys [concept form]}]
  (schema/validate! schema/FactsArgs {:ticker-or-cik ticker-or-cik :concept concept :form form})
  (xbrl/get-facts-dataset (company/company-cik ticker-or-cik)
                          :concept concept
                          :form form))

(defn frame
  "Fetch cross-sectional data for a concept across all companies for a period.
   Returns a tech.ml.dataset sorted by :val descending.
   Required:
     concept - GAAP concept string e.g. \"Assets\" \"NetIncomeLoss\"
     period  - frame string e.g. \"CY2023Q4I\" \"CY2023\"
   Options:
     :taxonomy - default \"us-gaap\"
     :unit     - default \"USD\"
   Example:
     (e/frame \"Assets\" \"CY2023Q4I\")
     (e/frame \"SharesOutstanding\" \"CY2023Q4I\" :unit \"shares\")"
  [concept period & {:keys [taxonomy unit] :or {taxonomy "us-gaap" unit "USD"}}]
  (schema/validate! schema/FrameArgs {:concept concept :period period :taxonomy taxonomy :unit unit})
  (xbrl/get-concept-frame concept period :taxonomy taxonomy :unit unit))

;;; ---------------------------------------------------------------------------
;;; Financial statements
;;; ---------------------------------------------------------------------------

(defn concepts
  "Return a dataset of all XBRL concepts available for a company.
   Columns: :taxonomy :concept :label :description
   Each row is one distinct concept — use this to discover what data is available.
   Example: (e/concepts \"AAPL\")"
  [ticker-or-cik]
  (xbrl/get-concepts (company/company-cik ticker-or-cik)))

(defn income
  "Return income statement as a long-format tech.ml.dataset.
   Options:
     :form  - \"10-K\" (default) or \"10-Q\"
     :shape - :long (default) or :wide
     :as-of - ISO date string \"YYYY-MM-DD\" (default nil).
               When set, only filings where :filed <= as-of-date are used
               (point-in-time / look-ahead-safe mode)."
  [ticker-or-cik & {:keys [form shape as-of] :or {form "10-K" shape :long}}]
  (schema/validate! schema/StatementArgs {:ticker-or-cik ticker-or-cik :form form :shape shape :as-of as-of})
  (financials/income-statement ticker-or-cik :form form :shape shape :as-of as-of))

(defn balance
  "Return balance sheet as a long-format tech.ml.dataset.
   Options:
     :form  - \"10-K\" (default) or \"10-Q\"
     :shape - :long (default) or :wide
     :as-of - ISO date string \"YYYY-MM-DD\" (default nil).
               When set, only filings where :filed <= as-of-date are used
               (point-in-time / look-ahead-safe mode)."
  [ticker-or-cik & {:keys [form shape as-of] :or {form "10-K" shape :long}}]
  (schema/validate! schema/StatementArgs {:ticker-or-cik ticker-or-cik :form form :shape shape :as-of as-of})
  (financials/balance-sheet ticker-or-cik :form form :shape shape :as-of as-of))

(defn cashflow
  "Return cash flow statement as a long-format tech.ml.dataset.
   Options:
     :form  - \"10-K\" (default) or \"10-Q\"
     :shape - :long (default) or :wide
     :as-of - ISO date string \"YYYY-MM-DD\" (default nil).
               When set, only filings where :filed <= as-of-date are used
               (point-in-time / look-ahead-safe mode)."
  [ticker-or-cik & {:keys [form shape as-of] :or {form "10-K" shape :long}}]
  (schema/validate! schema/StatementArgs {:ticker-or-cik ticker-or-cik :form form :shape shape :as-of as-of})
  (financials/cash-flow ticker-or-cik :form form :shape shape :as-of as-of))

(defn financials
  "Return all three financial statements for a company.
   Returns {:income ds :balance ds :cashflow ds}
   Options:
     :form  - \"10-K\" (default) or \"10-Q\"
     :shape - :long (default) or :wide
     :as-of - ISO date string \"YYYY-MM-DD\" (default nil).
               All three statements use point-in-time deduplication."
  [ticker-or-cik & {:keys [form shape as-of] :or {form "10-K" shape :long}}]
  (schema/validate! schema/StatementArgs {:ticker-or-cik ticker-or-cik :form form :shape shape :as-of as-of})
  (let [stmts (financials/get-financials ticker-or-cik :form form :shape shape :as-of as-of)]
    {:income (:income-statement stmts)
     :balance (:balance-sheet stmts)
     :cashflow (:cash-flow stmts)}))

;;; ---------------------------------------------------------------------------
;;; Panel datasets
;;; ---------------------------------------------------------------------------

(defn panel
  "Fetch XBRL facts for multiple companies and combine into a long-format dataset.
   Adds a :ticker column.
   Options:
     :concept - string or collection of strings (default all concepts)
     :form    - \"10-K\" (default) or \"10-Q\"
     :as-of   - \"YYYY-MM-DD\" string; point-in-time filter — excludes filings submitted
                after this date and deduplicates per [ticker concept end] keeping the
                most recently filed observation available at that date
   Example:
     (e/panel [\"AAPL\" \"MSFT\" \"GOOG\"] :concept [\"Assets\" \"NetIncomeLoss\"])
     (e/panel [\"AAPL\" \"MSFT\"] :concept \"Assets\" :as-of \"2022-01-01\")"
  [tickers & {:keys [concept form as-of] :or {form "10-K"}}]
  (schema/validate! schema/PanelArgs {:tickers tickers :concept concept :form form :as-of as-of})
  (dataset/multi-company-facts tickers :concept concept :form form :as-of as-of))

(defn pivot
  "Pivot a long-format facts dataset to wide format.
   Rows = :end (period), columns = :concept, values = :val.
   Returns a tech.ml.dataset."
  [ds]
  (dataset/pivot-wide ds))
