(ns edgar.company-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [edgar.core :as core]
            [edgar.company :as company]))

;; several tests load stubbed ticker data into the ticker caches — never let
;; it leak into other test namespaces
(use-fixtures :each (fn [f] (try (f) (finally (company/clear-ticker-cache!)))))

;;; ---------------------------------------------------------------------------
;;; ticker->cik / cik->ticker — pure normalization logic
;;; ---------------------------------------------------------------------------

(def ^:private tickers-fixture
  ;; company_tickers.json shape after jsonista keyword-keys parsing: rank
  ;; keys :0 :1 ..., delivered in hash-map order, so the lower-ranked share
  ;; class may well come first in (vals ...)
  {:7479 {:cik_str 1067983 :ticker "BRK-A" :title "BERKSHIRE HATHAWAY INC"}
   :0 {:cik_str 320193 :ticker "AAPL" :title "Apple Inc."}
   :4 {:cik_str 1652044 :ticker "GOOG" :title "Alphabet Inc."}
   :2 {:cik_str 1652044 :ticker "GOOGL" :title "Alphabet Inc."}
   :8 {:cik_str 1067983 :ticker "BRK-B" :title "BERKSHIRE HATHAWAY INC"}})

(defn- with-tickers-fixture [f]
  (with-redefs [core/edgar-get (fn [& _] tickers-fixture)]
    (company/clear-ticker-cache!)
    (try (f) (finally (company/clear-ticker-cache!)))))

(deftest cik->ticker-primary-share-class-test
  (with-tickers-fixture
    (fn []
      (testing "multi-class CIK resolves to the lowest-ranked ticker"
        (is (= "GOOGL" (company/cik->ticker 1652044)))
        (is (= "GOOGL" (company/cik->ticker "0001652044")))
        (is (= "BRK-B" (company/cik->ticker "1067983"))))
      (testing "single-class CIK"
        (is (= "AAPL" (company/cik->ticker 320193)))))))

(deftest ticker->cik-normalization-test
  (with-tickers-fixture
    (fn []
      (testing "share-class dot is accepted for the SEC's dash form"
        (is (= "0001067983" (company/ticker->cik "BRK.B")))
        (is (= "0001067983" (company/ticker->cik "brk.a")))
        (is (= "0001067983" (company/ticker->cik "BRK-B"))))
      (testing "case and surrounding whitespace are ignored"
        (is (= "0001652044" (company/ticker->cik " googl "))))
      (testing "unknown ticker returns nil without throwing"
        (is (nil? (company/ticker->cik "ZZZNOTTICKER")))))))

(deftest integer-cik-test
  (testing "integer CIKs resolve without a ticker lookup"
    (with-redefs [company/ticker->cik (fn [_] (throw (AssertionError. "no ticker lookup expected")))]
      (is (= "0000320193" (company/company-cik 320193)))
      (is (= "0000000042" (company/company-cik 42)))))
  (testing "get-company fetches the submissions URL for an integer CIK"
    (let [url (atom nil)]
      (with-redefs [core/edgar-get (fn [u & _] (reset! url u) {:cik "320193"})]
        (company/get-company 320193)
        (is (= "https://data.sec.gov/submissions/CIK0000320193.json" @url))))))

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

(defn- entity-hit
  "One hit as returned live by efts.sec.gov/LATEST/search-index?keysTyped=
   (captured 2026-10-15): _id is the unpadded CIK."
  ([cik entity] (entity-hit cik entity nil))
  ([cik entity tickers]
   {:_index "edgar_entity_20261015_040139"
    :_id cik
    :_score 21.6
    :_source (cond-> {:entity entity :entity_words entity :rank 181}
               tickers (assoc :tickers tickers))}))

(def ^:private apple-response
  {:took 68
   :timed_out false
   :hits {:total {:value 230 :relation "eq"}
          :max_score 30.12
          :hits [(entity-hit "320193" "Apple Inc. (AAPL)" "AAPL")
                 (entity-hit "1134982" "Apple iSports Group, Inc. (AAPI)" "AAPI")
                 (entity-hit "1418121" "Apple Hospitality REIT, Inc. (APLE)" "APLE")
                 (entity-hit "1215379" "APPLE JIM B")]}})

(deftest search-companies-shape-test
  (testing "queries the entity index (keysTyped), not full-text search"
    (let [url (atom nil)]
      (with-redefs [core/edgar-get (fn [u & _] (reset! url u) apple-response)]
        (company/search-companies "berkshire hathaway")
        (is (= "https://efts.sec.gov/LATEST/search-index?keysTyped=berkshire%20hathaway" @url)))))
  (testing "hits become {:cik :name :tickers} with padded CIK and ticker suffix removed"
    (with-redefs [core/edgar-get (fn [& _] apple-response)]
      (is (= [{:cik "0000320193" :name "Apple Inc." :tickers ["AAPL"]}
              {:cik "0001134982" :name "Apple iSports Group, Inc." :tickers ["AAPI"]}
              {:cik "0001418121" :name "Apple Hospitality REIT, Inc." :tickers ["APLE"]}
              {:cik "0001215379" :name "APPLE JIM B" :tickers []}]
             (company/search-companies "Apple")))))
  (testing "multi-class tickers are split; parenthesised names without tickers stay intact"
    (with-redefs [core/edgar-get
                  (fn [& _]
                    {:hits {:hits [(entity-hit "1652044" "Alphabet Inc. (GOOG, GOOGN, GOOGM, GOOGL)"
                                               "GOOG, GOOGN, GOOGM, GOOGL")
                                   (entity-hit "42" "ACME (HOLDINGS) INC")]}})]
      (is (= [{:cik "0001652044" :name "Alphabet Inc." :tickers ["GOOG" "GOOGN" "GOOGM" "GOOGL"]}
              {:cik "0000000042" :name "ACME (HOLDINGS) INC" :tickers []}]
             (company/search-companies "a")))))
  (testing ":limit is respected"
    (with-redefs [core/edgar-get (fn [& _] apple-response)]
      (is (= ["0000320193" "0001134982"] (map :cik (company/search-companies "Apple" :limit 2))))))
  (testing "duplicate CIKs and malformed hits are dropped; no hits → empty vector"
    (with-redefs [core/edgar-get
                  (fn [& _]
                    {:hits {:hits [(entity-hit "320193" "Apple Inc. (AAPL)" "AAPL")
                                   (entity-hit "320193" "Apple Inc. (AAPL)" "AAPL")
                                   {:_id nil :_source {:entity "X"}}
                                   {:_id "5" :_source {}}]}})]
      (is (= ["0000320193"] (map :cik (company/search-companies "Apple")))))
    (with-redefs [core/edgar-get (fn [& _] {:hits {:hits [] :total {:value 0 :relation "eq"}}})]
      (is (= [] (company/search-companies "zzzqqqxx"))))))

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
