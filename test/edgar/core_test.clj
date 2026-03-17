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

;;; ---------------------------------------------------------------------------
;;; Retry / backoff behaviour (no real HTTP — stub via with-redefs)
;;; retry-base-ms is set to 0 to skip actual sleep delays in tests.
;;; clear-cache! before each test to prevent the TTL cache from short-circuiting
;;; the stubbed hato.client/get.
;;; ---------------------------------------------------------------------------

(deftest retry-on-429-test
  (testing "retries on 429 and succeeds on third attempt"
    (core/clear-cache!)
    (let [call-count (atom 0)]
      (with-redefs [hato.client/get (fn [_url opts]
                                      (swap! call-count inc)
                                      (is (false? (:throw-exceptions? opts))
                                          ":throw-exceptions? must be false")
                                      (if (< @call-count 3)
                                        {:status 429 :body ""}
                                        {:status 200 :body "\"ok\""}))
                    edgar.core/throttle! (fn [] nil)
                    edgar.core/retry-base-ms 0]
        (core/set-identity! "Test test@example.com")
        (let [result (core/edgar-get "https://example.com/retry-429")]
          (is (= "ok" result))
          (is (= 3 @call-count)))))))

(deftest throws-on-non-retryable-4xx-test
  (testing "throws immediately on 404 without retry"
    (core/clear-cache!)
    (let [call-count (atom 0)]
      (with-redefs [hato.client/get (fn [_url _opts]
                                      (swap! call-count inc)
                                      {:status 404 :body ""})
                    edgar.core/throttle! (fn [] nil)
                    edgar.core/retry-base-ms 0]
        (core/set-identity! "Test test@example.com")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"HTTP 404"
                              (core/edgar-get "https://example.com/missing-404")))
        (is (= 1 @call-count))))))

(deftest retry-on-transport-error-test
  (testing "retries on transport exceptions and succeeds on third attempt"
    (core/clear-cache!)
    (let [call-count (atom 0)]
      (with-redefs [hato.client/get (fn [_url _opts]
                                      (swap! call-count inc)
                                      (if (< @call-count 3)
                                        (throw (java.net.ConnectException. "refused"))
                                        {:status 200 :body "\"ok\""}))
                    edgar.core/throttle! (fn [] nil)
                    edgar.core/retry-base-ms 0]
        (core/set-identity! "Test test@example.com")
        (let [result (core/edgar-get "https://example.com/transport-retry")]
          (is (= "ok" result))
          (is (= 3 @call-count)))))))

(deftest exhausted-transport-errors-throw-test
  (testing "throws ::http-error after max retries on transport exceptions"
    (core/clear-cache!)
    (with-redefs [hato.client/get (fn [_url _opts]
                                    (throw (java.net.ConnectException. "refused")))
                  edgar.core/throttle! (fn [] nil)
                  edgar.core/retry-base-ms 0]
      (core/set-identity! "Test test@example.com")
      (let [ex (try (core/edgar-get "https://example.com/transport-exhaust") nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= ::core/http-error (:type (ex-data ex))))))))

