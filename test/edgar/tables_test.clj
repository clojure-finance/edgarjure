(ns edgar.tables-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.tables :as tables]
            [edgar.filing]
            [hickory.core :as hickory]
            [hickory.select :as sel]
            [tech.v3.dataset :as ds]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; parse-number
;;; ---------------------------------------------------------------------------

(deftest parse-number-test
  (let [f #'edgar.tables/parse-number]
    (testing "plain integer"
      (is (= 1234 (f "1234"))))
    (testing "comma-separated integer"
      (is (= 1234567 (f "1,234,567"))))
    (testing "decimal number"
      (is (= 3.14 (f "3.14"))))
    (testing "dollar prefix stripped"
      (is (= 500 (f "$500"))))
    (testing "percent suffix stripped"
      (is (= 12 (f "12%"))))
    (testing "parentheses mean negative"
      (is (= -100 (f "(100)"))))
    (testing "negative decimal in parens"
      (is (= -1.5 (f "(1.5)"))))
    (testing "blank string returns nil"
      (is (nil? (f ""))))
    (testing "nil returns nil"
      (is (nil? (f nil))))
    (testing "non-numeric string returns nil"
      (is (nil? (f "n/a"))))
    (testing "dash-only returns nil"
      (is (nil? (f "—"))))
    (testing "regular space as thousand separator is parsed (u00A0 normalised upstream by cell-text)"
      (is (= 1234 (f "1 234"))))
    (testing "merged fragments: negative percentage, negative dollar amount"
      (is (= -4 (f "(4)%")))
      (is (= -1234 (f "$(1,234)"))))
    (testing "Unicode minus sign"
      (is (= -5 (f "\u22125"))))
    (testing "strings Double/parseDouble would accept are not numbers"
      (is (nil? (f "1.5d")))
      (is (nil? (f "NaN")))
      (is (nil? (f "1e5"))))))

;;; ---------------------------------------------------------------------------
;;; infer-column
;;; ---------------------------------------------------------------------------

(deftest infer-column-test
  (let [f #'edgar.tables/infer-column]
    (testing "all-integer cells → Long vector"
      (let [result (f ["1" "2" "3"])]
        (is (every? #(instance? Long %) result))))
    (testing "mixed integer and decimal → all-numeric vector (Long or Double per cell)"
      (let [result (f ["1" "2.5" "3"])]
        (is (every? number? result))
        (is (= 2.5 (second result)))))
    (testing "non-numeric cells → String vector (passthrough)"
      (let [result (f ["foo" "bar" "baz"])]
        (is (= ["foo" "bar" "baz"] result))))
    (testing "mixed numeric and non-numeric → String vector"
      (let [result (f ["100" "n/a" "200"])]
        (is (= ["100" "n/a" "200"] result))))
    (testing "dash placeholders do not make a numeric column a string column"
      (is (= [10 nil -6 nil nil] (f ["10" "—" "(6)" "$—" ""])))
      (is (= [1 nil 2] (f ["1" "–" "2"]))))
    (testing "a column of nothing but dashes stays strings"
      (is (= ["—" "—"] (f ["—" "—"]))))))

(deftest infer-column-blank-cells-test
  (let [f #'edgar.tables/infer-column]
    (testing "blank cell in otherwise numeric column produces nil, not string fallback"
      (let [result (f ["100" "" "300"])]
        (is (= 3 (count result)))
        (is (= 100 (first result)))
        (is (nil? (second result)))
        (is (= 300 (nth result 2)))))
    (testing "all-blank column stays as strings (no data to infer from)"
      (let [result (f ["" "" ""])]
        (is (= ["" "" ""] result))))
    (testing "single blank in double column keeps doubles elsewhere"
      (let [result (f ["1.5" "" "3.5"])]
        (is (nil? (second result)))
        (is (every? #(or (nil? %) (double? %)) result))))))

;;; ---------------------------------------------------------------------------
;;; layout-table?
;;; ---------------------------------------------------------------------------

(deftest layout-table-test
  (let [f #'edgar.tables/layout-table?]
    (testing "a single single-cell row is a layout table"
      (is (f [["only one row"]])))
    (testing "a single multi-cell data row is data (row count is :min-rows' job)"
      (is (not (f [["Revenue" "100"]]))))
    (testing "all single-cell rows is a layout table"
      (is (f [["a"] ["b"] ["c"]])))
    (testing "table with multi-cell rows is not a layout table"
      (is (not (f [["a" "b"] ["1" "2"] ["3" "4"]]))))
    (testing "empty rows is a layout table"
      (is (f [])))
    (testing "rows with one populated cell and one blank are still single-cell (layout)"
      ;; ["a" ""] has 1 non-blank cell → layout
      (is (f [["a" ""] ["b" ""]])))
    (testing "rows with 2+ non-blank cells are not layout even if blanks present"
      ;; ["a" "" "b"] has 2 non-blank cells → not layout
      (is (not (f [["a" "" "b"] ["1" "" "2"]]))))))

;;; ---------------------------------------------------------------------------
;;; Row layout — the cells of a single tr on the grid; blanks preserved for
;;; column alignment
;;; ---------------------------------------------------------------------------

(defn- row-cells [tr] (:cells (#'edgar.tables/layout-row tr {})))

(deftest row-cells-preserves-blanks-test
  (let [f row-cells]
    (testing "blank cells are preserved (not filtered out)"
      (let [tr {:type :element :tag :tr :attrs {}
                :content [{:type :element :tag :td :attrs {} :content ["A"]}
                          {:type :element :tag :td :attrs {} :content [""]}
                          {:type :element :tag :td :attrs {} :content ["B"]}]}
            result (f tr)]
        (is (= ["A" "" "B"] result) "all three cells returned including blank")))
    (testing "fully empty row returns vector of blank strings (one per cell)"
      (let [tr {:type :element :tag :tr :attrs {}
                :content [{:type :element :tag :td :attrs {} :content [""]}
                          {:type :element :tag :td :attrs {} :content [""]}]}
            result (f tr)]
        (is (= 2 (count result)))
        (is (every? str/blank? result))))
    (testing "no cells returns empty vector"
      (let [tr {:type :element :tag :tr :attrs {} :content []}]
        (is (= [] (f tr)))))))

;;; ---------------------------------------------------------------------------
;;; matrix->dataset
;;; ---------------------------------------------------------------------------

(deftest matrix->dataset-test
  (let [f #'edgar.tables/matrix->dataset
        rows [["Company" "Revenue" "Net Income"]
              ["AAPL" "394,328" "99,803"]
              ["MSFT" "211,915" "72,738"]]
        ds (f rows 0)]
    (testing "returns a dataset"
      (is (instance? tech.v3.dataset.impl.dataset.Dataset ds)))
    (testing "column count equals header length"
      (is (= 3 (ds/column-count ds))))
    (testing "row count equals data rows"
      (is (= 2 (ds/row-count ds))))
    (testing "column names match header"
      (let [cols (set (map name (ds/column-names ds)))]
        (is (contains? cols "Company"))
        (is (contains? cols "Revenue"))
        (is (contains? cols "Net Income"))))
    (testing "numeric columns are inferred"
      (let [cols (ds/column-names ds)
            rev-col (ds/column ds (first (filter #(= "Revenue" (name %)) cols)))]
        (is (every? number? rev-col)))))
  (testing "returns nil when fewer than 2 rows"
    (is (nil? (#'edgar.tables/matrix->dataset [["Header"]] 0)))))

(deftest matrix->dataset-dedup-column-names-test
  (let [f #'edgar.tables/matrix->dataset]
    (testing "duplicate column names are suffixed"
      (is (= ["Name" "Name_1" "Value"]
             (ds/column-names (f [["Name" "Name" "Value"] ["A" "B" "1"] ["C" "D" "2"]] 0)))))
    (testing "a suffixed name never collides with a later literal header"
      (is (= ["Name" "Name_1" "Name_1_1"]
             (ds/column-names (f [["Name" "Name" "Name_1"] ["A" "B" "C"]] 0)))))))

(deftest matrix->dataset-column-order-test
  ;; Regression: columns were collected in a hash map, which scrambles the
  ;; order once a table has more than 8 columns.
  (let [header (into ["" "Label"] (map #(str "FY" %) (range 2015 2027)))
        row (into ["" "Net sales"] (map str (range 12)))
        d (#'edgar.tables/matrix->dataset [header row row] 14)]
    (testing "column order matches the table, left to right"
      (is (= (into ["col_0" "Label"] (map #(str "FY" %) (range 2015 2027)))
             (ds/column-names d))))
    (testing "values stay under their own header"
      (is (= [0 0] (vec (ds/column d "FY2015"))))
      (is (= [11 11] (vec (ds/column d "FY2026")))))
    (testing "dataset name is kept"
      (is (= "table-14" (ds/dataset-name d))))))

;;; ---------------------------------------------------------------------------
;;; cell-text and row-cells — unit tests using inline hickory nodes
;;; ---------------------------------------------------------------------------

(deftest cell-text-test
  (let [f #'edgar.tables/cell-text]
    (testing "extracts text from a simple td node"
      (let [td {:type :element :tag :td :attrs {} :content ["hello world"]}]
        (is (= "hello world" (f td)))))
    (testing "collapses internal whitespace"
      (let [td {:type :element :tag :td :attrs {} :content ["  hello   world  "]}]
        (is (= "hello world" (f td)))))
    (testing "handles nested elements"
      (let [td {:type :element :tag :td :attrs {}
                :content [{:type :element :tag :span :attrs {} :content ["nested"]}]}]
        (is (= "nested" (f td)))))
    (testing "non-breaking space is normalised to regular space"
      (let [td {:type :element :tag :td :attrs {} :content [(str "1" \u00A0 "234")]}]
        (is (= "1 234" (f td)))))
    (testing "cell with only nbsp returns blank after normalisation"
      (let [td {:type :element :tag :td :attrs {} :content ["\u00A0"]}]
        (is (str/blank? (f td)))))))

(deftest row-cells-test
  (let [f row-cells
        tr {:type :element :tag :tr :attrs {}
            :content [{:type :element :tag :th :attrs {} :content ["Header"]}
                      {:type :element :tag :td :attrs {} :content ["Data"]}]}
        cells (f tr)]
    (testing "th and td cells both yield their plain text, one entry per cell"
      (is (= ["Header" "Data"] cells)))))

(deftest row-cells-direct-children-only-test
  (let [f row-cells
        ;; Nested table inside a td — inner cells should NOT appear in outer row
        tr {:type :element :tag :tr :attrs {}
            :content [{:type :element :tag :td :attrs {} :content ["outer"]}
                      {:type :element :tag :td :attrs {}
                       :content [{:type :element :tag :table :attrs {}
                                  :content [{:type :element :tag :tr :attrs {}
                                             :content [{:type :element :tag :td :attrs {}
                                                        :content ["inner"]}]}]}]}]}
        cells (f tr)]
    (testing "only direct td/th children are counted"
      (is (= 2 (count cells))))))

(deftest row-cells-dom-order-test
  (let [f row-cells]
    (testing "mixed th/td cells preserve DOM order, not th-first"
      ;; Row: td "A", th "B", td "C" — th-first bug would produce B, A, C
      (let [tr {:type :element :tag :tr :attrs {}
                :content [{:type :element :tag :td :attrs {} :content ["A"]}
                          {:type :element :tag :th :attrs {} :content ["B"]}
                          {:type :element :tag :td :attrs {} :content ["C"]}]}
            cells (f tr)]
        (is (= ["A" "B" "C"] cells))))))

(deftest row-cells-colspan-test
  (let [f row-cells]
    (testing "colspan=3 occupies 3 grid positions; the text is in the first only"
      (let [tr {:type :element :tag :tr :attrs {}
                :content [{:type :element :tag :th :attrs {:colspan "3"} :content ["Period"]}
                          {:type :element :tag :td :attrs {} :content ["Value"]}]}
            cells (f tr)]
        (is (= ["Period" "" "" "Value"] cells))))
    (testing "colspan=1 (explicit) produces exactly 1 entry"
      (let [tr {:type :element :tag :tr :attrs {}
                :content [{:type :element :tag :td :attrs {:colspan "1"} :content ["X"]}]}
            cells (f tr)]
        (is (= 1 (count cells)))))
    (testing "missing colspan attr produces 1 entry (default)"
      (let [tr {:type :element :tag :tr :attrs {}
                :content [{:type :element :tag :td :attrs {} :content ["Y"]}]}
            cells (f tr)]
        (is (= 1 (count cells)))))
    (testing "invalid colspan falls back to 1"
      (let [tr {:type :element :tag :tr :attrs {}
                :content [{:type :element :tag :td :attrs {:colspan "bad"} :content ["Z"]}]}
            cells (f tr)]
        (is (= 1 (count cells)))))))

(defn- html-rows
  "The direct tr nodes of the first table in an HTML snippet."
  [html]
  (#'edgar.tables/direct-rows
   (first (sel/select (sel/tag :table) (hickory/as-hickory (hickory/parse html))))))

(deftest layout-rows-rowspan-test
  (let [rows (#'edgar.tables/layout-rows
              (html-rows "<table>
                <tr><td rowspan=\"2\">Label</td><td colspan=\"2\">2025</td></tr>
                <tr><td>Amount</td><td>Rate</td></tr>
                <tr><td>Notes</td><td>100</td><td>4%</td></tr>
              </table>"))]
    (testing "a rowspan cell covers its column in the next row, shifting that row's cells right"
      (is (= [["Label" "2025" ""] ["" "Amount" "Rate"] ["Notes" "100" "4%"]]
             (mapv :cells rows))))
    (testing ":spans are the grid ranges of each row's own cells"
      (is (= [[[0 1] [1 3]] [[1 2] [2 3]] [[0 1] [1 2] [2 3]]]
             (mapv :spans rows))))))

;;; ---------------------------------------------------------------------------
;;; merge-fragments and dash placeholders
;;; ---------------------------------------------------------------------------

(deftest merge-fragments-test
  (let [f #'edgar.tables/merge-fragments]
    (testing "a currency symbol joins the number to its right, at the number's position"
      (is (= ["Americas" "" "$178,353" ""] (f ["Americas" "$" "178,353" ""]))))
    (testing "% and ) join the number to their left"
      (is (= ["7%" "" ""] (f ["7" "" "%"])))
      (is (= ["(4)%" ""] (f ["(4" ")%"])))
      (is (= ["" "$(1,234)" ""] (f ["$" "(1,234" ")"]))))
    (testing "currency symbol before a dash placeholder"
      (is (= ["" "$—"] (f ["$" "—"]))))
    (testing "fragments next to text are left alone"
      (is (= ["$" "n/a"] (f ["$" "n/a"])))
      (is (= ["Total" "%"] (f ["Total" "%"]))))))

(deftest dash-placeholder-test
  (let [f #'edgar.tables/dash-placeholder?]
    (is (every? f ["—" "–" "-" "$—" "$ —" "—%"]))
    (is (not-any? f ["" "n/a" "-5" "2025 – 2062"]))))

;;; ---------------------------------------------------------------------------
;;; html-content?
;;; ---------------------------------------------------------------------------

(deftest html-content-test
  (is (tables/html-content? "<!DOCTYPE html><html><body></body></html>"))
  (is (tables/html-content? "<HTML><BODY>x</BODY></HTML>"))
  (is (tables/html-content? "<body><table></table></body>"))
  (testing "SGML plain-text filings carry <TABLE> tags but are not HTML"
    (is (not (tables/html-content? "<TABLE>\n<CAPTION>\nITEM 1. BUSINESS"))))
  (is (not (tables/html-content? "ITEM 1. Business")))
  (is (not (tables/html-content? nil))))

;;; ---------------------------------------------------------------------------
;;; extract-table — private fn, using inline HTML
;;; ---------------------------------------------------------------------------

(deftest extract-table-test
  (let [f #'edgar.tables/extract-table
        table-html "<table>
          <tr><th>Company</th><th>Revenue</th><th>Profit</th></tr>
          <tr><td>AAPL</td><td>394328</td><td>99803</td></tr>
          <tr><td>MSFT</td><td>211915</td><td>72738</td></tr>
        </table>"
        tree (hickory/as-hickory (hickory/parse table-html))
        table-node (first (sel/select (sel/tag :table) tree))
        result (f table-node 0)]
    (testing "returns a dataset"
      (is (instance? tech.v3.dataset.impl.dataset.Dataset result)))
    (testing "has correct column count"
      (is (= 3 (ds/column-count result))))
    (testing "has correct row count"
      (is (= 2 (ds/row-count result))))
    (testing "numeric columns are typed"
      (let [rev-col (ds/column result "Revenue")]
        (is (every? number? rev-col))))))

(deftest extract-table-layout-returns-nil-test
  (let [f #'edgar.tables/extract-table
        layout-html "<table>
          <tr><td>Layout text spanning full width</td></tr>
          <tr><td>Another single-cell row</td></tr>
        </table>"
        tree (hickory/as-hickory (hickory/parse layout-html))
        table-node (first (sel/select (sel/tag :table) tree))]
    (testing "layout table returns nil"
      (is (nil? (f table-node 0))))))

(deftest extract-table-blank-header-cell-alignment-test
  ;; Regression test for Issue #6:
  ;; Header row ["A" "" "B"] was previously stripped to ["A" "B"] (2 cols),
  ;; misaligning 3-column data rows and silently dropping the middle column.
  (let [f #'edgar.tables/extract-table
        html "<table>
          <tr><th>Company</th><th></th><th>Revenue</th></tr>
          <tr><td>AAPL</td><td>2024</td><td>394328</td></tr>
          <tr><td>MSFT</td><td>2024</td><td>211915</td></tr>
        </table>"
        tree (hickory/as-hickory (hickory/parse html))
        table-node (first (sel/select (sel/tag :table) tree))
        result (f table-node 0)]
    (testing "returns a dataset"
      (is (instance? tech.v3.dataset.impl.dataset.Dataset result)))
    (testing "3 columns preserved even though middle header cell is blank"
      (is (= 3 (ds/column-count result))))
    (testing "Revenue column is at index 2, not index 1"
      (let [col-names (mapv name (ds/column-names result))]
        (is (= "Revenue" (nth col-names 2)))))
    (testing "Revenue data is not shifted — contains 394328 and 211915"
      (let [rev-col (ds/column result "Revenue")]
        (is (= #{394328 211915} (set rev-col)))))
    (testing "middle column (auto-named col_1) contains the year values 2024"
      ;; blank header → auto-named "col_1"
      (let [col-names (set (map name (ds/column-names result)))]
        (is (contains? col-names "col_1"))))))

(deftest extract-table-nested-no-double-count-test
  (let [f #'edgar.tables/extract-table
        ;; Outer table has 2 data rows; each td in row 2 contains a nested table.
        ;; Without the direct-rows fix, sel/select would also collect the inner <tr>
        ;; nodes, inflating the row count.
        html "<table>
          <tr><th>Company</th><th>Revenue</th></tr>
          <tr><td>AAPL</td><td><table><tr><td>inner</td></tr></table>394328</td></tr>
          <tr><td>MSFT</td><td>211915</td></tr>
        </table>"
        tree (hickory/as-hickory (hickory/parse html))
        table-node (first (sel/select (sel/tag :table) tree))
        result (f table-node 0)]
    (testing "returns a dataset (not nil)"
      (is (some? result)))
    (testing "nested-table rows are not double-counted — exactly 2 data rows"
      (is (= 2 (ds/row-count result))))))

;;; ---------------------------------------------------------------------------
;;; Fixture HTML file — integration test for extract-tables via mock filing
;;; ---------------------------------------------------------------------------

(def ^:private fixture-tables-html
  (slurp (io/resource "fixtures/tables.html")))

(deftest extract-tables-fixture-test
  (let [result (with-redefs [edgar.filing/filing-html (fn [_] fixture-tables-html)]
                 (tables/extract-tables {:form "10-K"}))]
    (testing "returns a seq"
      (is (seqable? result)))
    (testing "finds the 3 data tables (layout table excluded)"
      (is (= ["table-0" "table-2" "table-3"] (map ds/dataset-name result))))
    (testing "all results are datasets"
      (is (every? #(instance? tech.v3.dataset.impl.dataset.Dataset %) result)))
    (testing "first table has Company/Revenue/Net Income columns, in order"
      (is (= ["Company" "Revenue" "Net Income"] (ds/column-names (first result)))))
    (testing "numeric values are inferred as numbers"
      (let [first-ds (first result)]
        (is (= [:string :int64 :int64]
               (mapv #(-> (ds/column first-ds %) meta :datatype) (ds/column-names first-ds))))
        (is (= [394328 211915 282836] (vec (ds/column first-ds "Revenue"))))))
    (testing "layout-only table (single-cell rows) is excluded"
      (let [all-col-counts (map ds/column-count result)]
        (is (every? #(>= % 2) all-col-counts))))))

(deftest extract-tables-dedup-columns-fixture-test
  (let [result (with-redefs [edgar.filing/filing-html (fn [_] fixture-tables-html)]
                 (tables/extract-tables {:form "10-K"}))
        ;; The last table in the fixture has duplicate "Name" headers
        last-ds (last result)]
    (testing "duplicate column names are suffixed in fixture table"
      (is (= ["Name" "Name_1" "Value"] (ds/column-names last-ds))))))

(deftest extract-tables-nth-option-test
  (let [result (with-redefs [edgar.filing/filing-html (fn [_] fixture-tables-html)]
                 (tables/extract-tables {:form "10-K"} :n 0))]
    (testing ":n 0 returns a single dataset (not a seq)"
      (is (instance? tech.v3.dataset.impl.dataset.Dataset result)))))

(deftest extract-tables-nth-out-of-range-test
  (let [result (with-redefs [edgar.filing/filing-html (fn [_] fixture-tables-html)]
                 (tables/extract-tables {:form "10-K"} :n 9999))]
    (testing ":n out of range returns nil"
      (is (nil? result)))))

(deftest extract-tables-min-rows-filter-test
  (let [extract (fn [html & opts]
                  (with-redefs [edgar.filing/filing-html (fn [_] html)]
                    (apply tables/extract-tables {:form "10-K"} opts)))]
    (testing "fixture data tables have 3, 3 and 2 data rows"
      (is (= 3 (count (extract fixture-tables-html :min-rows 1))))
      (is (= ["table-0" "table-2"]
             (map ds/dataset-name (extract fixture-tables-html :min-rows 3))))
      (is (= 0 (count (extract fixture-tables-html :min-rows 5)))))
    (testing ":min-rows 1 keeps a table with a single data row; the default does not"
      (let [html "<html><body><table>
                    <tr><th>Metric</th><th>2025</th></tr>
                    <tr><td>Revenue</td><td>100</td></tr>
                  </table></body></html>"]
        (is (= 0 (count (extract html))))
        (is (= [["Metric" "2025"] 1]
               (let [[d] (extract html :min-rows 1)]
                 [(ds/column-names d) (ds/row-count d)])))
        (is (= 1 (count (extract html :min-rows 0))))))))

(deftest extract-tables-nil-html-test
  (testing "nil html returns empty seq (not a crash)"
    (let [result (with-redefs [edgar.filing/filing-html (fn [_] nil)]
                   (tables/extract-tables {:form "10-K"}))]
      (is (= [] result))))
  (testing "nil html with :n returns nil (not a crash)"
    (let [result (with-redefs [edgar.filing/filing-html (fn [_] nil)]
                   (tables/extract-tables {:form "10-K"} :n 0))]
      (is (nil? result))))
  (testing "blank html returns empty seq"
    (let [result (with-redefs [edgar.filing/filing-html (fn [_] "   ")]
                   (tables/extract-tables {:form "10-K"}))]
      (is (= [] result))))
  (testing "plain text (no HTML tags) returns empty seq"
    (let [result (with-redefs [edgar.filing/filing-html (fn [_] "ITEM 1. BUSINESS\nSome plain text.")]
                   (tables/extract-tables {:form "10-K"}))]
      (is (= [] result))))
  (testing "plain text with :n returns nil"
    (let [result (with-redefs [edgar.filing/filing-html (fn [_] "ITEM 1. BUSINESS\nSome plain text.")]
                   (tables/extract-tables {:form "10-K"} :n 0))]
      (is (nil? result))))
  (testing "valid HTML with tables still works after guard"
    (let [result (with-redefs [edgar.filing/filing-html (fn [_] fixture-tables-html)]
                   (tables/extract-tables {:form "10-K"}))]
      (is (seq result))
      (is (every? #(instance? tech.v3.dataset.impl.dataset.Dataset %) result)))))

;;; ---------------------------------------------------------------------------
;;; Real Workiva/iXBRL tables — trimmed from Apple's fiscal 2025 10-K
;;; ---------------------------------------------------------------------------

(def ^:private aapl-tables-html
  (slurp (io/resource "fixtures/aapl_10k_tables.html")))

(defn- aapl-tables []
  (with-redefs [edgar.filing/filing-html (fn [_] aapl-tables-html)]
    (vec (tables/extract-tables {:form "10-K"}))))

(defn- col-types [d]
  (mapv #(-> (ds/column d %) meta :datatype) (ds/column-names d)))

(deftest aapl-net-sales-by-segment-test
  ;; Grid: empty first row; label colspan 3; "$" and the number in two
  ;; cells under a colspan-3 header; "%" in its own cell after "7"/"(4)".
  (let [d (first (aapl-tables))]
    (testing "one column per header cell, no duplicated or all-blank columns"
      (is (= ["col_0" "2025" "Change" "2024" "Change_1" "2023"] (ds/column-names d)))
      (is (= [:string :int64 :int64 :int64 :int64 :int64] (col-types d))))
    (testing "row labels appear once"
      (is (= ["Americas" "Europe" "Greater China" "Japan" "Rest of Asia Pacific"
              "Total net sales"]
             (vec (ds/column d "col_0")))))
    (testing "\"$\" fragments merge into the number in the same column"
      (is (= [178353 111032 64377 28703 33696 416161] (vec (ds/column d "2025"))))
      (is (= [162560 94294 72559 24257 29615 383285] (vec (ds/column d "2023")))))
    (testing "\"%\" fragments merge; parentheses mean negative"
      (is (= [7 10 -4 15 10 6] (vec (ds/column d "Change"))))
      (is (= [3 7 -8 3 4 2] (vec (ds/column d "Change_1")))))))

(deftest aapl-term-debt-test
  ;; rowspan=2 header cells over a sub-header row ("Amount" / "Effective
  ;; Interest Rate" under each year) and "—" placeholders.
  (let [d (second (aapl-tables))]
    (testing "rowspan keeps the sub-header aligned; it is folded into the names"
      (is (= ["col_0" "Maturities (calendar year)"
              "2025 Amount (in millions)" "2025 Effective Interest Rate"
              "2024 Amount (in millions)" "2024 Effective Interest Rate"]
             (ds/column-names d))))
    (testing "amount columns are numeric despite \"—\" and \"(309)\""
      (is (= :int64 (-> (ds/column d "2025 Amount (in millions)") meta :datatype)))
      (is (= [nil 86781 nil 4500 91281 -309 -294 90678 -12350 78328]
             (vec (ds/column d "2025 Amount (in millions)"))))
      (is (= [nil 97341 nil nil 97341 -321 -358 96662 -10912 85750]
             (vec (ds/column d "2024 Amount (in millions)")))))
    (testing "text columns keep their text"
      (is (= "Fixed-rate 0.000% – 4.850% notes" (nth (ds/column d "col_0") 1)))
      (is (= "0.03% – 5.75%" (nth (ds/column d "2025 Effective Interest Rate") 1))))))
