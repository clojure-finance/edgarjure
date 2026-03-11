(ns edgar.dataset-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.dataset :as dataset]
            [tech.v3.dataset :as ds]))

;;; ---------------------------------------------------------------------------
;;; multi-company-facts — guard against empty / failing tickers
;;; These tests stub out the HTTP layer by patching company/company-cik
;;; and xbrl/get-facts-dataset via with-redefs.
;;; ---------------------------------------------------------------------------

(deftest multi-company-facts-empty-tickers
  (testing "returns empty dataset for empty tickers vector"
    (let [result (dataset/multi-company-facts [])]
      (is (= 0 (ds/row-count result))))))

(deftest multi-company-facts-all-tickers-fail
  (testing "returns empty dataset when every ticker throws"
    (with-redefs [edgar.company/company-cik (fn [_] (throw (ex-info "not found" {})))]
      (let [result (dataset/multi-company-facts ["BADINPUT" "ALSOBAD"])]
        (is (= 0 (ds/row-count result)))))))

(deftest multi-company-facts-partial-failure
  (testing "returns rows only for tickers that succeed; failed tickers are skipped"
    (with-redefs [edgar.company/company-cik (fn [t] t)
                  edgar.xbrl/get-facts-dataset
                  (fn [cik & _]
                    (if (= cik "GOOD")
                      (ds/->dataset [{:taxonomy "us-gaap" :concept "Assets"
                                      :unit "USD" :end "2023-12-31"
                                      :val 1000 :accn "0001-23-000001"
                                      :fy 2023 :fp "FY" :form "10-K"
                                      :filed "2024-01-15" :frame "CY2023Q4I"
                                      :label "Assets" :description "Total assets"}])
                      (throw (ex-info "fetch failed" {}))))]
      (let [result (dataset/multi-company-facts ["GOOD" "BAD"])]
        (is (= 1 (ds/row-count result)))
        (is (= ["GOOD"] (vec (get result :ticker))))))))
