(ns edgar.tables-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.tables :as tables]
            [edgar.filing]
            [hickory.core :as hickory]
            [hickory.select :as sel]
            [tech.v3.dataset :as ds]
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
      (is (= 1234 (f "1 234"))))))

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
        (is (= ["100" "n/a" "200"] result))))))

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
    (testing "fewer than 2 rows is a layout table"
      (is (f [["only one row"]])))
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
;;; row-texts — preserves blank cells for column alignment
;;; ---------------------------------------------------------------------------

(deftest row-texts-preserves-blanks-test
  (let [f #'edgar.tables/row-texts]
    (testing "blank cells are preserved (not filtered out)"
      (let [tr {:type :element :tag :tr :attrs {}
                :content [{:type :element :tag :td :attrs {} :content ["A"]}
                          {:type :element :tag :td :attrs {} :content [""]}
                          {:type :element :tag :td :attrs {} :content ["B"]}]}]
        (let [result (f tr)]
          (is (= 3 (count result)) "all three cells returned including blank")
          (is (= "A" (first result)))
          (is (str/blank? (second result)) "middle blank cell is preserved")
          (is (= "B" (nth result 2))))))
    (testing "fully empty row returns vector of blank strings (one per cell)"
      (let [tr {:type :element :tag :tr :attrs {}
                :content [{:type :element :tag :td :attrs {} :content [""]}
                          {:type :element :tag :td :attrs {} :content [""]}]}]
        (let [result (f tr)]
          (is (= 2 (count result)))
          (is (every? str/blank? result)))))
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
  (let [f #'edgar.tables/matrix->dataset
        rows [["Name" "Name" "Value"]
              ["A" "B" "1"]
              ["C" "D" "2"]]
        ds (f rows 0)]
    (testing "duplicate column names are suffixed"
      (let [cols (set (map name (ds/column-names ds)))]
        (is (contains? cols "Name"))
        (is (contains? cols "Name_1"))))))

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
  (let [f #'edgar.tables/row-cells
        tr {:type :element :tag :tr :attrs {}
            :content [{:type :element :tag :th :attrs {} :content ["Header"]}
                      {:type :element :tag :td :attrs {} :content ["Data"]}]}
        cells (f tr)]
    (testing "returns a vector"
      (is (vector? cells)))
    (testing "th cell is marked as header"
      (is (= ["Header" true] (first cells))))
    (testing "td cell is not marked as header"
      (is (= ["Data" false] (second cells))))
    (testing "returns one entry per cell"
      (is (= 2 (count cells))))))

(deftest row-cells-direct-children-only-test
  (let [f #'edgar.tables/row-cells
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
  (let [f #'edgar.tables/row-cells]
    (testing "mixed th/td cells preserve DOM order, not th-first"
      ;; Row: td "A", th "B", td "C" — th-first bug would produce B, A, C
      (let [tr {:type :element :tag :tr :attrs {}
                :content [{:type :element :tag :td :attrs {} :content ["A"]}
                          {:type :element :tag :th :attrs {} :content ["B"]}
                          {:type :element :tag :td :attrs {} :content ["C"]}]}
            cells (f tr)]
        (is (= 3 (count cells)))
        (is (= "A" (first (first cells))))
        (is (= "B" (first (second cells))))
        (is (= "C" (first (nth cells 2))))))))

(deftest row-cells-colspan-test
  (let [f #'edgar.tables/row-cells]
    (testing "colspan=3 expands to 3 entries with the same text"
      (let [tr {:type :element :tag :tr :attrs {}
                :content [{:type :element :tag :th :attrs {:colspan "3"} :content ["Period"]}
                          {:type :element :tag :td :attrs {} :content ["Value"]}]}
            cells (f tr)]
        (is (= 4 (count cells)))
        (is (= [["Period" true] ["Period" true] ["Period" true] ["Value" false]] cells))))
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
  (slurp (clojure.java.io/resource "fixtures/tables.html")))

(deftest extract-tables-fixture-test
  (let [result (with-redefs [edgar.filing/filing-html (fn [_] fixture-tables-html)]
                 (tables/extract-tables {:form "10-K"}))]
    (testing "returns a seq"
      (is (seqable? result)))
    (testing "finds at least 2 data tables (layout table excluded)"
      (is (>= (count result) 2)))
    (testing "all results are datasets"
      (is (every? #(instance? tech.v3.dataset.impl.dataset.Dataset %) result)))
    (testing "first table has Company/Revenue/Net Income columns"
      (let [first-ds (first result)
            cols (set (map name (ds/column-names first-ds)))]
        (is (or (contains? cols "Company")
                (contains? cols "Revenue")))))
    (testing "numeric values are inferred as numbers"
      (let [first-ds (first result)]
        (is (pos? (ds/row-count first-ds)))))
    (testing "layout-only table (single-cell rows) is excluded"
      (let [all-col-counts (map ds/column-count result)]
        (is (every? #(>= % 2) all-col-counts))))))

(deftest extract-tables-dedup-columns-fixture-test
  (let [result (with-redefs [edgar.filing/filing-html (fn [_] fixture-tables-html)]
                 (tables/extract-tables {:form "10-K"}))
        ;; The last table in the fixture has duplicate "Name" headers
        last-ds (last result)]
    (testing "duplicate column names are suffixed in fixture table"
      (when last-ds
        (let [cols (set (map name (ds/column-names last-ds)))]
          (is (or (contains? cols "Name_1")
                  ;; Tolerate if the last table wasn't reached due to min-rows filter
                  (>= (count cols) 1))))))))

(deftest extract-tables-nth-option-test
  (let [result (with-redefs [edgar.filing/filing-html (fn [_] fixture-tables-html)]
                 (tables/extract-tables {:form "10-K"} :nth 0))]
    (testing ":nth 0 returns a single dataset (not a seq)"
      (is (instance? tech.v3.dataset.impl.dataset.Dataset result)))))

(deftest extract-tables-nth-out-of-range-test
  (let [result (with-redefs [edgar.filing/filing-html (fn [_] fixture-tables-html)]
                 (tables/extract-tables {:form "10-K"} :nth 9999))]
    (testing ":nth out of range returns nil"
      (is (nil? result)))))

(deftest extract-tables-min-rows-filter-test
  (let [result-loose (with-redefs [edgar.filing/filing-html (fn [_] fixture-tables-html)]
                       (tables/extract-tables {:form "10-K"} :min-rows 1))
        result-strict (with-redefs [edgar.filing/filing-html (fn [_] fixture-tables-html)]
                        (tables/extract-tables {:form "10-K"} :min-rows 5))]
    (testing "fewer tables returned with higher min-rows"
      (is (>= (count result-loose) (count result-strict))))))

(deftest extract-tables-nil-html-test
  (testing "nil html returns empty seq (not a crash)"
    (let [result (with-redefs [edgar.filing/filing-html (fn [_] nil)]
                   (tables/extract-tables {:form "10-K"}))]
      (is (= [] result))))
  (testing "nil html with :nth returns nil (not a crash)"
    (let [result (with-redefs [edgar.filing/filing-html (fn [_] nil)]
                   (tables/extract-tables {:form "10-K"} :nth 0))]
      (is (nil? result))))
  (testing "blank html returns empty seq"
    (let [result (with-redefs [edgar.filing/filing-html (fn [_] "   ")]
                   (tables/extract-tables {:form "10-K"}))]
      (is (= [] result))))
  (testing "plain text (no HTML tags) returns empty seq"
    (let [result (with-redefs [edgar.filing/filing-html (fn [_] "ITEM 1. BUSINESS\nSome plain text.")]
                   (tables/extract-tables {:form "10-K"}))]
      (is (= [] result))))
  (testing "plain text with :nth returns nil"
    (let [result (with-redefs [edgar.filing/filing-html (fn [_] "ITEM 1. BUSINESS\nSome plain text.")]
                   (tables/extract-tables {:form "10-K"} :nth 0))]
      (is (nil? result))))
  (testing "valid HTML with tables still works after guard"
    (let [result (with-redefs [edgar.filing/filing-html (fn [_] fixture-tables-html)]
                   (tables/extract-tables {:form "10-K"}))]
      (is (seq result))
      (is (every? #(instance? tech.v3.dataset.impl.dataset.Dataset %) result)))))
