(ns edgar.extract-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.extract :as extract]
            [clojure.string :as str]
            [hickory.core :as hickory]))

;;; ---------------------------------------------------------------------------
;;; Item map completeness
;;; ---------------------------------------------------------------------------

(deftest items-10k-test
  (testing "contains standard 10-K items"
    (is (contains? extract/items-10k "1"))
    (is (contains? extract/items-10k "1A"))
    (is (contains? extract/items-10k "7"))
    (is (contains? extract/items-10k "7A"))
    (is (contains? extract/items-10k "8")))
  (testing "all values are non-blank strings"
    (is (every? #(and (string? %) (seq %)) (vals extract/items-10k)))))

(deftest items-10q-test
  (testing "contains standard 10-Q items"
    (is (contains? extract/items-10q "I-1"))
    (is (contains? extract/items-10q "I-2"))
    (is (contains? extract/items-10q "II-1")))
  (testing "all values are non-blank strings"
    (is (every? #(and (string? %) (seq %)) (vals extract/items-10q)))))

(deftest items-8k-test
  (testing "contains standard 8-K items"
    (is (contains? extract/items-8k "2.02"))
    (is (contains? extract/items-8k "5.02"))
    (is (contains? extract/items-8k "9.01")))
  (testing "all values are non-blank strings"
    (is (every? #(and (string? %) (seq %)) (vals extract/items-8k)))))

;;; ---------------------------------------------------------------------------
;;; items-for-form dispatch
;;; ---------------------------------------------------------------------------

(deftest items-for-form-test
  (testing "10-K returns items-10k"
    (is (= extract/items-10k (extract/items-for-form "10-K"))))
  (testing "10-Q returns items-10q"
    (is (= extract/items-10q (extract/items-for-form "10-Q"))))
  (testing "8-K returns items-8k"
    (is (= extract/items-8k (extract/items-for-form "8-K"))))
  (testing "unknown form returns empty map"
    (is (= {} (extract/items-for-form "SC 13G")))
    (is (= {} (extract/items-for-form "4")))))

;;; ---------------------------------------------------------------------------
;;; item-pattern regex
;;; ---------------------------------------------------------------------------

(deftest item-pattern-test
  (testing "matches standard item headings"
    (is (re-find extract/item-pattern "Item 1. Business"))
    (is (re-find extract/item-pattern "ITEM 1A. Risk Factors"))
    (is (re-find extract/item-pattern "Item 7  Management's Discussion"))
    (is (re-find extract/item-pattern "item 2.02 Results of Operations")))
  (testing "does not match non-item text"
    (is (nil? (re-find extract/item-pattern "The following table shows")))
    (is (nil? (re-find extract/item-pattern "Total revenues 1,234")))))

(def ^:private simple-html
  "<html><body>
     <p>Table of Contents</p>
     <p>Item 1. Business</p>
     <p>Some business text here.</p>
     <p>Item 1A. Risk Factors</p>
     <p>Risk discussion here.</p>
     <p>Item 7. Management's Discussion and Analysis</p>
     <p>MD&amp;A text goes here.</p>
   </body></html>")

(deftest find-item-boundaries-test
  (let [f #'edgar.extract/find-item-boundaries
        flat-fn #'edgar.extract/flatten-nodes
        tree (-> simple-html hickory/parse hickory/as-hickory)
        flat (flat-fn tree)
        boundaries (f flat)]
    (testing "returns a non-empty seq for a document with item headings"
      (is (seq boundaries)))
    (testing "each boundary has :item-id :title :node-index"
      (is (every? #(and (contains? % :item-id)
                        (contains? % :title)
                        (contains? % :node-index))
                  boundaries)))
    (testing "boundaries are sorted by :node-index"
      (let [idxs (map :node-index boundaries)]
        (is (= idxs (sort idxs)))))
    (testing "finds item 1A"
      (is (some #(= "1A" (:item-id %)) boundaries)))
    (testing "finds item 7"
      (is (some #(= "7" (:item-id %)) boundaries)))))

(deftest text-from-node-slice-test
  (let [f #'edgar.extract/text-from-node-slice]
    (testing "extracts non-blank strings from a mixed node sequence"
      (is (= "hello world" (f ["hello" "world"]))))
    (testing "blank strings are ignored"
      (is (= "hello" (f ["hello" "   " ""]))))
    (testing "empty seq returns empty string"
      (is (= "" (f []))))))

(deftest extract-items-html-test
  (let [f #'edgar.extract/extract-items-html
        tree (-> simple-html hickory/parse hickory/as-hickory)
        result (f tree #{"1A" "7"})]
    (testing "returns a map"
      (is (map? result)))
    (testing "keys are the requested item ids"
      (is (= #{"1A" "7"} (set (keys result)))))
    (testing "each value has :title :text :method"
      (is (every? #(and (contains? % :title)
                        (contains? % :text)
                        (contains? % :method))
                  (vals result))))
    (testing "method is :html-heading-boundaries"
      (is (every? #(= :html-heading-boundaries (:method %)) (vals result))))
    (testing "item 1A title contains Risk"
      (is (str/includes? (get-in result ["1A" :title]) "Risk")))
    (testing "item 7 text contains MD&A text"
      (is (str/includes? (get-in result ["7" :text]) "MD")))))

(def ^:private plain-text-filing
  "UNITED STATES SECURITIES AND EXCHANGE COMMISSION

ITEM 1. BUSINESS

The company manufactures widgets. It was founded in 1990.

ITEM 1A. RISK FACTORS

The main risks are competition and regulation.

ITEM 7. MANAGEMENT'S DISCUSSION AND ANALYSIS

Revenue increased 10% year over year.
")

(deftest extract-items-text-test
  (let [f #'edgar.extract/extract-items-text
        items-map extract/items-10k
        result (f plain-text-filing items-map)]
    (testing "returns a map"
      (is (map? result)))
    (testing "finds item 1"
      (is (contains? result "1")))
    (testing "item 1 method is :plain-text-regex"
      (is (= :plain-text-regex (get-in result ["1" :method]))))
    (testing "item 1 text contains business content"
      (is (str/includes? (get-in result ["1" :text]) "widgets")))
    (testing "all found items have :title :text :method"
      (is (every? #(and (contains? % :title)
                        (contains? % :text)
                        (contains? % :method))
                  (vals result))))))
