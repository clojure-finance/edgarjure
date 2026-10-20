(ns edgar.tables
  "HTML table extraction from SEC filings.

   Extracts <table> elements from filing HTML and converts them to
   tech.ml.dataset objects. Handles iXBRL inline-tagged content, colspan
   layouts (Workiva-style grids where one value sits in a 2- or 3-column
   span), currency/percent fragments split into their own cells, and mixed
   numeric/string columns.

   Usage:
     (require '[edgar.tables :as tables])
     (tables/extract-tables filing)        ; => seq of datasets
     (tables/extract-tables filing :n 0) ; => first table as dataset
     (tables/extract-tables filing :min-rows 5) ; => only tables with >=5 data rows"
  (:require [edgar.filing :as filing]
            [hickory.core :as hickory]
            [hickory.select :as sel]
            [tech.v3.dataset :as ds]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; HTML detection (shared with edgar.extract)
;;; ---------------------------------------------------------------------------

(defn html-content?
  "True when the string looks like an HTML document: it has a doctype,
   <html> or <body> tag. A bare <table> is not enough — pre-2001 SGML
   plain-text filings mark their tables with <TABLE> too."
  [s]
  (boolean (and s (re-find #"(?i)<(?:!DOCTYPE\s+html|html|body)\b" s))))

;;; ---------------------------------------------------------------------------
;;; Text extraction from hickory nodes
;;; ---------------------------------------------------------------------------

(def ^:private block-tags
  "Elements that break a line, so their text is separated by a space from
   the text around them (\"Amount<br>(in millions)\" -> \"Amount (in millions)\")."
  #{:br :div :p :li :tr})

(defn- node-text
  "Recursively extract all text content from a hickory node.
   Normalizes non-breaking spaces (\\u00A0) to regular spaces and the
   C1 controls \\u0096/\\u0097 (cp1252 dashes) to en/em dashes; block
   elements and <br> are separated from adjacent text by a space."
  [node]
  (cond
    (string? node) (-> node
                       (str/replace "\u00A0" " ")
                       ;; cp1252 en/em dash mis-decoded as C1 controls
                       (str/replace "\u0096" "\u2013")
                       (str/replace "\u0097" "\u2014"))
    (map? node) (let [s (apply str (map node-text (:content node)))]
                  (if (block-tags (:tag node)) (str " " s " ") s))
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
;;; Rows are laid out on the table's grid: th and td cells are treated
;;; alike, in DOM order; a cell with colspan=n occupies n grid positions and
;;; one with rowspan=m also covers the same positions in the next m-1 rows.
;;; A cell's text sits at its first position only — the other positions it
;;; covers are blank — so a value never appears in more than one column.
;;; ---------------------------------------------------------------------------

(defn- cell-nodes
  "The direct th/td children of a tr node, in DOM order."
  [tr-node]
  (filter #(and (map? %) (#{:th :td} (:tag %))) (:content tr-node)))

(defn- span-attr
  "A cell's colspan/rowspan attribute as a positive int (1 when absent or
   invalid; capped at the HTML spec's limit)."
  [cell attr limit]
  (or (some-> (get-in cell [:attrs attr]) str/trim parse-long (max 1) (min limit))
      1))

(defn- layout-row
  "Lay one tr out on the grid. occupied maps grid column -> number of rows
   (this one included) a rowspan from above still covers. Returns
   {:cells <text by grid position> :spans <[start end) of each own cell>
    :occupied <occupied map for the next row>}."
  [tr occupied]
  (loop [cells (cell-nodes tr) col 0 texts {} spans [] added {}]
    (cond
      (pos? (get occupied col 0))
      (recur cells (inc col) texts spans added)

      (empty? cells)
      {:cells (reduce-kv assoc (vec (repeat col "")) texts)
       :spans spans
       :occupied (into added
                       (keep (fn [[p r]] (when (> r 1) [p (dec r)])))
                       occupied)}

      :else
      (let [cell (first cells)
            end (+ col (span-attr cell :colspan 1000))
            rows (span-attr cell :rowspan 65534)]
        (recur (rest cells)
               end
               (assoc texts col (cell-text cell))
               (conj spans [col end])
               (cond-> added
                 (> rows 1) (into (map (fn [p] [p (dec rows)])) (range col end))))))))

(defn- layout-rows
  "Lay the tr nodes of one table out on a common grid (see layout-row).
   Returns one {:cells :spans} map per tr, in order."
  [trs]
  (loop [trs trs occupied {} out []]
    (if-let [tr (first trs)]
      (let [{:keys [occupied] :as row} (layout-row tr occupied)]
        (recur (rest trs) occupied (conj out (dissoc row :occupied))))
      out)))

;;; ---------------------------------------------------------------------------
;;; Fragment merging — filings often put "$", "%" or the closing ")" of a
;;; negative number in a cell of its own: ["$" "178,353"], ["7" "%"],
;;; ["(4" ")%"]. They are glued onto the adjacent number.
;;; ---------------------------------------------------------------------------

(defn- prefix-fragment?
  "A currency symbol and/or opening parenthesis standing alone: \"$\", \"(\",
   \"$(\", \"US$\"."
  [s]
  (boolean (re-matches #"(?:US\$|[$€£¥])?\(?" s)))

(defn- suffix-fragment?
  "A closing parenthesis and/or percent sign standing alone: \")\", \"%\",
   \")%\", \"%)\"."
  [s]
  (boolean (re-matches #"\)?%?\)?" s)))

(defn- dash-placeholder?
  "SEC tables print \"—\" (or \"–\", \"-\", other dashes), possibly with
   \"$\" or \"%\", for zero / not applicable."
  [s]
  (boolean (re-matches #"(?:US\$|[$€£¥])?\s*[\u2012-\u2015\u2212-]+\s*%?" s)))

(defn- number-start?
  "True when s begins like a number (or is a dash placeholder)."
  [s]
  (boolean (or (re-find #"^\(?[-−]?\.?\d" s) (dash-placeholder? s))))

(defn- number-like?
  "True when s is a (possibly partial, e.g. \"(4\") number or a dash
   placeholder, so a following \")\" or \"%\" belongs to it."
  [s]
  (boolean (or (re-matches #"(?:US\$|[$€£¥])?\(?[-−]?\.?\d[\d.,]*\)?" s)
               (dash-placeholder? s))))

(defn- merge-fragments
  "Glue currency/parenthesis prefixes onto the number that follows them and
   \")\"/\"%\" suffixes onto the number before them. Merged text sits at the
   number's grid position; the fragment's own position becomes blank."
  [cells]
  (let [items (keep-indexed (fn [i s] (when-not (str/blank? s) [i s])) cells)
        merged (reduce (fn [acc [i s]]
                         (let [[pi ps] (peek acc)]
                           (cond
                             (and ps (prefix-fragment? ps)
                                  (or (number-start? s) (prefix-fragment? (str ps s))))
                             (conj (pop acc) [i (str ps s)])

                             (and ps (suffix-fragment? s) (number-like? ps))
                             (conj (pop acc) [pi (str ps s)])

                             :else (conj acc [i s]))))
                       []
                       items)]
    (reduce (fn [v [i s]] (assoc v i s)) (vec (repeat (count cells) "")) merged)))

;;; ---------------------------------------------------------------------------
;;; Numeric type inference
;;; ---------------------------------------------------------------------------

(defn- parse-number
  "Try to parse a cell string as a number.
   Handles: commas, parentheses for negatives, leading $ (and other currency
   symbols), trailing %, spaces, the Unicode minus sign."
  [s]
  (when-not (str/blank? s)
    (let [clean (-> s
                    str/trim
                    (str/replace #"^US\$" "")
                    (str/replace #"[$€£¥,%\s]" "")
                    (str/replace "−" "-")
                    (str/replace #"^\((.+)\)$" "-$1"))]
      (when (re-matches #"-?(?:\d+\.?\d*|\.\d+)" clean)
        (if (str/includes? clean ".")
          (Double/parseDouble clean)
          (try (Long/parseLong clean)
               (catch NumberFormatException _ (Double/parseDouble clean))))))))

(defn- infer-column
  "Given a seq of cell strings for one column, return the best typed vector.
   A column is numeric when every cell that is neither blank nor a dash
   placeholder (\"—\", \"–\", \"-\") parses as a number; blanks and dashes
   then become nil. Otherwise the strings are returned unchanged."
  [cells]
  (let [missing? #(or (str/blank? %) (dash-placeholder? %))
        values (remove missing? cells)]
    (if (and (seq values) (every? parse-number values))
      (mapv #(when-not (missing? %) (parse-number %)) cells)
      (vec cells))))

;;; ---------------------------------------------------------------------------
;;; Table filtering — skip layout/navigation tables
;;; ---------------------------------------------------------------------------

(defn- layout-table?
  "Return true if a table appears to be layout/navigation rather than data.
   Heuristics: no data rows, or every data row has fewer than 2 non-blank
   cells. The minimum number of data rows is governed by :min-rows in
   extract-tables, not here."
  [data-rows]
  (or (empty? data-rows)
      (every? #(< (count (remove str/blank? %)) 2) data-rows)))

;;; ---------------------------------------------------------------------------
;;; Grid → logical columns
;;; ---------------------------------------------------------------------------

(defn- non-blank-count [row] (count (remove str/blank? row)))

(defn- split-range
  "Split the grid range [s e) at the positions of row's non-blank cells
   inside it (the first piece always starts at s)."
  [[s e] row]
  (let [starts (filterv #(and (> % s) (not (str/blank? (row %)))) (range s e))
        bounds (conj (into [s] starts) e)]
    (mapv vector bounds (rest bounds))))

(defn- column-groups
  "Logical columns as {:range [start end) :header-range [start end)} grid
   ranges: one per header-row cell (a header cell spanning three grid
   columns owns all three, which is where Workiva puts \"$\", the number and
   a spacer), plus one per grid position past the header's width. A header
   cell over several sub-columns (the first data row that holds two or more
   values under it, typically a sub-header row) is split at that row's
   cells; every piece keeps the header cell as its :header-range and records
   the index of the splitting row as :split-by. rows are padded to width."
  [header-spans data-rows width]
  (let [hdr-end (if (seq header-spans) (second (peek header-spans)) 0)
        ranges (into header-spans (map (fn [p] [p (inc p)])) (range hdr-end width))]
    (into []
          (mapcat (fn [[s e :as r]]
                    (if-let [i (first (keep-indexed #(when (< 1 (non-blank-count (subvec %2 s e))) %1)
                                                    data-rows))]
                      (map (fn [p] {:range p :header-range r :split-by i})
                           (split-range r (nth data-rows i)))
                      [{:range r :header-range r}])))
          ranges)))

(defn- group-row
  "Collapse a padded grid row into logical columns, joining the non-blank
   texts in each group's range-key range with a space."
  [groups range-key row]
  (mapv (fn [g]
          (let [[s e] (range-key g)]
            (str/join " " (remove str/blank? (subvec row s e)))))
        groups))

(defn- fold-sub-header
  "When the first data row is a second header row — it split a header cell
   into sub-columns (see column-groups) and none of its cells is numeric
   (\"Amount\" / \"Rate\" under a year) — append its texts to the header
   names and drop it. rows are grouped rows, header first."
  [groups [header sub & more :as rows]]
  (if (and sub
           (some #(= 0 (:split-by %)) groups)
           (not-any? parse-number sub))
    (into [(mapv (fn [h s] (str/trim (str h " " s))) header sub)] more)
    (vec rows)))

(defn- drop-blank-columns
  "Remove the columns that are blank in every data row (the header row is
   first and does not count)."
  [[_ & data :as rows]]
  (let [keep-idx (filterv (fn [ci] (some #(not (str/blank? (nth % ci))) data))
                          (range (count (first rows))))]
    (mapv (fn [row] (mapv row keep-idx)) rows)))

;;; ---------------------------------------------------------------------------
;;; Table → dataset conversion
;;; ---------------------------------------------------------------------------

(defn- matrix->dataset
  "Convert a seq of row-vectors (all strings) into a tech.ml.dataset.
   First row is used as column names. Remaining rows are data.
   Pads short rows with blanks; truncates overlong rows.
   Columns keep their left-to-right order from the HTML table."
  [rows table-idx]
  (when (>= (count rows) 2)
    (let [header (first rows)
          data-rows (rest rows)
          ncols (count header)
          ;; Pad/truncate each data row to ncols
          aligned (mapv (fn [row]
                          (let [padded (into (vec row) (repeat (- ncols (count row)) ""))]
                            (subvec padded 0 ncols)))
                        data-rows)
          ;; Build column seqs
          col-seqs (for [ci (range ncols)]
                     (mapv #(nth % ci) aligned))
          ;; Deduplicate column names
          col-names (loop [names header used #{} result []]
                      (if (empty? names)
                        result
                        (let [n (first names)
                              n (if (str/blank? n) (str "col_" (count result)) n)
                              ;; first of n, n_1, n_2, ... not already taken
                              uniq-n (first (remove used (cons n (map #(str n "_" %) (iterate inc 1)))))]
                          (recur (rest names)
                                 (conj used uniq-n)
                                 (conj result uniq-n)))))
          ;; Infer types; build columns in order (a column map would be a
          ;; hash map beyond 8 entries and scramble the column order)
          columns (map ds/new-column col-names (map infer-column col-seqs))]
      (ds/new-dataset {:dataset-name (str "table-" table-idx)} columns))))

;;; ---------------------------------------------------------------------------
;;; Main extraction pipeline
;;; ---------------------------------------------------------------------------

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
  "Convert a single hickory table node into a dataset, or nil if it's a
   layout table or has no data rows.

   The header is the first row with at least two non-blank cells; rows
   above it are dropped. Data cells are assigned to the column of the header
   cell whose span covers them, fragments (\"$\", \"%\", \")\") are merged
   into their numbers, a sub-header row directly below the header is folded
   into the column names, and columns blank in every data row are removed."
  [table-node table-idx]
  (let [rows (->> (layout-rows (direct-rows table-node))
                  (map #(update % :cells merge-fragments))
                  ;; Filter out rows where every cell is blank
                  (filterv #(pos? (non-blank-count (:cells %)))))
        header-idx (first (keep-indexed #(when (>= (non-blank-count (:cells %2)) 2) %1)
                                        rows))]
    (when header-idx
      (let [width (reduce max 0 (map (comp count :cells) rows))
            pad #(into % (repeat (- width (count %)) ""))
            [header & data] (map (comp pad :cells) (drop header-idx rows))
            groups (column-groups (:spans (nth rows header-idx)) (vec data) width)
            [header-row & data-rows] (->> data
                                          (map #(group-row groups :range %))
                                          (cons (group-row groups :header-range header))
                                          (fold-sub-header groups)
                                          drop-blank-columns)]
        (when-not (layout-table? data-rows)
          (matrix->dataset (cons header-row data-rows) table-idx))))))

(defn extract-tables
  "Extract HTML tables from a filing as a seq of tech.ml.dataset objects.

   filing    — a filing map (from e/filing, e/filings, e/filing-by-accession, etc.)
   Options:
     :n        — return only the nth table (0-indexed) among the tables that
                 pass the filters; returns a single dataset or nil
     :min-rows — only return tables with at least this many data rows
                 (default 2; :min-rows 1 also keeps single-row tables, and a
                 table always needs at least one data row)
     :min-cols — only return tables with at least this many columns (default 2)

   Returns a seq of datasets (or a single dataset when :n is used).
   Each dataset is named \"table-N\" where N is the original index in the HTML.
   Columns are in the table's left-to-right order.

   Cleanup applied to every table:
     - the header is the first row with at least two non-blank cells
     - colspan cells hold their value once; each data cell goes to the
       column of the header cell spanning it, and columns blank in every
       row are dropped
     - \"$\", \"(\", \")\" and \"%\" in cells of their own are merged into the
       adjacent number
     - a column whose cells are all numbers, blanks or dash placeholders
       (\"—\", \"–\", \"-\") is numeric: commas, currency symbols and \"%\"
       are stripped, parentheses mean negative, blanks and dashes are nil

   Tables that appear to be layout/navigation (no data row with at least two
   non-blank cells) are automatically filtered out.

   Returns nil (for :n) or an empty seq when the filing has no HTML content
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
     (tables/extract-tables f :n 0)

     ;; Third table
     (tables/extract-tables f :n 2)"
  [filing & {:keys [n min-rows min-cols] :or {min-rows 2 min-cols 2}}]
  (let [html-str (filing/filing-html filing)]
    (if-not (html-content? html-str)
      (if n nil [])
      (let [hick (parse-html html-str)
            table-nodes (sel/select (sel/tag :table) hick)
            all-datasets (->> table-nodes
                              (map-indexed (fn [idx node] (extract-table node idx)))
                              (keep identity)
                              (filter #(>= (ds/row-count %) min-rows))
                              (filter #(>= (ds/column-count %) min-cols)))]
        (if n
          (nth (vec all-datasets) n nil)
          all-datasets)))))
