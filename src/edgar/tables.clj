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

(defn- cell-ix-meta
  "The iXBRL numeric tagging of a td/th cell, as {:unit-ref \"usd\" :scale 6},
   when every ix:nonFraction value in the cell is tagged alike (a
   \"0.03% – 5.75%\" range counts once). nil for untagged cells and mixed
   tagging. A missing scale attribute is scale 0 per the iXBRL spec; the
   displayed number times 10^scale is the tagged value's magnitude (6 =
   millions, 3 = thousands, -2 = a percentage tagged as its decimal
   fraction). The ix sign attribute is deliberately ignored: extracted
   cells keep the displayed value, whose own sign (parentheses, minus)
   already parses, so a sign=\"-\" fact shown without one stays positive
   here as displayed."
  [cell]
  (let [metas (->> (tree-seq #(and (map? %) (:content %)) :content cell)
                   (filter #(and (map? %) (= :ix:nonfraction (:tag %))))
                   (keep (fn [t]
                           (when-let [ref (get-in t [:attrs :unitref])]
                             {:unit-ref ref
                              :scale (or (some-> (get-in t [:attrs :scale])
                                                 str/trim
                                                 parse-long)
                                         0)})))
                   distinct)]
    (when (= 1 (count metas)) (first metas))))

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
   {:cells <text by grid position> :metas <grid position -> cell-ix-meta,
    tagged cells only> :spans <[start end) of each own cell>
    :occupied <occupied map for the next row>}."
  [tr occupied]
  (loop [cells (cell-nodes tr) col 0 texts {} metas {} spans [] added {}]
    (cond
      (pos? (get occupied col 0))
      (recur cells (inc col) texts metas spans added)

      (empty? cells)
      {:cells (reduce-kv assoc (vec (repeat col "")) texts)
       :metas metas
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
               (if-let [m (cell-ix-meta cell)] (assoc metas col m) metas)
               (conj spans [col end])
               (cond-> added
                 (> rows 1) (into (map (fn [p] [p (dec rows)])) (range col end))))))))

