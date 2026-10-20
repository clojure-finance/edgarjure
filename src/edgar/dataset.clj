(ns edgar.dataset
  (:require [clojure.string :as str]
            [edgar.filings :as filings]
            [edgar.xbrl :as xbrl]
            [edgar.company :as company]
            [edgar.util :as util]
            [tech.v3.dataset :as ds]))

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
  (let [rows (keep identity
                   (for [ticker tickers]
                     (try
                       (let [cik (company/company-cik ticker)
                             d (xbrl/get-facts-dataset cik
                                                       :form form
                                                       :concept concept
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
      (->> (ds/rows combined {:nil-missing? true})
           (filter #(util/filed-on-or-before? as-of %))
           (util/latest-filed (juxt :ticker :concept :unit :start :end))
           vec
           ds/->dataset))))

(defn- annual-form? [form]
  (str/starts-with? (str form) "10-K"))

(defn pivot-wide
  "Pivot a long-format facts dataset to wide format: one row per period
   (:end) — per [:ticker :end] when the dataset has a :ticker column, as
   e/panel output does — and one column per :concept, valued from :val.

   Facts from 10-K filings (:form \"10-K\", \"10-K/A\", ...) whose window is
   3, 6 or 9 months are dropped first: a 10-K carries quarterly-footnote
   windows next to its fiscal-year figures, and those must neither replace
   an annual value nor surface as extra quarter-end rows.

   One observation is kept per [ticker end concept unit]:
     - duration facts: the longest window ending at :end wins (a 10-K's
       fiscal-year figure over an irregular window sharing its end date; a
       10-Q's year-to-date figure over its 3-month figure)
     - among observations of the same window length, the most recently
       :filed wins (restatements)
   Units are never mixed in one column: a concept reported in a single unit
   keeps its plain name as the column; a concept reported in several units
   gets one column per unit, named \"<concept> (<unit>)\".
   Columns: :ticker (when present) and :end first, then the concept columns
   alphabetically. Rows are sorted by :ticker (when present), then :end
   descending. Returns a tech.ml.dataset."
  [ds]
  (if (zero? (ds/row-count ds))
    (ds/->dataset [])
    (let [rows (remove #(and (annual-form? (:form %)) (util/quarterly-window? %))
                       (ds/rows ds {:nil-missing? true}))
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
                           (if (or (nil? cur) (util/preferred-observation? r cur))
                             (assoc acc k r)
                             acc)))
                       {} rows)]
      (util/ordered-dataset
       (->> best
            (group-by (fn [[[ticker end _] _]] [ticker end]))
            (sort-by key (fn [[ta ea] [tb eb]]
                           (let [c (compare (str ta) (str tb))]
                             (if (zero? c) (compare (str eb) (str ea)) c))))
            (map (fn [[[ticker end] cells]]
                   (into (if ticker? {:ticker ticker :end end} {:end end})
                         (map (fn [[[_ _ col] r]] [col (:val r)]))
                         cells))))
       [:ticker :end]))))
