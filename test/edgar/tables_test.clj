(ns edgar.tables-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.tables :as tables]
            [tech.v3.dataset :as ds]))

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
      (is (nil? (f "—"))))))

;;; ---------------------------------------------------------------------------
;;; infer-column
;;; ---------------------------------------------------------------------------

(deftest infer-column-test
  (let [f #'edgar.tables/infer-column]
    (testing "all-integer cells → Long vector"
      (let [result (f ["1" "2" "3"])]
        (is (every? #(instance? Long %) result))))
    (testing "mixed integer and decimal → Double vector"
      (let [result (f ["1" "2.5" "3"])]
        (is (every? #(instance? Double %) result))))
    (testing "non-numeric cells → String vector (passthrough)"
      (let [result (f ["foo" "bar" "baz"])]
        (is (= ["foo" "bar" "baz"] result))))
    (testing "mixed numeric and non-numeric → String vector"
      (let [result (f ["100" "n/a" "200"])]
        (is (= ["100" "n/a" "200"] result))))))

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
      (is (f [])))))

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
