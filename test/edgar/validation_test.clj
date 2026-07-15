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
