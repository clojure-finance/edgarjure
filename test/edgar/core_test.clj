(ns edgar.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.core :as core]))

(deftest cik-url-test
  (testing "zero-pads CIK to 10 digits"
    (is (= "https://data.sec.gov/submissions/CIK0000320193.json"
           (core/cik-url "320193"))))
  (testing "already padded CIK"
    (is (= "https://data.sec.gov/submissions/CIK0000320193.json"
           (core/cik-url "0000320193")))))

(deftest facts-endpoint-test
  (testing "builds correct XBRL facts URL"
    (is (= "https://data.sec.gov/api/xbrl/companyfacts/CIK0000320193.json"
           (core/facts-endpoint "320193")))))

(deftest archives-path-test
  (testing "strips dashes from accession number"
    (is (= "https://www.sec.gov/Archives/edgar/data/320193/000032019323000064"
           (core/archives-path "320193" "0000320193-23-000064"))))
  (testing "already undashed accession number is unchanged"
    (is (= "https://www.sec.gov/Archives/edgar/data/320193/000032019323000064"
           (core/archives-path "320193" "000032019323000064")))))

(deftest set-identity-test
  (testing "set-identity! updates *identity*"
    (core/set-identity! "Test User test@example.com")
    (is (= "Test User test@example.com" core/*identity*))))

(deftest base-urls-test
  (testing "base URLs are defined and non-empty"
    (is (string? core/base-url))
    (is (string? core/data-url))
    (is (string? core/archives-url))
    (is (string? core/submissions-url))
    (is (string? core/facts-url))
    (is (string? core/tickers-url))
    (is (clojure.string/starts-with? core/base-url "https://"))
    (is (clojure.string/starts-with? core/data-url "https://"))))
