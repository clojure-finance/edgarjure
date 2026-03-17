(ns edgar.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; validate! helper
;;; ---------------------------------------------------------------------------

(deftest validate!-passes-on-valid-args
  (testing "returns nil for valid args"
    (is (nil? (schema/validate! schema/FilingArgs
                                {:ticker-or-cik "AAPL" :form "10-K" :n 0 :include-amends? false})))))

(deftest validate!-throws-on-invalid-args
  (testing "throws ex-info with ::invalid-args type"
    (let [ex (try (schema/validate! schema/FilingArgs {:ticker-or-cik "" :form "10-K"})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= ::schema/invalid-args (:type (ex-data ex))))))
  (testing ":args key contains the original map"
    (let [args {:ticker-or-cik "" :form "10-K"}
          ex (try (schema/validate! schema/FilingArgs args) nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= args (:args (ex-data ex))))))
  (testing ":errors key is present"
    (let [ex (try (schema/validate! schema/FilingArgs {:ticker-or-cik "" :form "10-K"}) nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? (:errors (ex-data ex)))))))

;;; ---------------------------------------------------------------------------
;;; FilingArgs — optional fields and required fields
;;; ---------------------------------------------------------------------------

(deftest CompanyArgs-test
  (testing "valid with a ticker string"
    (is (nil? (schema/validate! schema/CompanyArgs {:ticker-or-cik "AAPL"}))))
  (testing "valid with a numeric CIK string"
    (is (nil? (schema/validate! schema/CompanyArgs {:ticker-or-cik "0000320193"}))))
  (testing "throws when :ticker-or-cik is blank"
    (is (thrown? clojure.lang.ExceptionInfo
                 (schema/validate! schema/CompanyArgs {:ticker-or-cik ""}))))
  (testing "throws when :ticker-or-cik is missing"
    (is (thrown? clojure.lang.ExceptionInfo
                 (schema/validate! schema/CompanyArgs {}))))
  (testing "throws when :ticker-or-cik is nil"
    (is (thrown? clojure.lang.ExceptionInfo
                 (schema/validate! schema/CompanyArgs {:ticker-or-cik nil}))))
  (testing "throws when :ticker-or-cik is not a string"
    (is (thrown? clojure.lang.ExceptionInfo
                 (schema/validate! schema/CompanyArgs {:ticker-or-cik 320193})))))

(deftest FilingArgs-required-fields
  (testing "valid with all fields"
    (is (nil? (schema/validate! schema/FilingArgs
                                {:ticker-or-cik "AAPL" :form "10-K" :n 0 :include-amends? false}))))
  (testing "valid with only required fields (n and include-amends? are optional)"
    (is (nil? (schema/validate! schema/FilingArgs
                                {:ticker-or-cik "AAPL" :form "10-K"}))))
  (testing "throws when :ticker-or-cik is missing"
    (is (thrown? clojure.lang.ExceptionInfo
                 (schema/validate! schema/FilingArgs {:form "10-K"}))))
  (testing "throws when :form is missing"
    (is (thrown? clojure.lang.ExceptionInfo
                 (schema/validate! schema/FilingArgs {:ticker-or-cik "AAPL"}))))
  (testing "throws when :ticker-or-cik is blank"
    (is (thrown? clojure.lang.ExceptionInfo
                 (schema/validate! schema/FilingArgs {:ticker-or-cik "" :form "10-K"}))))
  (testing "throws when :n is negative"
    (is (thrown? clojure.lang.ExceptionInfo
                 (schema/validate! schema/FilingArgs {:ticker-or-cik "AAPL" :form "10-K" :n -1})))))

;;; ---------------------------------------------------------------------------
;;; FilingsArgs — consistency check (plural, has optional :form)
;;; ---------------------------------------------------------------------------

(deftest FilingsArgs-optional-form
  (testing "valid without :form"
    (is (nil? (schema/validate! schema/FilingsArgs {:ticker-or-cik "AAPL"}))))
  (testing "valid with :form"
    (is (nil? (schema/validate! schema/FilingsArgs {:ticker-or-cik "AAPL" :form "10-K"}))))
  (testing "valid with :form nil (maybe)"
    (is (nil? (schema/validate! schema/FilingsArgs {:ticker-or-cik "AAPL" :form nil})))))

;;; ---------------------------------------------------------------------------
;;; StatementArgs
;;; ---------------------------------------------------------------------------

(deftest StatementArgs-test
  (testing "valid with required fields"
    (is (nil? (schema/validate! schema/StatementArgs
                                {:ticker-or-cik "AAPL" :form "10-K" :shape :long}))))
  (testing "valid with :as-of"
    (is (nil? (schema/validate! schema/StatementArgs
                                {:ticker-or-cik "AAPL" :form "10-K" :shape :wide :as-of "2022-01-01"}))))
  (testing "throws on bad :shape"
    (is (thrown? clojure.lang.ExceptionInfo
                 (schema/validate! schema/StatementArgs
                                   {:ticker-or-cik "AAPL" :form "10-K" :shape :tall}))))
  (testing "throws on bad :as-of format"
    (is (thrown? clojure.lang.ExceptionInfo
                 (schema/validate! schema/StatementArgs
                                   {:ticker-or-cik "AAPL" :form "10-K" :shape :long :as-of "22-01-01"})))))

;;; ---------------------------------------------------------------------------
;;; AccessionNumber primitive
;;; ---------------------------------------------------------------------------

(deftest AccessionNumber-test
  (testing "valid dashed accession number"
    (is (nil? (schema/validate! schema/FilingByAccessionArgs
                                {:accession-number "0000320193-23-000106"}))))
  (testing "throws on undashed format"
    (is (thrown? clojure.lang.ExceptionInfo
                 (schema/validate! schema/FilingByAccessionArgs
                                   {:accession-number "000032019323000106"}))))
  (testing "throws on empty string"
    (is (thrown? clojure.lang.ExceptionInfo
                 (schema/validate! schema/FilingByAccessionArgs
                                   {:accession-number ""})))))
