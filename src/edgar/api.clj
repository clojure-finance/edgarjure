(ns edgar.api
  "Unified power-user entry point for edgarjure.
   Require as: (require '[edgar.api :as e])

   Design principles:
   - Every function accepts ticker or CIK interchangeably
   - Keyword args throughout; no positional form/type args — a single options
     map also works everywhere: (e/income \"AAPL\" {:form \"10-Q\"})
   - nth-style lookups (:n on e/filing and e/tables) return nil out of range, never throw
   - Sensible defaults for taxonomy (us-gaap), unit (USD), form (10-K)
   - :concept accepts a string or a collection of strings
   - Functions that return datasets always return tech.ml.dataset, never seq-of-maps"
  (:require [edgar.core :as core]
            [edgar.company :as company]
            [edgar.filings :as filings]
            [edgar.filing :as filing]
            [edgar.forms]
            [edgar.extract :as extract]
            [edgar.xbrl :as xbrl]
            [edgar.financials :as financials]
            [edgar.reclass :as reclass]
            [edgar.dataset :as dataset]
            [edgar.tables :as tables-ns]
            [edgar.fsds :as fsds]
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
;;; Disk cache (opt-in)
;;; ---------------------------------------------------------------------------

(defn enable-disk-cache!
  "Enable the persistent on-disk HTTP cache (off by default). Responses are
   stored under :dir (default ~/.edgarjure/http-cache) and reused across
   sessions: JSON endpoints and the daily-changing full-index/daily-index
   files for 24 h, raw filing documents for 30 days (published filings are
   immutable).
   Options: :dir, :ttl-json-ms, :ttl-raw-ms.
   Example: (e/enable-disk-cache!)"
  [& {:as opts}]
  (core/enable-disk-cache! opts))

(defn disable-disk-cache!
  "Disable the on-disk HTTP cache (cached files are kept on disk)."
  []
  (core/disable-disk-cache!))

(defn clear-disk-cache!
  "Delete all entries from the on-disk HTTP cache. Returns the number of
   entries removed. Options: :dir (defaults to the enabled/default dir)."
  [& {:as opts}]
  (core/clear-disk-cache! opts))

(defn disk-cache-stats
  "Return {:dir :entries :bytes} for the on-disk cache, or nil when disabled."
  []
  (core/disk-cache-stats))

(defn enable-fsds!
  "Enable the local SEC Financial Statement Data Sets quarter cache (off by
   default). When enabled, the income statement's :view :compustat augments
   its reclassification with two things the companyfacts API cannot provide,
   per 10-K period: statement placement (does this filer present D&A as its
   own income statement line? then COGS is not stripped) and extension-tag
   operands (e.g. AMZN's Fulfillment / Technology & content lines, folded
   into XSGA/XRD).
   Quarter zips (50-130 MB each) download on first use into :dir (default
   ~/.edgarjure/fsds) and are shared across all companies. FSDS operands
   apply to 10-K periods only: 10-Q queries never trigger quarter downloads
   (the annual rows they build for Q4/LTM derivation are reclassified
   without FSDS, so a derived Q4 never mixes the two rule regimes).
   Options: :dir.
   Example: (e/enable-fsds!)"
  [& {:as opts}]
  (fsds/enable-cache! opts))

(defn disable-fsds!
  "Disable the FSDS quarter cache (cached files are kept on disk)."
  []
  (fsds/disable-cache!))

;;; ---------------------------------------------------------------------------
;;; Company
;;; ---------------------------------------------------------------------------

(defn cik
  "Resolve a ticker to a zero-padded 10-digit CIK string, or normalise a CIK.
   Accepts integer CIKs (320193) and share-class dots (\"BRK.B\").
   (e/cik \"AAPL\") => \"0000320193\""
  [ticker-or-cik]
  (schema/validate! schema/CompanyArgs {:ticker-or-cik ticker-or-cik})
  (company/company-cik ticker-or-cik))

(defn ticker
  "Reverse lookup: CIK (integer or string) → ticker, or nil when the CIK has
   no listed ticker. For several share classes the primary listing is
   returned. (e/ticker 1652044) => \"GOOGL\""
  [cik]
  (schema/validate! schema/CompanyArgs {:ticker-or-cik cik})
  (company/cik->ticker cik))

(defn company
  "Return full SEC submissions metadata map for a company.
   Accepts ticker or CIK."
  [ticker-or-cik]
  (schema/validate! schema/CompanyArgs {:ticker-or-cik ticker-or-cik})
  (company/get-company ticker-or-cik))

(defn company-name
  "Return the company name string for a ticker or CIK."
  [ticker-or-cik]
  (schema/validate! schema/CompanyArgs {:ticker-or-cik ticker-or-cik})
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
  (schema/validate! schema/CompanyArgs {:ticker-or-cik ticker-or-cik})
  (company/company-metadata ticker-or-cik))

(defn search
  "Search EDGAR entities (companies, funds, individual filers) by current
   name, best match first; ticker and CIK queries match too.
   Returns a vector of {:cik \"0000320193\" :name \"Apple Inc.\" :tickers [\"AAPL\"]}.
   Options:
     :limit - max results (default 10; the SEC endpoint returns at most 10)"
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
     :include-amends? - include amended filings e.g. 10-K/A (default false);
                        an amendment :form (\"10-K/A\") returns the
                        amendments themselves regardless"
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
     :include-amends? - include amended filings (default false); an
                        amendment :form (\"10-K/A\") returns the amendments
                        themselves regardless"
  [ticker-or-cik & {:keys [form n include-amends?] :or {form "10-K" n 0 include-amends? false}}]
  (schema/validate! schema/FilingArgs {:ticker-or-cik ticker-or-cik
                                       :form form
                                       :n n
                                       :include-amends? include-amends?})
  (nth (filings/get-filings ticker-or-cik :form form :include-amends? include-amends? :limit (inc n)) n nil))

(defn latest-effective-filing
  "Return the most recent effective filing for a company and form type.
   If an amendment (e.g. 10-K/A) is newer than the original, it is returned instead.
   Options:
     :form - form type string (default \"10-K\")"
  [ticker-or-cik & {:keys [form] :or {form "10-K"}}]
  (schema/validate! schema/LatestEffectiveFilingArgs {:ticker-or-cik ticker-or-cik :form form})
  (filings/latest-effective-filing ticker-or-cik :form form))

(defn filings-dataset
  "Return a filing index for a company as a tech.ml.dataset.
   Same options as e/filings."
  [ticker-or-cik & {:as opts}]
  (schema/validate! schema/FilingsArgs (assoc opts :ticker-or-cik ticker-or-cik))
  (apply dataset/get-filings-dataset ticker-or-cik (mapcat identity opts)))

(defn search-filings
  "Full-text search across EDGAR filings (2001 onward).
   Options:
     :forms      - vector of form types e.g. [\"10-K\" \"10-Q\"]; EFTS also
                   returns their amendments (\"8-K\" matches 8-K/A)
     :start-date - \"YYYY-MM-DD\" (alone: through today)
     :end-date   - \"YYYY-MM-DD\" (alone: from 2001-01-01)
     :limit      - max results (default 10)
   Returns a lazy seq of one map per FILING (EFTS indexes documents; a filing
   whose exhibits also match appears once, best-scoring document first).
   Each map is the raw EFTS source map (:adsh :ciks :display_names :root_forms
   :file_date :period_ending :items :sics ...) plus
     :accessionNumber - dashed accession number
     :cik             - zero-padded 10-digit CIK of the first filer
     :form            - form type
     :filingDate      - \"YYYY-MM-DD\"
     :companyName     - first display name
     :document        - file name of the matching document
     :url             - archive URL of the matching document
   so results work with e/html, e/text, e/filing-by-accession. EFTS caps
   result offsets at 10,000."
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

   Built from the EDGAR daily master index — complete, one map per filing.
   :form is an exact match (\"8-K\" excludes \"8-K/A\"). Returns an empty seq
   for weekends, holidays, future dates, and the current day until the SEC
   publishes its index (around 10 pm ET).

   Each result map has:
     :accessionNumber — dashed accession number
     :form            — form type string
     :filingDate      — \"YYYY-MM-DD\"
     :cik             — zero-padded 10-digit CIK of the first listed filer
                        (index order: for a Form 4 often the reporting
                        owner, not the issuer — filter on :filers instead)
     :companyName     — that filer's name
     :filers          — every filer as {:cik :name} (e.g. Form 4 issuer and owner)
   The maps work directly with e/html, e/text, e/items, e/obj; call
   e/filing-by-accession for :primaryDocument, :url and filer roles. For 8-K
   item filters use e/search-filings (its hits carry :items). Throws when
   the SEC refuses access (e.g. a missing or blocked User-Agent) rather
   than returning an empty day.

   Examples:
     ;; All filings on a date
     (e/daily-filings \"2026-03-10\")

     ;; Only 8-Ks
     (e/daily-filings \"2026-03-10\" :form \"8-K\")

     ;; Form 4s involving Apple, as issuer or as reporting owner
     (e/daily-filings \"2026-03-10\" :form \"4\"
                      :filter-fn (fn [f] (some #(= \"0000320193\" (:cik %)) (:filers f))))

     ;; Works with java.time.LocalDate too
     (e/daily-filings (java.time.LocalDate/of 2026 3 10))"
  [date & {:keys [form filter-fn]}]
  (schema/validate! schema/DailyFilingsArgs {:date date :form form :filter-fn filter-fn})
  (filings/get-daily-filings date :form form :filter-fn filter-fn))

(defn quarterly-index
  "Return every filing in an EDGAR quarterly full index (1993 onward) as a
   lazy seq of {:cik :company-name :form-type :date-filed :filename} maps,
   :cik zero-padded. Built from master.idx (30-60 MB, not held in the
   in-memory cache; the opt-in disk cache applies).
   Options:
     :form - keep only this exact form type (\"10-K\" excludes \"10-K/A\")
   Example: (e/quarterly-index 2025 4 :form \"10-K\")"
  [year quarter & {:keys [form]}]
  (schema/validate! schema/QuarterlyIndexArgs {:year year :quarter quarter :form form})
  (filings/get-quarterly-index year quarter :form form))

;;; ---------------------------------------------------------------------------
;;; Filing content
;;; ---------------------------------------------------------------------------

(defn filing-by-accession
  "Hydrate a filing map from an accession number string.
   Accepts dashed (\"XXXXXXXXXX-YY-ZZZZZZ\") or undashed format.
   Returns {:cik :accessionNumber :form :primaryDocument :filingDate :url :filers},
   ready for e/html, e/text, e/items, e/obj, etc. :cik is the company the
   filing is about (Filer, else Subject, Issuer, Reporting, Filed by) — not the
   filing agent that submitted it; :filers lists every entity on the filing
   index as {:name :role :cik}. CIKs are zero-padded 10-digit strings.
   Example: (e/filing-by-accession \"0000320193-23-000106\")"
  [accession-number]
  (schema/validate! schema/FilingByAccessionArgs {:accession-number accession-number})
  (filing/filing-by-accession accession-number))

(defn html
  "Fetch the primary document of a filing as a string: HTML for most forms,
   XML for the structured forms (3/4/5, 13F, 13D/G — use e/obj to parse
   them), plain text for pre-2001 filings."
  [filing-map]
  (schema/validate! schema/FilingMapArgs {:filing-map filing-map})
  (filing/filing-html filing-map))

(defn text
  "Fetch the primary document of a filing as plain text (markup stripped;
   script and style content dropped)."
  [filing-map]
  (schema/validate! schema/FilingMapArgs {:filing-map filing-map})
  (filing/filing-text filing-map))

(defn items
  "Extract item sections from a filing.
   Returns a map of item-id → {:title \"...\" :text \"...\" :method ...}.
   Options:
     :only           - set of item ids to extract e.g. #{\"7\" \"1A\"} (default all)
     :remove-tables? - drop every <table> before extraction (default false)
   10-Q ids carry their part: \"I-1\"..\"I-4\", \"II-1\"..\"II-6\" (\"I-2\" is MD&A);
   ids are normalized, so \"i 2\" also works."
  [filing-map & {:keys [only remove-tables?] :or {remove-tables? false}}]
  (schema/validate! schema/FilingMapArgs {:filing-map filing-map})
  (extract/extract-items filing-map
                         :items only
                         :remove-tables? remove-tables?))

(defn item
  "Extract a single item section from a filing.
   Returns {:title \"...\" :text \"...\" :method ...} or nil.
   (e/item f \"7\")  => {:title \"Management's Discussion...\" :text \"...\" :method :html-heading-boundaries}"
  [filing-map item-id]
  (schema/validate! schema/FilingMapArgs {:filing-map filing-map})
  (extract/extract-item filing-map item-id))

(defn obj
  "Parse a filing into a structured form-specific map via the filing-obj
   multimethod, dispatching on :form: Forms 3/4/5 (and amendments), 13F-HR,
   Schedule 13D/G. Other forms return {:form ... :raw-html ...}; extend with
   (defmethod edgar.filing/filing-obj \"FORM\" [f] ...)."
  [filing-map]
  (schema/validate! schema/FilingMapArgs {:filing-map filing-map})
  (filing/filing-obj filing-map))

(defn tables
  "Extract HTML tables from a filing as a seq of tech.ml.dataset objects.

   Options:
     :n        — return only the nth table (0-indexed); returns a single dataset or nil
     :min-rows — only include tables with at least this many data rows (default 2;
                 :min-rows 1 also keeps single-row tables)
     :min-cols — only include tables with at least this many columns (default 2)

   Layout and navigation tables (no data row with at least two non-blank cells)
   are filtered out.
   Column names come from the first substantive row; duplicates are suffixed _1, _2, etc.
   Colspan/rowspan grids are collapsed to one column per header cell (all-blank
   columns dropped); \"$\"/\"%\"/\")\" cells are merged into their number.
   Numeric columns: commas, currency and % stripped, parentheses → negative,
   \"—\"/\"–\"/\"-\" → nil.

   Examples:
     (def f (e/filing \"AAPL\" :form \"10-K\"))

     ;; All data tables
     (e/tables f)

     ;; Only substantial tables
     (e/tables f :min-rows 5)

     ;; Specific table by index
     (e/tables f :n 0)
     (e/tables f :n 2)"
  [filing-map & {:keys [n min-rows min-cols] :or {min-rows 2 min-cols 2}}]
  (schema/validate! schema/TablesArgs {:filing-map filing-map :n n
                                       :min-rows min-rows :min-cols min-cols})
  (tables-ns/extract-tables filing-map :n n :min-rows min-rows :min-cols min-cols))

(defn save!
  "Download a filing's primary document to dir/<form>/<cik>/<accession>/
   (\"/\" in the form becomes \"_\", e.g. 10-K_A). Returns the saved file
   path, or nil when the filing has no primary document."
  [filing-map dir]
  (schema/validate! schema/FilingMapArgs {:filing-map filing-map})
  (filing/filing-save! filing-map dir))

(defn save-all!
  "Download all documents in a filing (including the complete submission
   text file) to the e/save! directory. Returns a seq of saved file paths."
  [filing-map dir]
  (schema/validate! schema/FilingMapArgs {:filing-map filing-map})
  (filing/filing-save-all! filing-map dir))

(defn filing-index
  "Return a filing's index: {:files [{:sequence :name :type :description :size} ...]
   :formType :filingDate :filers [{:name :role :cik} ...]}. :files lists every
   document in the filing (primary document, exhibits, XBRL, graphics)."
  [filing-map]
  (schema/validate! schema/FilingMapArgs {:filing-map filing-map})
  (filing/filing-index filing-map))

(defn doc-url
  "Build the SEC archives URL for a specific document within a filing.
   doc-name is the filename as it appears in the filing index.

   Use (e/filing-index f) to see all available document names and types.

   Examples:
     ;; URL for a named exhibit
     (let [ex (e/exhibit f \"EX-21.1\")]
       (e/doc-url f (:name ex)))

     ;; URL for a known attachment
     (e/doc-url f \"R2.htm\")"
  [filing-map doc-name]
  (schema/validate! schema/FilingMapArgs {:filing-map filing-map})
  (filing/filing-doc-url filing-map doc-name))

(defn filing-document
  "Fetch a specific named document from a filing as a raw string.
   doc-name is the filename as it appears in the filing index.

   Use (e/exhibits f) or (e/filing-index f) to discover available document names.
   Use (e/doc-url f doc-name) if you only need the URL.

   Examples:
     ;; Fetch the subsidiaries exhibit content
     (def f  (e/filing \"AAPL\" :form \"10-K\"))
     (def ex (e/exhibit f \"EX-21.1\"))
     (e/filing-document f (:name ex))

     ;; Fetch a known attachment by name
     (e/filing-document f \"R2.htm\")"
  [filing-map doc-name]
  (schema/validate! schema/FilingMapArgs {:filing-map filing-map})
  (filing/filing-document filing-map doc-name))

(defn exhibits
  "Return all exhibit entries from a filing's index as a seq of maps.
   Each map has :name :type :description :sequence :size.
   Exhibits have :type values beginning with \"EX-\" (e.g. \"EX-21.1\", \"EX-31.1\").

   Example:
     (def f (e/filing \"AAPL\" :form \"10-K\"))
     (e/exhibits f)
     ;=> ({:type \"EX-4.1\" :name \"a10-kexhibit4109272025.htm\" :description \"EX-4.1\" ...}
     ;    {:type \"EX-21.1\" :name \"a10-kexhibit21109272025.htm\" ...} ...)

   Fetch an exhibit's content:
     (e/filing-document f (:name (e/exhibit f \"EX-21.1\")))"
  [filing-map]
  (schema/validate! schema/FilingMapArgs {:filing-map filing-map})
  (filing/filing-exhibits filing-map))

(defn exhibit
  "Return the first exhibit whose :type equals exhibit-type exactly, or nil.
   Most filings number their exhibits, so ask for \"EX-21.1\", not \"EX-21\"
   (list them with e/exhibits).

   Common exhibit types:
     \"EX-21\"   — subsidiaries list
     \"EX-23\"   — auditor consent
     \"EX-31.1\" — CEO Sarbanes-Oxley certification
     \"EX-31.2\" — CFO Sarbanes-Oxley certification
     \"EX-32\"   — Section 906 certifications

   To fetch the exhibit content, use (e/doc-url ...) to get the URL, or
   (e/filing-document ...) to fetch the raw content string directly:

     (def f  (e/filing \"AAPL\" :form \"10-K\"))
     (def ex (e/exhibit f \"EX-21.1\"))
     ;; Get the SEC URL:
     (e/doc-url f (:name ex))
     ;; Or fetch the content directly:
     (e/filing-document f (:name ex))"
  [filing-map exhibit-type]
  (schema/validate! schema/FilingMapArgs {:filing-map filing-map})
  (filing/filing-exhibit filing-map exhibit-type))

(defn xbrl-docs
  "Return all XBRL-related document entries from a filing's index as a seq of maps.
   Covers EX-101.* linkbases (schema, calculation, label, presentation,
   definition, and the instance in pre-inline filings) plus the instance SEC
   extracts from an inline-XBRL document (*_htm.xml, :type \"XML\").

   Example:
     (def f (e/filing \"AAPL\" :form \"10-K\"))
     (e/xbrl-docs f)
     ;=> ({:type \"EX-101.SCH\" :name \"aapl-20250927.xsd\" ...}
     ;    {:type \"EX-101.CAL\" :name \"aapl-20250927_cal.xml\" ...} ...
     ;    {:type \"XML\" :name \"aapl-20250927_htm.xml\" ...})"
  [filing-map]
  (schema/validate! schema/FilingMapArgs {:filing-map filing-map})
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
   Returns a tech.ml.dataset sorted by :val descending, with columns
   :accn :cik :entityName :loc :end :val (plus :start for duration frames
   such as \"CY2023\"). :cik is a zero-padded 10-digit
   string (matching e/cik), so results join directly against other
   edgarjure output.
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
  (schema/validate! schema/CompanyArgs {:ticker-or-cik ticker-or-cik})
  (xbrl/get-concepts (company/company-cik ticker-or-cik)))

(defn income
  "Return income statement as a long-format tech.ml.dataset.
   Options:
     :form     - \"10-K\" (default) or \"10-Q\"
     :shape    - :long (default) or :wide
     :view     - :normalized (default) | :as-reported | :standardized
                 | :compustat
                 :as-reported = raw rows, no dedup or label mapping
                 (:as-of still applies);
                 :standardized = normalized plus line items imputed from
                 arithmetic identities (rows carry :method :derived);
                 :compustat = standardized plus line items reclassified to
                 approximate Compustat definitions (\"COGS (Compustat)\",
                 \"XSGA (Compustat)\", \"OIADP (Compustat)\", ...; rows
                 carry :method :reclassified) - see (e/reclass-rules :income);
                 10-K values are the first-reported ones, not the latest
                 restatement
     :industry - :standard | :bank | :insurance | :reit. Auto-detected from the
                 company's SIC code when omitted (banks/insurers/REITs get
                 industry-specific concept chains). The :compustat rules only
                 apply on :standard chains; for other industries
                 :view :compustat equals :standardized with first-reported
                 10-K values
     :concepts - override the fallback chains with your own
                 [[label concept ...] ...] vectors (disables industry routing)
     :as-of    - ISO date string \"YYYY-MM-DD\" (default nil).
                 When set, only filings where :filed <= as-of-date are used
                 (point-in-time / look-ahead-safe mode).
   Long format carries :duration-months (not under :view :as-reported, whose
   rows are the raw companyfacts observations without the derived
   :line-item/:duration-months/:val-q/:val-ltm columns). 10-K queries keep the
   quarterly-footnote windows filed with the 10-K (3/6/9 months);
   :shape :wide keeps only the annual windows. 10-Q queries add :val-q
   (single quarter) and :val-ltm (trailing twelve months); share counts and
   per-share amounts are never differenced or summed (:val-q is the reported
   3-month value or nil, :val-ltm is nil)."
  [ticker-or-cik & {:keys [form shape as-of view industry concepts]
                    :or {form "10-K" shape :long}}]
  (schema/validate! schema/StatementArgs {:ticker-or-cik ticker-or-cik :form form :shape shape
                                          :as-of as-of :view view :industry industry
                                          :concepts concepts})
  (financials/income-statement ticker-or-cik :form form :shape shape :as-of as-of
                               :view view :industry industry
                               :concepts concepts))

(defn balance
  "Return balance sheet as a long-format tech.ml.dataset.
   Options:
     :form  - \"10-K\" (default) or \"10-Q\"
     :shape - :long (default) or :wide
     :view  - :normalized (default) | :as-reported | :standardized
              | :compustat
              (:standardized imputes e.g. Total Liabilities, Total Equity
              = SE + NCI, and a derived-only \"Working Capital\" item;
              :compustat adds reclassified items approximating Compustat
              constructions: \"RE (Compustat)\" incl. AOCI,
              \"DLC (Compustat)\"/\"DLTT (Compustat)\" incl. lease
              obligations, \"MIB (Compustat)\"/\"MIBN (Compustat)\"
              /\"MIBT (Compustat)\",
              \"PSTK (Compustat)\", \"SEQ (Compustat)\"/\"CEQ (Compustat)\"
              /\"TEQ (Compustat)\", \"RECT (Compustat)\", \"CHE (Compustat)\")
     :concepts - override balance-sheet-concepts with your own
               [[label concept ...] ...] vectors
     :as-of - ISO date string \"YYYY-MM-DD\" (default nil).
               When set, only filings where :filed <= as-of-date are used
               (point-in-time / look-ahead-safe mode)."
  [ticker-or-cik & {:keys [form shape as-of view concepts]
                    :or {form "10-K" shape :long}}]
  (schema/validate! schema/StatementArgs {:ticker-or-cik ticker-or-cik :form form :shape shape
                                          :as-of as-of :view view :concepts concepts})
  (financials/balance-sheet ticker-or-cik :form form :shape shape :as-of as-of
                            :view view :concepts concepts))

(defn cashflow
  "Return cash flow statement as a long-format tech.ml.dataset.
   Options:
     :form  - \"10-K\" (default) or \"10-Q\"
     :shape - :long (default) or :wide
     :view  - :normalized (default) | :as-reported | :standardized
              | :compustat
              (:standardized adds a derived \"Free Cash Flow\" line item and
              imputes \"D&A\" from separately tagged components; :compustat
              has no cash flow rules and differs from :standardized only in
              keeping first-reported 10-K values)
     :concepts - override cash-flow-concepts with your own
               [[label concept ...] ...] vectors
     :as-of - ISO date string \"YYYY-MM-DD\" (default nil).
               When set, only filings where :filed <= as-of-date are used
               (point-in-time / look-ahead-safe mode; also for :as-reported).
   Long format carries :duration-months (not under :view :as-reported);
   10-K :shape :wide keeps only the
   annual windows. 10-Q queries add :val-q and :val-ltm (share counts and
   per-share amounts are never differenced or summed)."
  [ticker-or-cik & {:keys [form shape as-of view concepts]
                    :or {form "10-K" shape :long}}]
  (schema/validate! schema/StatementArgs {:ticker-or-cik ticker-or-cik :form form :shape shape
                                          :as-of as-of :view view :concepts concepts})
  (financials/cash-flow ticker-or-cik :form form :shape shape :as-of as-of
                        :view view :concepts concepts))

(defn financials
  "Return all three financial statements for a company.
   Returns {:income ds :balance ds :cashflow ds}
   Options:
     :form     - \"10-K\" (default) or \"10-Q\"
     :shape    - :long (default) or :wide
     :view     - :normalized (default) | :as-reported | :standardized
                 | :compustat (income + balance sheet reclassification;
                 first-reported 10-K values on all three statements)
     :industry - :standard | :bank | :insurance | :reit (income statement only)
     :as-of    - ISO date string \"YYYY-MM-DD\" (default nil).
                 All three statements use point-in-time deduplication
                 (every :view, including :as-reported)."
  [ticker-or-cik & {:keys [form shape as-of view industry]
                    :or {form "10-K" shape :long}}]
  (schema/validate! schema/StatementArgs {:ticker-or-cik ticker-or-cik :form form :shape shape
                                          :as-of as-of :view view :industry industry})
  (let [stmts (financials/get-financials ticker-or-cik :form form :shape shape :as-of as-of
                                         :view view :industry industry)]
    {:income (:income-statement stmts)
     :balance (:balance-sheet stmts)
     :cashflow (:cash-flow stmts)}))

;;; ---------------------------------------------------------------------------
;;; Long-name aliases
;;;
;;; Every statement function also answers to its full name; e/search gets a
;;; companies-suffixed alias symmetric with e/search-filings. All functions
;;; taking keyword options also accept a single options map (Clojure 1.11+
;;; keyword-argument semantics): (e/income "AAPL" {:form "10-Q" :shape :wide})
;;; ---------------------------------------------------------------------------

(def ^{:doc "Long-name alias of e/income."
       :arglists '([ticker-or-cik & {:keys [form shape as-of view industry concepts]}])}
  income-statement income)

(def ^{:doc "Long-name alias of e/balance."
       :arglists '([ticker-or-cik & {:keys [form shape as-of view concepts]}])}
  balance-sheet balance)

(def ^{:doc "Long-name alias of e/cashflow."
       :arglists '([ticker-or-cik & {:keys [form shape as-of view concepts]}])}
  cash-flow cashflow)

(def ^{:doc "Alias of e/search — searches companies by name, symmetric with
  e/search-filings for full-text filing search."
       :arglists '([query & {:keys [limit]}])}
  search-companies search)

;;; ---------------------------------------------------------------------------
;;; Standardization introspection
;;; ---------------------------------------------------------------------------

(defn concepts-for
  "Return the active concept chains (and their EDN metadata) for a statement.
   statement: :income | :balance | :cash-flow
   Options:
     :industry - :standard (default) | :bank | :insurance | :reit (income only)
   Example: (e/concepts-for :income :industry :bank)"
  [statement & {:keys [industry] :or {industry :standard}}]
  (financials/concepts-for statement :industry industry))

(defn reclass-rules
  "Return the reclassification ruleset used by :view :compustat for a
   statement, as its full EDN map ({:ruleset :compustat :rules [...] ...}),
   or nil when the statement has no rules (:income and :balance have rules;
   :cash-flow has none).
   Example: (e/reclass-rules :balance)"
  [statement]
  (reclass/ruleset-for statement))

(defn unmapped-concepts
  "Return the registry of us-gaap concepts seen in company facts that no
   active concept chain matched, accumulated across statement calls this
   session: {concept {:count n :first-seen date :example-ciks #{...}}}.
   :count is the number of distinct companies; concepts of every built-in
   chain (all statements and industries) and of custom :concepts count as
   mapped.
   Options:
     :top - only the n most frequent, as [concept info] pairs
   Grow chain coverage by reviewing this and extending the EDN concept files."
  [& {:keys [top]}]
  (financials/unmapped-concepts :top top))

(defn clear-unmapped-concepts!
  "Reset the unmapped-concept registry."
  []
  (financials/clear-unmapped-concepts!))

(defn save-unmapped-concepts!
  "Write the unmapped-concept registry as EDN.
   Options:
     :path - output file (default ~/.edgarjure/unmapped-concepts.edn)
   Returns the path written."
  [& {:keys [path]}]
  (financials/save-unmapped-concepts! :path path))

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
                after this date and deduplicates per [ticker concept unit start end]
                keeping the most recently filed observation available at that date
   Example:
     (e/panel [\"AAPL\" \"MSFT\" \"GOOG\"] :concept [\"Assets\" \"NetIncomeLoss\"])
     (e/panel [\"AAPL\" \"MSFT\"] :concept \"Assets\" :as-of \"2022-01-01\")"
  [tickers & {:keys [concept form as-of] :or {form "10-K"}}]
  (schema/validate! schema/PanelArgs {:tickers tickers :concept concept :form form :as-of as-of})
  (dataset/multi-company-facts tickers :concept concept :form form :as-of as-of))

(defn pivot
  "Pivot a long-format facts dataset to wide format.
   Rows = :end (per [:ticker :end] when the dataset has a :ticker column,
   e.g. e/panel output); columns = :concept; values = :val. One observation
   per [ticker end concept unit]: the longest duration window wins (fiscal
   year over quarterly-footnote rows), latest :filed among equal windows.
   Concepts reported in several units get one column per unit,
   \"<concept> (<unit>)\". Facts from 10-K filings whose window is 3, 6 or 9
   months (quarterly footnotes) are dropped first. Columns: :ticker (when
   present) and :end, then the concepts alphabetically. Returns a
   tech.ml.dataset."
  [ds]
  (dataset/pivot-wide ds))
