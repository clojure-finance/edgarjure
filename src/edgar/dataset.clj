(ns edgar.dataset
  (:require [edgar.filings :as filings]
            [edgar.xbrl :as xbrl]
            [edgar.company :as company]
            [tech.v3.dataset :as ds]
            [tech.v3.dataset.rolling :as ds-roll]
            [clojure.string :as str]))

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
   Options:
     :concept   - string or collection of concept strings to keep (default all)
     :form      - \"10-K\" | \"10-Q\" (default \"10-K\")"
  [tickers & {:keys [concept form] :or {form "10-K"}}]
  (let [concept-set (when concept
                      (if (string? concept) #{concept} (set concept)))
        rows (for [ticker tickers
                   :let [cik (company/company-cik ticker)
                         ds (xbrl/get-facts-dataset cik
                                                    :form form
                                                    :concept concept-set
                                                    :sort nil)]]
               (ds/add-column ds :ticker (repeat (ds/row-count ds) ticker)))]
    (apply ds/concat rows)))

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
   Returns a dataset sorted by :val descending."
  [taxonomy concept unit frame]
  (-> (xbrl/get-concept-frame concept frame :taxonomy taxonomy :unit unit)
      (ds/sort-by-column :val {:comparator compare :reverse? true})))

;;; ---------------------------------------------------------------------------
;;; Helpers for financial research workflows
;;; These bridge edgar.dataset with typical empirical finance patterns
;;; and Datajure downstream use
;;; ---------------------------------------------------------------------------

(defn add-market-cap-rank
  "Add a :rank column (1 = largest) based on a market-cap or size column."
  [ds col]
  (let [sorted (ds/sort-by-column ds col {:comparator compare :reverse? true})
        ranks (range 1 (inc (ds/row-count sorted)))]
    (ds/add-column sorted :rank ranks)))

(defn filter-form
  "Filter a facts dataset to a specific form type."
  [ds form]
  (ds/filter-column ds :form #(= % form)))

(defn pivot-wide
  "Pivot a long-format facts dataset to wide format.
   Rows = :end (period), columns = :concept, values = :val.
   Deduplicates by taking the first observation per [end, concept].
   Returns a tech.ml.dataset."
  [ds]
  (let [deduped (ds/unique-by ds (fn [row] [(:end row) (:concept row)]))]
    (ds/->dataset
     (->> (ds/rows deduped)
          (group-by :end)
          (map (fn [[period rows]]
                 (into {:end period}
                       (map (fn [r] [(:concept r) (:val r)]) rows))))))))
