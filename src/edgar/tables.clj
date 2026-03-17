(ns edgar.tables
  "HTML table extraction from SEC filings.

   Extracts <table> elements from filing HTML and converts them to
   tech.ml.dataset objects. Handles iXBRL inline-tagged content,
   colspan expansion, and mixed numeric/string columns.

   Usage:
     (require '[edgar.tables :as tables])
     (tables/extract-tables filing)        ; => seq of datasets
     (tables/extract-tables filing :nth 0) ; => first table as dataset
     (tables/extract-tables filing :min-rows 5) ; => only tables with >=5 data rows"
  (:require [edgar.filing :as filing]
            [hickory.core :as hickory]
            [hickory.select :as sel]
            [tech.v3.dataset :as ds]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; Text extraction from hickory nodes
;;; ---------------------------------------------------------------------------

(defn- node-text
  "Recursively extract all text content from a hickory node.
   Normalizes non-breaking spaces (\\u00A0) to regular spaces."
  [node]
  (cond
    (string? node) (str/replace node "\u00A0" " ")
    (map? node) (apply str (map node-text (:content node)))
    :else ""))

(defn- clean-text
  "Trim and normalise internal whitespace in a string."
  [s]
  (-> s str/trim (str/replace #"\s+" " ")))

(defn- cell-text
  "Extract cleaned text from a td or th hickory node."
  [cell]
  (clean-text (node-text cell)))

;;; ---------------------------------------------------------------------------
;;; Row extraction
;;; Each row is represented as a vector of cell text strings (blanks preserved).
;;; th and td are both collected; th is prioritised for header detection.
;;; ---------------------------------------------------------------------------

(defn- row-cells
  "Return a vector of [text is-header?] pairs for all cells in a tr node,
   in DOM order. th cells are marked as headers.
   Colspan attributes are expanded: a cell with colspan=3 produces 3 entries."
  [tr-node]
  (->> (filter map? (:content tr-node))
       (filter #(#{:th :td} (:tag %)))
       (mapcat (fn [cell]
                 (let [span (or (when-let [cs (get-in cell [:attrs :colspan])]
                                  (try (Integer/parseInt cs) (catch Exception _ nil)))
                                1)
                       text (cell-text cell)
                       hdr? (= :th (:tag cell))]
                   (repeat span [text hdr?]))))
       vec))

(defn- row-texts
  "Return a vector of cell text strings from a tr node, preserving blank cells.
   Column positions are maintained so downstream alignment is correct."
  [tr-node]
  (mapv (comp cell-text first) (row-cells tr-node)))

;;; ---------------------------------------------------------------------------
;;; Numeric type inference
;;; ---------------------------------------------------------------------------

(defn- parse-number
  "Try to parse a cell string as a number.
   Handles: commas, parentheses for negatives, leading $, trailing %, spaces."
  [s]
  (when-not (str/blank? s)
    (let [clean (-> s
                    str/trim
                    (str/replace #"[$,%\s]" "")
                    (str/replace #"^\((.+)\)$" "-$1"))]
      (when-not (str/blank? clean)
        (try
          (if (str/includes? clean ".")
            (Double/parseDouble clean)
            (Long/parseLong clean))
          (catch NumberFormatException _ nil))))))

(defn- infer-column
  "Given a seq of cell strings for one column, return the best typed vector.
   Infers type from non-blank cells only; blank cells become nil.
   Tries Long, then Double, then falls back to String."
  [cells]
  (let [non-blank (remove str/blank? cells)
        parsed-non-blank (map parse-number non-blank)
        all-numeric? (and (seq non-blank) (every? some? parsed-non-blank))
        any-double? (some double? parsed-non-blank)]
    (if all-numeric?
      (mapv #(when-not (str/blank? %) (parse-number %)) cells)
      (vec cells))))

;;; ---------------------------------------------------------------------------
;;; Table filtering — skip layout/navigation tables
;;; ---------------------------------------------------------------------------

(defn- layout-table?
  "Return true if a table appears to be layout/navigation rather than data.
   Heuristics: fewer than 2 data rows, or all rows have only 1 non-blank cell."
  [data-rows]
  (or (< (count data-rows) 2)
      (every? #(< (count (remove str/blank? %)) 2) data-rows)))

;;; ---------------------------------------------------------------------------
;;; Table → dataset conversion
;;; ---------------------------------------------------------------------------

(defn- matrix->dataset
  "Convert a seq of row-vectors (all strings) into a tech.ml.dataset.
   First row is used as column names. Remaining rows are data.
   Pads short rows with nil; truncates overlong rows."
  [rows table-idx]
  (when (>= (count rows) 2)
    (let [header (first rows)
          data-rows (rest rows)
          ncols (count header)
          ;; Pad/truncate each data row to ncols
          aligned (mapv (fn [row]
                          (let [padded (into (vec row) (repeat (- ncols (count row)) ""))]
                            (subvec padded 0 (min ncols (count padded)))))
                        data-rows)
          ;; Build column seqs
          col-seqs (for [ci (range ncols)]
                     (mapv #(nth % ci "") aligned))
          ;; Deduplicate column names
          col-names (loop [names header seen {} result []]
                      (if (empty? names)
                        result
                        (let [n (first names)
                              n (if (str/blank? n) (str "col_" (count result)) n)
                              cnt (get seen n 0)
                              uniq-n (if (zero? cnt) n (str n "_" cnt))]
                          (recur (rest names)
                                 (assoc seen n (inc cnt))
                                 (conj result uniq-n)))))
          ;; Infer types
          typed-cols (mapv infer-column col-seqs)
          col-map (zipmap col-names typed-cols)]
      (ds/->dataset col-map {:dataset-name (str "table-" table-idx)}))))

;;; ---------------------------------------------------------------------------
;;; Main extraction pipeline
;;; ---------------------------------------------------------------------------

(defn- html-content?
  "Return true if the string looks like an HTML document."
  [s]
  (boolean (re-find #"(?i)<(!DOCTYPE|html|table)" s)))

(defn- parse-html
  "Parse an HTML string into a hickory tree."
  [html-str]
  (hickory/as-hickory (hickory/parse html-str)))

(defn- tr-node? [n] (and (map? n) (= :tr (:tag n))))

(defn- direct-rows
  "Return only the <tr> nodes that are direct children of a <table> node,
   or direct children of its <thead>/<tbody>/<tfoot> sections.
   Does not recurse into nested tables, preventing double-counting."
  [table-node]
  (let [direct-children (filter map? (:content table-node))
        section-tags #{:thead :tbody :tfoot}
        sections (filter #(section-tags (:tag %)) direct-children)
        tr-from-sections (mapcat (fn [sec] (filter tr-node? (filter map? (:content sec))))
                                 sections)
        tr-direct (filter tr-node? direct-children)]
    (vec (concat tr-direct tr-from-sections))))

(defn- extract-table
  "Convert a single hickory table node into a dataset, or nil if it's a layout table."
  [table-node table-idx]
  (let [rows (->> (direct-rows table-node)
                  (mapv row-texts)
                  ;; Filter out rows where every cell is blank
                  (filterv #(some (complement str/blank?) %)))
        ;; Find the first row with >=2 non-blank cells as the header
        header-idx (or (first (keep-indexed
                               #(when (>= (count (remove str/blank? %2)) 2) %1)
                               rows))
                       0)
        header-row (nth rows header-idx nil)
        data-rows (drop (inc header-idx) rows)]
    (when (and header-row
               (not (layout-table? data-rows)))
      (matrix->dataset (into [header-row] data-rows) table-idx))))

(defn extract-tables
  "Extract HTML tables from a filing as a seq of tech.ml.dataset objects.

   filing    — a filing map (from e/filing, filings/get-filing, etc.)
   Options:
     :nth      — return only the nth table (0-indexed); returns a single dataset or nil
     :min-rows — only return tables with at least this many data rows (default 2)
     :min-cols — only return tables with at least this many columns (default 2)

   Returns a seq of datasets (or a single dataset when :nth is used).
   Each dataset is named \"table-N\" where N is the original index in the HTML.

   Tables that appear to be layout/navigation (single-column, <2 data rows)
   are automatically filtered out.

   Returns nil (for :nth) or an empty seq when the filing has no HTML content
   or the HTML contains no tables.

   Example:
     (require '[edgar.tables :as tables]
              '[edgar.api :as e])
     (def f (e/filing \"AAPL\" :form \"10-K\"))

     ;; All data tables
     (tables/extract-tables f)

     ;; Only tables with at least 5 data rows
     (tables/extract-tables f :min-rows 5)

     ;; First table
     (tables/extract-tables f :nth 0)

     ;; Third table
     (tables/extract-tables f :nth 2)"
  [filing & {:keys [nth min-rows min-cols] :or {min-rows 2 min-cols 2}}]
  (let [html-str (filing/filing-html filing)]
    (if-not (and html-str (not (str/blank? html-str)) (html-content? html-str))
      (if nth nil [])
      (let [hick (parse-html html-str)
            table-nodes (sel/select (sel/tag :table) hick)
            all-datasets (->> table-nodes
                              (map-indexed (fn [idx node] (extract-table node idx)))
                              (keep identity)
                              (filter #(>= (ds/row-count %) min-rows))
                              (filter #(>= (ds/column-count %) min-cols)))]
        (if nth
          (clojure.core/nth (vec all-datasets) nth nil)
          all-datasets)))))
