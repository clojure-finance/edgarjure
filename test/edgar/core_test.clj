(ns edgar.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hato.client]
            [edgar.core :as core]))

;; These tests set a throwaway identity and clear the caches; restore the
;; caller's identity (e.g. a REPL session's) and leave clean caches behind.
(use-fixtures :once
  (fn [f]
    (let [saved core/*identity*]
      (try (f)
           (finally
             (alter-var-root #'core/*identity* (constantly saved))
             (core/disable-disk-cache!)
             (core/clear-cache!))))))

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

(deftest pad-cik-test
  (testing "integers and numeric strings, padded or not, normalise to 10 digits"
    (is (= "0000320193" (core/pad-cik 320193)))
    (is (= "0000320193" (core/pad-cik "320193")))
    (is (= "0000320193" (core/pad-cik "0000320193")))
    (is (= "0000320193" (core/pad-cik " 320193 ")))))

(deftest set-identity-test
  (testing "set-identity! updates *identity*"
    (core/set-identity! "Test User test@example.com")
    (is (= "Test User test@example.com" core/*identity*))))

(deftest endpoint-urls-test
  (testing "endpoint builders pad the CIK"
    (is (= "https://data.sec.gov/submissions/CIK0000320193.json" (core/cik-url 320193)))
    (is (= "https://data.sec.gov/api/xbrl/companyfacts/CIK0000320193.json"
           (core/facts-endpoint "320193"))))
  (testing "archive-url strips CIK zeros and accession dashes"
    (is (= "https://www.sec.gov/Archives/edgar/data/320193/000032019325000079/"
           (core/archive-url "0000320193" "0000320193-25-000079")))
    (is (= "https://www.sec.gov/Archives/edgar/data/320193/000032019325000079/aapl-20250927.htm"
           (core/archive-url 320193 "000032019325000079" "aapl-20250927.htm")))))

(deftest cik-and-accession-normalisation-test
  (is (= "0000320193" (core/pad-cik " 320193 ")))
  (is (= "320193" (core/unpad-cik "0000320193")))
  (testing "dashed-accession accepts undashed and dashed input, passes others through"
    (is (= "0000320193-23-000064" (core/dashed-accession "000032019323000064")))
    (is (= "0001193125-22-082474" (core/dashed-accession "0001193125-22-082474")))
    (is (= "not-an-accession" (core/dashed-accession "not-an-accession")))))

(deftest url-encode-test
  (testing "spaces become %20 (EFTS rejects +), reserved characters are escaped"
    (is (= "%22material%20weakness%22%20%2B%20a%26b" (core/url-encode "\"material weakness\" + a&b")))))

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

;;; ---------------------------------------------------------------------------
;;; Disk cache (opt-in) — stubbed HTTP, temp directory
;;; ---------------------------------------------------------------------------

(defn- temp-cache-dir []
  (let [d (java.io.File/createTempFile "edgarjure-test-cache" "")]
    (.delete d)
    (.mkdirs d)
    (str d)))

(deftest disk-cache-disabled-by-default-test
  (core/clear-cache!)
  (core/disable-disk-cache!)
  (testing "no disk files written when disabled"
    (let [call-count (atom 0)]
      (with-redefs [hato.client/get (fn [_ _] (swap! call-count inc)
                                      {:status 200 :body "\"v\""})
                    edgar.core/throttle! (fn [] nil)]
        (core/set-identity! "Test test@example.com")
        (core/edgar-get "https://example.com/disk-off")
        (is (= 1 @call-count))
        (is (nil? (core/disk-cache-stats)))))))

(deftest disk-cache-round-trip-test
  (core/clear-cache!)
  (let [dir (temp-cache-dir)
        call-count (atom 0)]
    (try
      (core/enable-disk-cache! :dir dir)
      (with-redefs [hato.client/get (fn [_ _] (swap! call-count inc)
                                      {:status 200 :body "{\"a\":1}"})
                    edgar.core/throttle! (fn [] nil)]
        (core/set-identity! "Test test@example.com")
        (testing "first fetch hits network and writes the disk entry"
          (is (= {:a 1} (core/edgar-get "https://example.com/disk-json")))
          (is (= 1 @call-count))
          (is (= 1 (:entries (core/disk-cache-stats)))))
        (testing "after clearing memory, the disk entry answers without network"
          (core/clear-cache!)
          (is (= {:a 1} (core/edgar-get "https://example.com/disk-json")))
          (is (= 1 @call-count) "no second network call"))
        (testing "raw responses round-trip too"
          (with-redefs [hato.client/get (fn [_ _] (swap! call-count inc)
                                          {:status 200 :body "<html>doc</html>"})
                        edgar.core/throttle! (fn [] nil)]
            (is (= "<html>doc</html>" (core/edgar-get "https://example.com/disk-raw" :raw? true)))
            (core/clear-cache!)
            (is (= "<html>doc</html>" (core/edgar-get "https://example.com/disk-raw" :raw? true)))
            (is (= 2 @call-count) "raw fetched from network exactly once"))))
      (finally
        (core/clear-disk-cache! :dir dir)
        (core/disable-disk-cache!)))))

(deftest disk-cache-expiry-test
  (core/clear-cache!)
  (let [dir (temp-cache-dir)
        call-count (atom 0)]
    (try
      (core/enable-disk-cache! :dir dir :ttl-json-ms -1)
      (with-redefs [hato.client/get (fn [_ _] (swap! call-count inc)
                                      {:status 200 :body "\"v\""})
                    edgar.core/throttle! (fn [] nil)]
        (core/set-identity! "Test test@example.com")
        (core/edgar-get "https://example.com/disk-expired")
        (core/clear-cache!)
        (testing "already-expired entry is refetched and deleted"
          (is (= "v" (core/edgar-get "https://example.com/disk-expired")))
          (is (= 2 @call-count))))
      (finally
        (core/clear-disk-cache! :dir dir)
        (core/disable-disk-cache!)))))

(deftest clear-disk-cache-test
  (let [dir (temp-cache-dir)]
    (try
      (core/enable-disk-cache! :dir dir)
      (core/clear-cache!)
      (with-redefs [hato.client/get (fn [_ _] {:status 200 :body "\"x\""})
                    edgar.core/throttle! (fn [] nil)]
        (core/set-identity! "Test test@example.com")
        (core/edgar-get "https://example.com/disk-clear-1")
        (core/edgar-get "https://example.com/disk-clear-2")
        (testing "clear removes all entries and reports the count"
          (is (= 2 (:entries (core/disk-cache-stats))))
          (is (= 2 (core/clear-disk-cache!)))
          (is (= 0 (:entries (core/disk-cache-stats))))))
      (finally
        (core/disable-disk-cache!)))))

(deftest disk-cache-raw-json-key-separation-test
  ;; the same URL fetched :raw? and as JSON must not share a disk entry —
  ;; a raw HTML body handed to the JSON path would fail to parse
  (core/clear-cache!)
  (let [dir (temp-cache-dir)]
    (try
      (core/enable-disk-cache! :dir dir)
      (core/set-identity! "Test test@example.com")
      (with-redefs [hato.client/get (fn [_ _] {:status 200 :body "<html>doc</html>"})
                    edgar.core/throttle! (fn [] nil)]
        (is (= "<html>doc</html>"
               (core/edgar-get "https://example.com/same-url" :raw? true))))
      (core/clear-cache!)
      (with-redefs [hato.client/get (fn [_ _] {:status 200 :body "{\"a\":1}"})
                    edgar.core/throttle! (fn [] nil)]
        (testing "JSON fetch of the same URL does not read the raw entry"
          (is (= {:a 1} (core/edgar-get "https://example.com/same-url"))))
        (testing "two separate disk entries exist"
          (is (= 2 (:entries (core/disk-cache-stats))))))
      (finally
        (core/clear-disk-cache! :dir dir)
        (core/disable-disk-cache!)))))

(deftest disk-cache-index-ttl-test
  ;; full-index/daily-index files change while their period is open, so
  ;; they must not get the 30-day immutable-document TTL
  (let [ttl #'edgar.core/disk-ttl-ms
        config {:ttl-json-ms 1 :ttl-raw-ms 2}]
    (testing "raw filing documents get the long raw TTL"
      (is (= 2 (ttl config "https://www.sec.gov/Archives/edgar/data/320193/000032019325000079/aapl-20250927.htm" true))))
    (testing "full-index and daily-index files get the short JSON TTL"
      (is (= 1 (ttl config "https://www.sec.gov/Archives/edgar/full-index/2026/QTR4/master.idx" true)))
      (is (= 1 (ttl config "https://www.sec.gov/Archives/edgar/daily-index/2026/QTR4/master.20261013.idx" true)))
      (is (= 1 (ttl config "https://www.sec.gov/Archives/edgar/daily-index/2026/QTR4/index.json" false))))
    (testing "JSON endpoints get the JSON TTL"
      (is (= 1 (ttl config "https://data.sec.gov/submissions/CIK0000320193.json" false))))))

(deftest disk-cache-index-expiry-round-trip-test
  (core/clear-cache!)
  (let [dir (temp-cache-dir)
        calls (atom {})]
    (try
      ;; JSON TTL already expired, raw TTL 30 days
      (core/enable-disk-cache! :dir dir :ttl-json-ms -1)
      (core/set-identity! "Test test@example.com")
      (with-redefs [hato.client/get (fn [url _] (swap! calls update url (fnil inc 0))
                                      {:status 200 :body "body"})
                    edgar.core/throttle! (fn [] nil)]
        (let [idx "https://www.sec.gov/Archives/edgar/full-index/2026/QTR4/master.idx"
              doc "https://www.sec.gov/Archives/edgar/data/1/000000000126000001/a.htm"]
          (core/edgar-get idx :raw? true)
          (core/edgar-get doc :raw? true)
          (core/clear-cache!)
          (core/edgar-get idx :raw? true)
          (core/edgar-get doc :raw? true)
          (is (= 2 (@calls idx)) "index file re-fetched once its short TTL lapsed")
          (is (= 1 (@calls doc)) "filing document served from disk")))
      (finally
        (core/clear-disk-cache! :dir dir)
        (core/disable-disk-cache!)))))

(deftest cache-option-test
  (core/clear-cache!)
  (core/disable-disk-cache!)
  (let [calls (atom 0)]
    (with-redefs [hato.client/get (fn [_ _] (swap! calls inc) {:status 200 :body "\"v\""})
                  edgar.core/throttle! (fn [] nil)]
      (core/set-identity! "Test test@example.com")
      (testing ":cache :disk skips the in-memory raw cache"
        (core/edgar-get "https://example.com/big.idx" :raw? true :cache :disk)
        (core/edgar-get "https://example.com/big.idx" :raw? true :cache :disk)
        (is (= 2 @calls))
        (is (not (contains? @@#'edgar.core/raw-cache "https://example.com/big.idx"))))
      (testing ":cache :none skips the JSON cache too"
        (reset! calls 0)
        (core/edgar-get "https://example.com/listing.json" :cache :none)
        (core/edgar-get "https://example.com/listing.json" :cache :none)
        (is (= 2 @calls))
        (is (not (contains? @@#'edgar.core/cache "https://example.com/listing.json"))))
      (testing "default :cache :all memoises"
        (reset! calls 0)
        (core/edgar-get "https://example.com/doc.htm" :raw? true)
        (core/edgar-get "https://example.com/doc.htm" :raw? true)
        (is (= 1 @calls))))))

(deftest disk-cache-skipped-for-cache-none-test
  (core/clear-cache!)
  (let [dir (temp-cache-dir)]
    (try
      (core/enable-disk-cache! :dir dir)
      (core/set-identity! "Test test@example.com")
      (with-redefs [hato.client/get (fn [_ _] {:status 200 :body "\"v\""})
                    edgar.core/throttle! (fn [] nil)]
        (core/edgar-get "https://example.com/never-cached" :cache :none)
        (is (= 0 (:entries (core/disk-cache-stats))))
        (core/edgar-get "https://example.com/disk-only" :raw? true :cache :disk)
        (is (= 1 (:entries (core/disk-cache-stats)))))
      (finally
        (core/clear-disk-cache! :dir dir)
        (core/disable-disk-cache!)))))

(deftest retries-exhausted-on-429-test
  (testing "initial attempt + 3 retries, then ::http-error with the status"
    (core/clear-cache!)
    (let [calls (atom 0)]
      (with-redefs [hato.client/get (fn [_ _] (swap! calls inc) {:status 429 :body ""})
                    edgar.core/throttle! (fn [] nil)
                    edgar.core/retry-base-ms 0]
        (core/set-identity! "Test test@example.com")
        (let [ex (try (core/edgar-get "https://example.com/always-429") nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= 4 @calls))
          (is (= ::core/http-error (:type (ex-data ex))))
          (is (= 429 (:status (ex-data ex))))
          (is (= "https://example.com/always-429" (:url (ex-data ex)))))))))

(deftest missing-identity-test
  (testing "no identity → ::missing-identity before any network call"
    (core/clear-cache!)
    (let [calls (atom 0)]
      (with-redefs [hato.client/get (fn [_ _] (swap! calls inc) {:status 200 :body "1"})
                    edgar.core/throttle! (fn [] nil)]
        (alter-var-root #'core/*identity* (constantly nil))
        (let [ex (try (core/edgar-get "https://example.com/no-identity") nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= ::core/missing-identity (:type (ex-data ex))))
          (is (zero? @calls)))))))

(deftest cache-size-bounds-test
  (core/clear-cache!)
  (with-redefs [hato.client/get (fn [_ _] {:status 200 :body "\"v\""})
                edgar.core/throttle! (fn [] nil)]
    (core/set-identity! "Test test@example.com")
    (testing "raw cache is trimmed to raw-cache-max-entries; the newest entry is kept"
      (let [n @#'edgar.core/raw-cache-max-entries]
        (doseq [i (range (+ n 5))]
          (core/edgar-get (str "https://example.com/raw" i) :raw? true))
        (let [ks (set (keys @@#'edgar.core/raw-cache))]
          (is (= n (count ks)))
          (is (contains? ks (str "https://example.com/raw" (+ n 4)))))))
    (testing "JSON cache is trimmed to cache-max-entries"
      (let [n @#'edgar.core/cache-max-entries]
        (doseq [i (range (+ n 3))]
          (core/edgar-get (str "https://example.com/json" i)))
        (is (= n (count @@#'edgar.core/cache))))))
  (core/clear-cache!))
