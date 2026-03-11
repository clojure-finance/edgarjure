(ns edgar.company-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.company :as company]))

;;; ---------------------------------------------------------------------------
;;; ticker->cik / cik->ticker — pure normalization logic
;;; ---------------------------------------------------------------------------

(deftest ticker->cik-returns-nil-for-unknown
  (testing "unknown ticker returns nil without throwing"
    (is (nil? (company/ticker->cik "ZZZNOTTICKER")))))

(deftest company-cik-numeric-string
  (testing "numeric string input is zero-padded to 10 digits"
    (is (= "0000320193" (company/company-cik "320193"))))
  (testing "already 10-digit numeric input is unchanged"
    (is (= "0000320193" (company/company-cik "0000320193"))))
  (testing "integer-like string with leading zeros is preserved"
    (is (= "0000000001" (company/company-cik "1")))))

(deftest company-cik-format
  (testing "result is always exactly 10 characters"
    (is (= 10 (count (company/company-cik "320193"))))))

;;; ---------------------------------------------------------------------------
;;; shape-address via company-metadata (testing the shaping logic indirectly)
;;; We can test the metadata map keys without a live HTTP call by mocking the
;;; underlying get-company response.  Since that requires HTTP, we only test
;;; the structural contract of company-cik (pure) here and keep HTTP-dependent
;;; tests in a separate :test-integration alias.
;;; ---------------------------------------------------------------------------

(deftest company-cik-pure-branch
  (testing "pure numeric-string branch always zero-pads"
    (is (= "0000001234" (company/company-cik "1234")))
    (is (= "0000000042" (company/company-cik "42")))))