(deftest cache-eviction-test
  (testing "expired entries are removed from cache when a new entry is put"
    (core/clear-cache!)
    ;; Manually insert an already-expired entry by poking the atom directly
    (let [cache (var-get #'edgar.core/cache)
          expired-at (.minusMillis (java.time.Instant/now) 1000)]
      (swap! cache assoc
             "https://example.com/expired"
             {:value "old" :expires-at expired-at}))
    ;; Confirm the expired entry is present before eviction
    (is (= 1 (count @(var-get #'edgar.core/cache)))
        "expired entry should be present before next put")
    ;; Trigger a cache-put! by calling edgar-get with a stubbed response
    (with-redefs [hato.client/get (fn [_url _opts]
                                    {:status 200 :body "\"fresh\""})
                  edgar.core/throttle! (fn [] nil)]
      (core/set-identity! "Test test@example.com")
      (core/edgar-get "https://example.com/fresh"))
    ;; Expired entry should now be gone; only the fresh entry should remain
    (let [cache-contents @(var-get #'edgar.core/cache)]
      (is (not (contains? cache-contents "https://example.com/expired"))
          "expired entry must be evicted after next cache-put!")
      (is (contains? cache-contents "https://example.com/fresh")
          "newly cached entry must be present")))
  (testing "non-expired entries are not evicted"
    (core/clear-cache!)
    ;; Insert a still-valid entry
    (let [cache (var-get #'edgar.core/cache)
          future-at (.plusMillis (java.time.Instant/now) 60000)]
      (swap! cache assoc
             "https://example.com/valid"
             {:value "still-good" :expires-at future-at}))
    ;; Trigger cache-put! for a different URL
    (with-redefs [hato.client/get (fn [_url _opts]
                                    {:status 200 :body "\"new\""})
                  edgar.core/throttle! (fn [] nil)]
      (core/set-identity! "Test test@example.com")
      (core/edgar-get "https://example.com/another"))
    ;; Both entries should still be present
    (let [cache-contents @(var-get #'edgar.core/cache)]
      (is (contains? cache-contents "https://example.com/valid")
          "non-expired entry must survive eviction sweep")
      (is (contains? cache-contents "https://example.com/another")))))

(deftest cache-eviction-throttled-test
  (testing "eviction is skipped for puts 2..N (not every put is O(n))"
    (core/clear-cache!)
    ;; After clear, put-count is at (dec eviction-interval).
    ;; First put triggers eviction (counter wraps to 0).
    ;; Subsequent puts 2..N should NOT trigger eviction — counter is 1..N-1.
    (let [eviction-call-count (atom 0)
          real-evict! (var-get #'edgar.core/cache-evict!)]
      (with-redefs [hato.client/get (fn [_url _opts] {:status 200 :body "\"v\""})
                    edgar.core/throttle! (fn [] nil)
                    edgar.core/cache-evict! (fn []
                                              (swap! eviction-call-count inc)
                                              (real-evict!))]
        (core/set-identity! "Test test@example.com")
        ;; Put 1 — triggers eviction (put-count wraps 99→0)
        (core/edgar-get "https://example.com/p1")
        (is (= 1 @eviction-call-count) "put 1 must trigger eviction")
        ;; Puts 2..5 — must NOT trigger eviction
        (doseq [i (range 2 6)]
          (core/edgar-get (str "https://example.com/p" i)))
        (is (= 1 @eviction-call-count)
            "puts 2-5 must not trigger eviction (counter not yet at interval)"))))
  (testing "eviction fires again after exactly eviction-interval further puts"
    (core/clear-cache!)
    (let [eviction-call-count (atom 0)
          interval (var-get #'edgar.core/eviction-interval)
          real-evict! (var-get #'edgar.core/cache-evict!)]
      (with-redefs [hato.client/get (fn [_url _opts] {:status 200 :body "\"v\""})
                    edgar.core/throttle! (fn [] nil)
                    edgar.core/cache-evict! (fn []
                                              (swap! eviction-call-count inc)
                                              (real-evict!))]
        (core/set-identity! "Test test@example.com")
        ;; First put triggers eviction (put-count: 99→0)
        (core/edgar-get "https://example.com/first")
        (is (= 1 @eviction-call-count))
        ;; Do exactly (dec interval) more puts — counter goes 1..(interval-1), no eviction
        (doseq [i (range 1 interval)]
          (core/edgar-get (str "https://example.com/mid" i)))
        (is (= 1 @eviction-call-count)
            "no further eviction after (dec interval) puts")
        ;; One more put — counter wraps back to 0, eviction fires
        (core/edgar-get "https://example.com/last")
        (is (= 2 @eviction-call-count)
            "eviction fires again after exactly eviction-interval further puts")))))
