(ns edgar.extract-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.extract :as extract]))

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
