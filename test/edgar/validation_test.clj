(ns edgar.validation-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.validation :as validation]
            [edgar.financials :as financials]
            [tech.v3.dataset :as ds]))

(def ^:private stub-statement
  (ds/->dataset
   [{:line-item "Revenue" :end "2023-09-30" :val 383285000000 :unit "USD"}
    {:line-item "Net Income" :end "2023-09-30" :val 96995000000 :unit "USD"}
    {:line-item "Revenue" :end "2022-09-30" :val 394328000000 :unit "USD"}]))

(deftest compare-to-benchmark-test
  (with-redefs [financials/income-statement (fn [& _] stub-statement)]
    (testing "exact matches count toward match-rate"
      (let [result (validation/compare-to-benchmark
                    "AAPL"
                    [{:line-item "Revenue" :end "2023-09-30" :val 383285000000}
                     {:line-item "Net Income" :end "2023-09-30" :val 96995000000}])]
        (is (= 1.0 (:match-rate result)))
        (is (= 2 (count (:matched result))))
        (is (empty? (:mismatched result)))
        (is (empty? (:missing result)))))
    (testing "values within relative tolerance match"
      (let [result (validation/compare-to-benchmark
                    "AAPL"
                    [{:line-item "Revenue" :end "2023-09-30" :val 383000000000}]
                    :tolerance 0.01)]
        (is (= 1.0 (:match-rate result)))))
    (testing "values outside tolerance are mismatched with :rel-diff"
      (let [result (validation/compare-to-benchmark
                    "AAPL"
                    [{:line-item "Revenue" :end "2023-09-30" :val 300000000000}])]
        (is (= 0.0 (:match-rate result)))
        (is (= 1 (count (:mismatched result))))
        (is (number? (:rel-diff (first (:mismatched result)))))))
    (testing "line items edgarjure has no value for are :missing"
      (let [result (validation/compare-to-benchmark
                    "AAPL"
                    [{:line-item "Nonexistent" :end "2023-09-30" :val 1}])]
        (is (= 0.0 (:match-rate result)))
        (is (= 1 (count (:missing result))))))
    (testing "benchmark can be a dataset"
      (let [result (validation/compare-to-benchmark
                    "AAPL"
                    (ds/->dataset [{:line-item "Revenue" :end "2023-09-30"
                                    :val 383285000000}]))]
        (is (= 1.0 (:match-rate result)))))
    (testing "empty benchmark yields nil match-rate"
      (let [result (validation/compare-to-benchmark "AAPL" [])]
        (is (nil? (:match-rate result)))))))

(deftest compare-to-benchmark-date-tolerance-test
  ;; Compustat normalises datadate to calendar month-end; XBRL carries the
  ;; exact 52/53-week fiscal date (2015-09-30 vs Apple's 2015-09-26).
  (let [stmt (ds/->dataset
              [{:line-item "Revenue" :end "2015-09-26" :val 233715000000 :unit "USD"}
               {:line-item "Revenue" :end "2014-09-27" :val 182795000000 :unit "USD"}])]
    (with-redefs [financials/income-statement (fn [& _] stmt)]
      (testing "default (exact) matching misses month-end benchmark dates"
        (let [r (validation/compare-to-benchmark
                 "AAPL" [{:line-item "Revenue" :end "2015-09-30" :val 233715000000}])]
          (is (= 1 (count (:missing r))))))
      (testing ":date-tolerance-days matches the nearby fiscal date"
        (let [r (validation/compare-to-benchmark
                 "AAPL" [{:line-item "Revenue" :end "2015-09-30" :val 233715000000}]
                 :date-tolerance-days 10)]
          (is (= 1.0 (:match-rate r)))))
      (testing "closest date wins within tolerance"
        (let [r (validation/compare-to-benchmark
                 "AAPL" [{:line-item "Revenue" :end "2014-09-30" :val 182795000000}]
                 :date-tolerance-days 400)]
          (is (= 1.0 (:match-rate r))
              "must pick 2014-09-27, not 2015-09-26, despite huge tolerance")))
      (testing "exact match always wins over a closer-by-tolerance candidate"
        (let [r (validation/compare-to-benchmark
                 "AAPL" [{:line-item "Revenue" :end "2014-09-27" :val 182795000000}]
                 :date-tolerance-days 400)]
          (is (= 1.0 (:match-rate r))))))))

(deftest compare-to-benchmark-duration-preference-test
  ;; 10-K facts include quarterly-footnote observations: a Q4 3-month row
  ;; shares its :end date with the fiscal-year row. Annual benchmarks must
  ;; match the 12-month row, both on exact-date and tolerance matching.
  (let [stmt (ds/->dataset
              [{:line-item "Revenue" :start "2015-06-28" :end "2015-09-26"
                :val 51501000000 :unit "USD"}                       ; Q4 footnote row
               {:line-item "Revenue" :start "2014-09-28" :end "2015-09-26"
                :val 233715000000 :unit "USD"}])]                   ; FY row
    (with-redefs [financials/income-statement (fn [& _] stmt)]
      (testing "exact-date match prefers the longest duration window"
        (let [r (validation/compare-to-benchmark
                 "AAPL" [{:line-item "Revenue" :end "2015-09-26" :val 233715000000}])]
          (is (= 1.0 (:match-rate r)))))
      (testing "tolerance match also prefers the longest duration window"
        (let [r (validation/compare-to-benchmark
                 "AAPL" [{:line-item "Revenue" :end "2015-09-30" :val 233715000000}]
                 :date-tolerance-days 10)]
          (is (= 1.0 (:match-rate r))))))))

(deftest compare-to-benchmark-value-key-test
  (let [stmt (ds/->dataset
              [{:line-item "Revenue" :end "2024-03-31" :val 210 :val-q 110 :unit "USD"}
               {:line-item "Revenue" :end "2023-12-31" :val 100 :val-q 100 :unit "USD"}])]
    (with-redefs [financials/income-statement (fn [& _] stmt)]
      (testing ":value-key :val-q validates single-quarter values"
        (let [r (validation/compare-to-benchmark
                 "AAPL" [{:line-item "Revenue" :end "2024-03-31" :val 110}]
                 :value-key :val-q)]
          (is (= 1.0 (:match-rate r)))))
      (testing "default :value-key :val compares the YTD/reported value"
        (let [r (validation/compare-to-benchmark
                 "AAPL" [{:line-item "Revenue" :end "2024-03-31" :val 110}])]
          (is (= 1 (count (:mismatched r)))))))))

(deftest compare-to-benchmark-statement-dispatch-test
  (let [called (atom nil)]
    (with-redefs [financials/income-statement (fn [& _] (reset! called :income) stub-statement)
                  financials/balance-sheet (fn [& _] (reset! called :balance) stub-statement)
                  financials/cash-flow (fn [& _] (reset! called :cash-flow) stub-statement)]
      (testing "dispatches to the requested statement function"
        (validation/compare-to-benchmark "AAPL" [] :statement :balance)
        (is (= :balance @called))
        (validation/compare-to-benchmark "AAPL" [] :statement :cash-flow)
        (is (= :cash-flow @called))
        (validation/compare-to-benchmark "AAPL" [])
        (is (= :income @called))))))
