(ns edgar.dataset
  (:require [edgar.filings :as filings]
            [edgar.xbrl :as xbrl]
            [edgar.company :as company]
            [tech.v3.dataset :as ds])
  (:import [java.time LocalDate]
           [java.time.temporal ChronoUnit]))

;;; ---------------------------------------------------------------------------
;;; Filings index → dataset
;;; ---------------------------------------------------------------------------

(defn filings->dataset
  "Convert a seq of filing metadata maps to a tech.ml.dataset.
   Useful for filtering/sorting filing lists with dataset operations."
  [filings & {:keys [name] :or {name "filings"}}]
  (ds/->dataset (map #(select-keys % [:cik :form :filingDate :accessionNumber
                                      :primaryDocument :primaryDocDescription
                                      :reportDate :isInlineXBRL])
                     filings)
                {:dataset-name name}))

(defn get-filings-dataset
  "Fetch filings for a company and return as a dataset.
   Options same as edgar.filings/get-filings."
  [ticker-or-cik & opts]
  (filings->dataset (apply filings/get-filings ticker-or-cik opts)
                    :name (str ticker-or-cik " filings")))

;;; ---------------------------------------------------------------------------
;;; Multi-company facts panel
;;; Build a long-format panel dataset across multiple companies + concepts
;;; ---------------------------------------------------------------------------

(defn multi-company-facts
  "Fetch XBRL facts for multiple tickers/CIKs and combine into a single dataset.
   Returns a long-format dataset with an additional :ticker column.
   If a ticker fails (HTTP error, unknown CIK, etc.) it is skipped silently.
   If all tickers fail or tickers is empty, returns an empty dataset.
   Options:
     :concept   - string or collection of concept strings to keep (default all)
     :form      - \"10-K\" | \"10-Q\" (default \"10-K\")
     :as-of     - \"YYYY-MM-DD\" string; exclude filings submitted after this date
                  and keep only the most recently filed observation per [ticker concept unit start end]"
  [tickers & {:keys [concept form as-of] :or {form "10-K"}}]
  (let [concept-set (when concept
                      (if (string? concept) #{concept} (set concept)))
        rows (keep identity
                   (for [ticker tickers]
                     (try
                       (let [cik (company/company-cik ticker)
                             d (xbrl/get-facts-dataset cik
                                                       :form form
                                                       :concept concept-set
                                                       :sort nil)]
                         (ds/add-column d (ds/new-column :ticker (repeat (ds/row-count d) ticker))))
                       (catch Exception _
                         nil))))
        combined (if (seq rows)
                   (apply ds/concat rows)
                   (ds/->dataset []))]
    ;; the empty dataset has no :filed column, so the as-of filter would throw
    (if (or (nil? as-of) (zero? (ds/row-count combined)))
      combined
      (let [filtered (ds/filter-column combined :filed #(not (pos? (compare % as-of))))
            deduped (reduce (fn [acc row]
                              (let [k [(:ticker row) (:concept row) (:unit row) (:start row) (:end row)]]
                                (if (or (not (contains? acc k))
                                        (pos? (compare (:filed row) (:filed (get acc k)))))
                                  (assoc acc k row)
                                  acc)))
                            {}
                            (ds/rows filtered {:nil-missing? true}))]
        (ds/->dataset (vals deduped))))))

;;; ---------------------------------------------------------------------------
;;; Cross-sectional frame dataset
;;; Pull a single concept for all companies in one API call
;;; ---------------------------------------------------------------------------

(defn cross-sectional-dataset
  "Build a cross-sectional dataset for a concept across all companies.
   Uses the SEC frames endpoint (much faster than per-company fetches).
   taxonomy : \"us-gaap\" | \"dei\" | \"ifrs-full\"
   concept  : e.g. \"Assets\" \"Revenues\"
   unit     : e.g. \"USD\" \"shares\"
   frame    : e.g. \"CY2023Q4I\" \"CY2023\" \"CY2022Q3\"
   Returns a dataset sorted by :val descending (as edgar.xbrl/get-concept-frame
   already returns it)."
  [taxonomy concept unit frame]
  (xbrl/get-concept-frame concept frame :taxonomy taxonomy :unit unit))

;;; ---------------------------------------------------------------------------
;;; Helpers for financial research workflows
;;; These bridge edgar.dataset with typical empirical finance patterns
;;; and Datajure downstream use
;;; ---------------------------------------------------------------------------

(defn add-market-cap-rank
  "Sort descending by col and add a :rank column (1 = largest)."
  [ds col]
  (let [sorted (ds/sort-by-column ds col #(compare %2 %1))]
    (ds/add-or-update-column sorted :rank
                             (vec (range 1 (inc (ds/row-count sorted)))))))

(defn filter-form
  "Filter a facts dataset to a specific form type."
  [ds form]
  (ds/filter-column ds :form #(= % form)))

(defn- window-days
  "Length in days of a fact's :start..:end window; -1 for instant facts
   (and for unparseable dates), so any duration window outranks them."
  ^long [row]
  (let [parse (fn [d] (when d (try (LocalDate/parse (str d)) (catch Exception _ nil))))
        s (parse (:start row))
        e (parse (:end row))]
    (if (and s e) (.between ChronoUnit/DAYS s e) -1)))

(defn- preferred-fact?
  "True when fact a should replace fact b as the pivot cell value: the
   longer window wins; for the same window length the later :filed wins."
  [a b]
  (let [c (compare (window-days a) (window-days b))]
    (if (zero? c)
      (pos? (compare (str (:filed a)) (str (:filed b))))
      (pos? c))))

(defn pivot-wide
  "Pivot a long-format facts dataset to wide format: one row per period
   (:end) — per [:ticker :end] when the dataset has a :ticker column, as
   e/panel output does — and one column per :concept, valued from :val.

   One observation is kept per [ticker end concept unit]:
     - duration facts: the longest window ending at :end wins (a 10-K's
       fiscal-year figure over the quarterly-footnote row sharing its end
       date; a 10-Q's year-to-date figure over its 3-month figure)
     - among observations of the same window length, the most recently
       :filed wins (restatements)
   Units are never mixed in one column: a concept reported in a single unit
   keeps its plain name as the column; a concept reported in several units
   gets one column per unit, named \"<concept> (<unit>)\".
   Rows are sorted by :ticker (when present), then :end descending.
   Returns a tech.ml.dataset."
  [ds]
  (if (zero? (ds/row-count ds))
    (ds/->dataset [])
    (let [rows (ds/rows ds {:nil-missing? true})
          ticker? (contains? (set (ds/column-names ds)) :ticker)
          units (reduce (fn [m r] (update m (:concept r) (fnil conj #{}) (:unit r)))
                        {} rows)
          col-name (fn [r]
                     (if (> (count (get units (:concept r))) 1)
                       (str (:concept r) " (" (:unit r) ")")
                       (:concept r)))
          best (reduce (fn [acc r]
                         (let [k [(when ticker? (:ticker r)) (:end r) (col-name r)]
                               cur (get acc k)]
                           (if (or (nil? cur) (preferred-fact? r cur))
                             (assoc acc k r)
                             acc)))
                       {} rows)]
      (ds/->dataset
       (->> best
            (group-by (fn [[[ticker end _] _]] [ticker end]))
            (sort-by key (fn [[ta ea] [tb eb]]
                           (let [c (compare (str ta) (str tb))]
                             (if (zero? c) (compare (str eb) (str ea)) c))))
            (map (fn [[[ticker end] cells]]
                   (into (if ticker? {:ticker ticker :end end} {:end end})
                         (map (fn [[[_ _ col] r]] [col (:val r)]))
                         cells))))))))