(defn- layout-rows
  "Lay the tr nodes of one table out on a common grid (see layout-row).
   Returns one {:cells :spans :metas} map per tr, in order."
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
   symbols), trailing %, the Unicode minus sign, and spaces as thousands
   separators (\"1 234\"). Interior whitespace that is not grouping digits by
   three means two space-joined tokens (\"48,758 48\"), not a number — nil."
  [s]
  (when-not (str/blank? s)
    (let [t (-> s
                str/trim
                (str/replace #"^US\$" "")
                (str/replace #"[$€£¥%]" "")
                (str/replace "−" "-")
                str/trim
                (str/replace #"^\((.+)\)$" "-$1")
                str/trim)]
      (when (or (not (re-find #"\s" t))
                (re-matches #"-?\d{1,3}(?:\s\d{3})+(?:\.\d+)?" t))
        (let [clean (str/replace t #"[,\s]" "")]
          (when (re-matches #"-?(?:\d+\.?\d*|\.\d+)" clean)
            (if (str/includes? clean ".")
              (Double/parseDouble clean)
              (try (Long/parseLong clean)
                   (catch NumberFormatException _ (Double/parseDouble clean))))))))))

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

(defn- non-blank-positions
  "Positions of row's non-blank cells inside the grid range [s e)."
  [[s e] row]
  (filterv #(not (str/blank? (row %))) (range s e)))

(defn- split-bounds
  "Positions that split the grid range [s e) into one piece per logical
   column, with :split-by the index of the first data row with the most
   non-blank cells in the range (k of them; nil when no row has two). Those
   \"full\" rows dictate k pieces, and each boundary sits between the i-th
   and (i+1)-th cell of every full row — at the smallest (i+1)-th position.
   Rows place the same logical value at slightly different grid positions
   (a merged \"$\" shifts the number one cell right), so splitting at one
   row's own positions would misalign the others; should the full rows
   still not agree on a boundary, the first one's positions are used."
  [r data-rows]
  (let [poss (mapv #(non-blank-positions r %) data-rows)
        k (reduce max 0 (map count poss))]
    (when (> k 1)
      (let [full (filterv #(= k (count %)) poss)
            bounds (reduce (fn [acc i]
                             (let [lo (reduce max (map #(nth % i) full))
                                   hi (reduce min (map #(nth % (inc i)) full))]
                               (if (< lo hi) (conj acc hi) (reduced nil))))
                           []
                           (range (dec k)))]
        {:bounds (or bounds (vec (rest (first full))))
         :split-by (first (keep-indexed #(when (= k (count %2)) %1) poss))}))))

(defn- column-groups
  "Logical columns as {:range [start end) :header-range [start end)} grid
   ranges: one per header-row cell (a header cell spanning three grid
   columns owns all three, which is where Workiva puts \"$\", the number and
   a spacer), plus one per grid position no header cell covers — before,
   between (a rowspan from a dropped sparse row can occupy part of the
   header row, leaving a hole in its spans) or past them. A header
   cell over several sub-columns (data rows holding two or more values
   under it — one per year, or an amount + \"% of revenues\" pair per year)
   is split per split-bounds; every piece keeps the header cell as its
   :header-range and records the splitting row's index as :split-by.
   rows are padded to width."
  [header-spans data-rows width]
  (let [singletons (fn [from to] (map (fn [p] [p (inc p)]) (range from to)))
        ranges (loop [acc [] pos 0 spans (seq header-spans)]
                 (if-let [[s e :as span] (first spans)]
                   (recur (conj (into acc (singletons pos s)) span)
                          (long e)
                          (next spans))
                   (into acc (singletons pos width))))]
    (into []
          (mapcat (fn [[s e :as r]]
                    (if-let [{:keys [bounds split-by]} (split-bounds r data-rows)]
                      (let [starts (into [s] bounds)]
                        (map (fn [p] {:range p :header-range r :split-by split-by})
                             (mapv vector starts (conj (vec (rest starts)) e))))
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

(defn- sub-header-row?
  "True when a grid data row looks like a sub-header for the given header
   ranges: every one of its non-blank cells lies inside one of them, so the
   label region is blank (a \"September 30,\" or year row)."
  [ranges row]
  (every? (fn [p] (some (fn [[s e]] (and (<= s p) (< p e))) ranges))
          (keep-indexed (fn [i c] (when-not (str/blank? c) i)) row)))

(defn- sub-header-count
  "How many leading grid data rows are further header rows to fold into the
   column names. Walking from the top, a row folds when it is
   - a row before the first splitting row (see column-groups) whose
     non-blank cells all sit inside split header ranges (period/date rows,
     year rows above the row that fixed the split), or
   - a label-less row — nothing under the first header range — with no
     numeric cell and some text (\"(unaudited)\", a lone
     \"September 30, 2026\"), or
   - the splitting row itself, when its non-blanks all sit inside split
     header ranges (a year row that split its period header into years)
     or it is label-less with nothing parsing as a number (\"Amount\" /
     \"Rate\" under a year); folding stops there. A row with text under
     the first header range is data, never folded — an all-text row with
     a real label (a signature or director grid) must survive."
  [groups data-rows]
  (let [splits (filterv :split-by groups)
        srs (distinct (map :header-range splits))
        b (some->> (seq (map :split-by splits)) (reduce min))
        first-range (:header-range (first groups))
        value-ranges (remove #{first-range} (distinct (map :header-range groups)))
        label-less-text? (fn [row]
                           (and (not-any? parse-number row)
                                (some #(re-find #"[A-Za-z]" %) row)
                                (sub-header-row? value-ranges row)))]
    (loop [i 0]
      (if (>= i (count data-rows))
        i
        (let [row (nth data-rows i)]
          (cond
            (and b (= i b)) (if (or (sub-header-row? srs row)
                                    (and (not-any? parse-number row)
                                         (sub-header-row? value-ranges row)))
                              (inc i)
                              i)
            (and b (< i b) (sub-header-row? srs row)) (recur (inc i))
            (label-less-text? row) (recur (inc i))
            :else i))))))

(defn- spread-row
  "Map a sub-header grid row onto the logical columns with colspan
   semantics: within each header range, a cell covers the pieces from its
   own up to the one holding the row's next non-blank cell in that range
   (\"2025\" over an amount + % pair; \"September 30,\" over all four).
   Returns one string per group."
  [groups row]
  (mapv (fn [{[gs ge] :range hr :header-range}]
          (let [qs (non-blank-positions hr row)
                own (filterv #(and (<= gs %) (< % ge)) qs)]
            (if (seq own)
              (str/join " " (map row own))
              (or (some->> qs (filter #(< % gs)) last row) ""))))
        groups))

(defn- fold-sub-headers
  "Append the texts of the sub-header grid rows (see sub-header-count) to
   the grouped header row, each spread per spread-row."
  [groups header-row sub-rows]
  (reduce (fn [hdr sub]
            (mapv (fn [h s] (str/trim (str h " " s))) hdr (spread-row groups sub)))
          header-row
          sub-rows))

(defn- column-ix-metas
  "Per logical column, the one iXBRL tagging its data cells agree on, with
   the unit ref resolved through the document's xbrli:unit definitions:
   {:unit \"USD\" :scale 6}, or nil when the column's cells are untagged or
   tagged differently. cells-rows and metas-rows are the grid data rows'
   padded :cells and their :metas, sub-header rows already removed."
  [groups unit-map cells-rows metas-rows]
  (mapv (fn [{r :range}]
          (let [ms (distinct
                    (for [[cells metas] (map vector cells-rows metas-rows)
                          p (non-blank-positions r cells)
                          :let [m (metas p)]
                          :when m]
                      m))]
            (when (= 1 (count ms))
              (let [{:keys [unit-ref scale]} (first ms)]
                {:unit (get unit-map unit-ref unit-ref) :scale scale}))))
        groups))

(defn- non-blank-column-idx
  "The indices of the columns that are non-blank in at least one data row
   (the header row is first and does not count)."
  [[_ & data :as rows]]
  (filterv (fn [ci] (some #(not (str/blank? (nth % ci))) data))
           (range (count (first rows)))))

;;; ---------------------------------------------------------------------------
;;; Table → dataset conversion
;;; ---------------------------------------------------------------------------

(defn- matrix->dataset
  "Convert a seq of row-vectors (all strings) into a tech.ml.dataset.
   First row is used as column names. Remaining rows are data.
   Pads short rows with blanks; truncates overlong rows.
   Columns keep their left-to-right order from the HTML table.
   col-metas, when given, holds one {:unit :scale} map (or nil) per column,
   attached as column metadata to the columns that infer as numeric."
  ([rows table-idx] (matrix->dataset rows table-idx nil))
  ([rows table-idx col-metas]
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
           columns (map (fn [nm vals m]
                          (if (and m (some number? vals))
                            (ds/new-column nm vals m)
                            (ds/new-column nm vals)))
                        col-names
                        (map infer-column col-seqs)
                        (concat (or col-metas []) (repeat nil)))]
       (ds/new-dataset {:dataset-name (str "table-" table-idx)} columns)))))

;;; ---------------------------------------------------------------------------
;;; Main extraction pipeline
;;; ---------------------------------------------------------------------------

(defn- parse-html
  "Parse an HTML string into a hickory tree."
  [html-str]
  (hickory/as-hickory (hickory/parse html-str)))

(defn- unit-definitions
  "Map of the document's xbrli:unit ids (ix:nonFraction unitRef targets) to
   unit strings: the measure names without their namespace prefix
   (iso4217:USD → \"USD\", xbrli:shares → \"shares\", xbrli:pure → \"pure\"),
   a divide joined as \"USD/shares\" — the same spelling as XBRL fact
   :unit values."
  [hick]
  (into {}
        (keep (fn [u]
                (when-let [id (get-in u [:attrs :id])]
                  (let [measures (->> (tree-seq #(and (map? %) (:content %)) :content u)
                                      (filter #(and (map? %) (= :xbrli:measure (:tag %))))
                                      (map #(-> (apply str (filter string? (:content %)))
                                                str/trim
                                                (str/replace #"^[^:]+:" ""))))]
                    (when (seq measures) [id (str/join "/" measures)])))))
        (sel/select (sel/tag :xbrli:unit) hick)))

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
   into their numbers, sub-header rows directly below the header (period
   dates, years, \"Amount\"/\"Rate\" labels) are folded into the column
   names, and columns blank in every data row are removed. Numeric columns
   whose cells carry one agreed iXBRL tagging get {:unit :scale} column
   metadata, the unit ref resolved through unit-map (see column-ix-metas)."
  [table-node table-idx unit-map]
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
            data (vec data)
            groups (column-groups (:spans (nth rows header-idx)) data width)
            n-sub (sub-header-count groups data)
            header-row (fold-sub-headers groups
                                         (group-row groups :header-range header)
                                         (subvec data 0 n-sub))
            col-metas (column-ix-metas groups unit-map (subvec data n-sub)
                                       (mapv :metas (drop (+ header-idx 1 n-sub) rows)))
            data-rows (mapv #(group-row groups :range %) (subvec data n-sub))
            keep-idx (non-blank-column-idx (into [header-row] data-rows))
            header-row (mapv header-row keep-idx)
            data-rows (mapv (fn [row] (mapv row keep-idx)) data-rows)]
        (when-not (layout-table? data-rows)
          (matrix->dataset (cons header-row data-rows) table-idx
                           (mapv col-metas keep-idx)))))))

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
     - a header cell over several sub-columns (one per year, or an amount +
       \"% of revenues\" pair per year) becomes one column per sub-column,
       and sub-header rows below the header (period dates, years,
       \"Amount\"/\"Rate\" labels) are folded into the column names
     - \"$\", \"(\", \")\" and \"%\" in cells of their own are merged into the
       adjacent number
     - a column whose cells are all numbers, blanks or dash placeholders
       (\"—\", \"–\", \"-\") is numeric: commas, currency symbols and \"%\"
       are stripped, parentheses mean negative, blanks and dashes are nil
     - a numeric column whose cells carry one agreed iXBRL tagging gets
       {:unit :scale} column metadata — (meta (ds/column d \"2026\")) =>
       {:unit \"USD\" :scale 6 …} means the values are USD in millions
       (displayed value × 10^scale; \"pure\" with :scale -2 is a
       percentage). Units spell like XBRL fact :unit values
       (\"USD\", \"shares\", \"USD/shares\", \"pure\"); untagged (pre-iXBRL)
       filings and mixed-tagged columns carry no metadata

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
            unit-map (unit-definitions hick)
            all-datasets (->> table-nodes
                              (map-indexed (fn [idx node] (extract-table node idx unit-map)))
                              (keep identity)
                              (filter #(>= (ds/row-count %) min-rows))
                              (filter #(>= (ds/column-count %) min-cols)))]
        (if n
          (nth (vec all-datasets) n nil)
          all-datasets)))))
