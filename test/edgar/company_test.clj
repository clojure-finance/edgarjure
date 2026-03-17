(ns edgar.company-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [edgar.core :as core]
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

(deftest company-cik-unknown-ticker-test
  (testing "unknown ticker throws ex-info with ::unknown-ticker type"
    (with-redefs [edgar.company/tickers-by-ticker
                  (fn [] {"AAPL" {:cik_str "320193" :ticker "AAPL"}})]
      (let [ex (try
                 (company/company-cik "ZZZNOTTICKER")
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) "should throw for unknown ticker")
        (is (= ::company/unknown-ticker (:type (ex-data ex))))
        (is (= "ZZZNOTTICKER" (:ticker (ex-data ex))))
        (is (str/includes? (ex-message ex) "ZZZNOTTICKER")))))
  (testing "numeric input is never treated as a ticker lookup — no throw"
    (is (= "0000001234" (company/company-cik "1234")))))

(deftest ticker->cik-format-test
  (testing "ticker->cik zero-pads using Long parse, not raw string format"
    (with-redefs [edgar.company/tickers-by-ticker
                  (fn [] {"AAPL" {:cik_str "320193" :ticker "AAPL"}})]
      (is (= "0000320193" (company/ticker->cik "AAPL")))
      (is (= 10 (count (company/ticker->cik "AAPL"))))))
  (testing "ticker->cik handles cik_str values that arrive as integers"
    (with-redefs [edgar.company/tickers-by-ticker
                  (fn [] {"AAPL" {:cik_str 320193 :ticker "AAPL"}})]
      (is (= "0000320193" (company/ticker->cik "AAPL")))))
  (testing "ticker->cik returns nil for unknown ticker"
    (with-redefs [edgar.company/tickers-by-ticker
                  (fn [] {"AAPL" {:cik_str "320193" :ticker "AAPL"}})]
      (is (nil? (company/ticker->cik "ZZZNOTTICKER"))))))

(deftest cik->ticker-test
  (testing "cik->ticker uses Long comparison, not Double (avoids precision loss)"
    (with-redefs [edgar.company/load-tickers!
                  (fn [] {0 {:cik_str "320193" :ticker "AAPL"}})]
      (is (= "AAPL" (company/cik->ticker "320193")))
      (is (= "AAPL" (company/cik->ticker "0000320193")))))
  (testing "cik->ticker returns nil for unknown CIK"
    (with-redefs [edgar.company/load-tickers!
                  (fn [] {0 {:cik_str "320193" :ticker "AAPL"}})]
      (is (nil? (company/cik->ticker "9999999999"))))))

(deftest shape-address-nil-street2-test
  (let [f #'edgar.company/shape-address]
    (testing "nil street2 produces nil, not \"nil\""
      (let [addr {:street1 "ONE APPLE PARK WAY"
                  :street2 nil
                  :city "CUPERTINO"
                  :stateOrCountry "CA"
                  :stateOrCountryDescription "CA"
                  :zipCode "95014"
                  :isForeignLocation 0}
            result (f addr)]
        (is (nil? (:street2 result)))
        (is (not= "nil" (:street2 result)))))
    (testing "non-nil street2 is preserved"
      (let [addr {:street1 "200 WEST ST"
                  :street2 "SUITE 100"
                  :city "NEW YORK"
                  :stateOrCountry "NY"
                  :stateOrCountryDescription "NY"
                  :zipCode "10282"
                  :isForeignLocation 0}
            result (f addr)]
        (is (= "SUITE 100" (:street2 result)))))
    (testing "blank string street2 produces nil via not-empty"
      (let [addr {:street1 "100 MAIN ST"
                  :street2 ""
                  :city "ANYTOWN"
                  :stateOrCountry "TX"
                  :stateOrCountryDescription "TX"
                  :zipCode "75001"
                  :isForeignLocation 0}
            result (f addr)]
        (is (nil? (:street2 result)))))
    (testing "nil addr returns nil"
      (is (nil? (f nil))))))

(deftest search-companies-shape-test
  (testing "returns shaped maps with :entity-name :cik :location :inc-states"
    (with-redefs [edgar.core/edgar-get
                  (fn [_]
                    {:hits {:hits
                            [{:_source {:display_names ["Apple Inc.  (AAPL)  (CIK 0000320193)"]
                                        :ciks ["0000320193"]
                                        :biz_locations ["Cupertino, CA"]
                                        :inc_states ["CA"]}}
                             {:_source {:display_names ["Apple Hospitality REIT, Inc.  (APLE)  (CIK 0001418121)"]
                                        :ciks ["0001418121"]
                                        :biz_locations ["Richmond, VA"]
                                        :inc_states ["VA"]}}]}})]
      (let [results (company/search-companies "apple" :limit 10)]
        (is (= 2 (count results)))
        (is (every? #(contains? % :entity-name) results))
        (is (every? #(contains? % :cik) results))
        (is (every? #(contains? % :location) results))
        (is (every? #(contains? % :inc-states) results)))))
  (testing "entity-name strips CIK and ticker suffixes"
    (with-redefs [edgar.core/edgar-get
                  (fn [_]
                    {:hits {:hits
                            [{:_source {:display_names ["Apple Inc.  (AAPL)  (CIK 0000320193)"]
                                        :ciks ["0000320193"]
                                        :biz_locations ["Cupertino, CA"]
                                        :inc_states ["CA"]}}
                             {:_source {:display_names ["CITIGROUP INC  (C, C-PN)  (CIK 0000831001)"]
                                        :ciks ["0000831001"]
                                        :biz_locations ["New York, NY"]
                                        :inc_states ["DE"]}}
                             {:_source {:display_names ["MULTI FINELINE ELECTRONIX INC  (CIK 0000830916)"]
                                        :ciks ["0000830916"]
                                        :biz_locations ["Anaheim, CA"]
                                        :inc_states []}}]}})]
      (let [results (company/search-companies "test" :limit 10)
            by-cik (into {} (map (juxt :cik identity) results))]
        (is (= "Apple Inc." (get-in by-cik ["0000320193" :entity-name])))
        (is (= "CITIGROUP INC" (get-in by-cik ["0000831001" :entity-name])))
        (is (= "MULTI FINELINE ELECTRONIX INC" (get-in by-cik ["0000830916" :entity-name]))))))
  (testing "deduplicates hits by CIK"
    (with-redefs [edgar.core/edgar-get
                  (fn [_]
                    {:hits {:hits
                            [{:_source {:display_names ["Apple Inc.  (AAPL)  (CIK 0000320193)"]
                                        :ciks ["0000320193"]
                                        :biz_locations ["Cupertino, CA"]
                                        :inc_states ["CA"]}}
                             {:_source {:display_names ["Apple Inc.  (AAPL)  (CIK 0000320193)"]
                                        :ciks ["0000320193"]
                                        :biz_locations ["Cupertino, CA"]
                                        :inc_states ["CA"]}}]}})]
      (let [results (company/search-companies "apple" :limit 10)]
        (is (= 1 (count results)) "duplicate CIKs must be deduplicated"))))
  (testing ":limit is respected"
    (with-redefs [edgar.core/edgar-get
                  (fn [_]
                    {:hits {:hits
                            (vec (for [i (range 10)]
                                   {:_source {:display_names [(str "Corp " i "  (CIK 000000000" i ")")]
                                              :ciks [(str "000000000" i)]
                                              :biz_locations ["New York, NY"]
                                              :inc_states ["NY"]}}))}})]
      (let [results (company/search-companies "corp" :limit 3)]
        (is (= 3 (count results))))))
  (testing "hits with missing cik or display_names are skipped"
    (with-redefs [edgar.core/edgar-get
                  (fn [_]
                    {:hits {:hits
                            [{:_source {:display_names nil :ciks [] :biz_locations [] :inc_states []}}
                             {:_source {:display_names ["Apple Inc.  (AAPL)  (CIK 0000320193)"]
                                        :ciks ["0000320193"]
                                        :biz_locations ["Cupertino, CA"]
                                        :inc_states ["CA"]}}]}})]
      (let [results (company/search-companies "apple" :limit 10)]
        (is (= 1 (count results)))))))

;;; ---------------------------------------------------------------------------
;;; tickers-by-ticker-cache — populated once alongside tickers-cache
;;; ---------------------------------------------------------------------------

(deftest tickers-by-ticker-cache-test
  (testing "tickers-by-ticker-cache is populated on first load-tickers! call"
    (let [call-count (atom 0)
          fake-data {0 {:cik_str "320193" :ticker "AAPL"}
                     1 {:cik_str "789019" :ticker "MSFT"}}]
      (with-redefs [edgar.core/edgar-get
                    (fn [_]
                      (swap! call-count inc)
                      fake-data)]
        ;; Reset both caches so load-tickers! will fetch
        (reset! (var-get #'edgar.company/tickers-cache) nil)
        (reset! (var-get #'edgar.company/tickers-by-ticker-cache) nil)
        ;; First call populates both caches
        (#'edgar.company/load-tickers!)
        (is (= 1 @call-count) "edgar-get called exactly once")
        (let [cache @(var-get #'edgar.company/tickers-by-ticker-cache)]
          (is (map? cache))
          (is (contains? cache "AAPL"))
          (is (contains? cache "MSFT")))
        ;; Second call must not re-fetch
        (#'edgar.company/load-tickers!)
        (is (= 1 @call-count) "edgar-get not called again on second load-tickers!"))))
  (testing "tickers-by-ticker returns the cached map on repeated calls"
    (let [call-count (atom 0)
          fake-data {0 {:cik_str "320193" :ticker "AAPL"}}]
      (with-redefs [edgar.core/edgar-get
                    (fn [_]
                      (swap! call-count inc)
                      fake-data)]
        (reset! (var-get #'edgar.company/tickers-cache) nil)
        (reset! (var-get #'edgar.company/tickers-by-ticker-cache) nil)
        (let [m1 (#'edgar.company/tickers-by-ticker)
              m2 (#'edgar.company/tickers-by-ticker)]
          (is (identical? m1 m2) "same map object returned on repeated calls")
          (is (= 1 @call-count) "edgar-get called only once across both calls"))))))
