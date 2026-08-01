(ns edgar.core
  (:require [hato.client :as hato]
            [jsonista.core :as json]
            [clojure.edn]
            [clojure.string])
  (:import [io.github.bucket4j Bandwidth Bucket]))

;;; ---------------------------------------------------------------------------
;;; Identity (SEC requires User-Agent: "Name email")
;;; ---------------------------------------------------------------------------

(def ^:dynamic *identity*
  "SEC-required User-Agent string. Set via set-identity! before making requests."
  nil)

(defn set-identity!
  "Set the SEC Edgar identity used in all HTTP requests.
   Required by SEC fair-use policy.
   Example: (set-identity! \"Your Name your@email.com\")"
  [name-and-email]
  (alter-var-root #'*identity* (constantly name-and-email)))

(defn- identity-header []
  (when-not *identity*
    (throw (ex-info "Edgar identity not set. Call (set-identity! \"Name email\") first."
                    {:type ::missing-identity})))
  {"User-Agent" *identity*
   "Accept" "application/json"})

;;; ---------------------------------------------------------------------------
;;; Rate limiter — SEC allows max 10 requests/second
;;; ---------------------------------------------------------------------------

(def ^:private rate-limiter
  (delay
    (let [bandwidth (-> (Bandwidth/builder)
                        (.capacity 10)
                        (.refillGreedy 10 (java.time.Duration/ofSeconds 1))
                        .build)]
      (-> (Bucket/builder)
          (.addLimit bandwidth)
          .build))))

(defn- throttle! []
  (.consume (.asBlocking @rate-limiter) 1))

;;; ---------------------------------------------------------------------------
;;; HTTP client (hato, persistent connection pool)
;;; ---------------------------------------------------------------------------

(def ^:private http-client
  (delay (hato/build-http-client {:connect-timeout 10000
                                  :redirect-policy :always})))

;;; ---------------------------------------------------------------------------
;;; In-memory TTL cache
;;; ---------------------------------------------------------------------------

(def ^:private cache (atom {}))

(def ^:private eviction-interval
  "Number of cache-put! calls between full expired-entry sweeps.
   Between sweeps the O(n) scan is skipped to avoid a linear cost on every put
   in long-running processes. clear-cache! resets the counter to
   (dec eviction-interval) so the very next put after a clear always evicts."
  100)

(def ^:private put-count
  "Counts cache-put! calls mod eviction-interval.
   Initialised to (dec eviction-interval) so tests that call clear-cache!
   first will trigger an eviction sweep on their first subsequent put."
  (atom (dec eviction-interval)))

(def ^:private cache-ttl-metadata
  "TTL in milliseconds for metadata responses (submissions, tickers, search)."
  (* 5 60 1000))

(def ^:private cache-ttl-facts
  "TTL in milliseconds for heavy responses (company-facts, frames)."
  (* 60 60 1000))

(defn- cache-ttl-for [url]
  (if (re-find #"/api/xbrl/" url)
    cache-ttl-facts
    cache-ttl-metadata))

(defn- cache-get [url]
  (let [{:keys [value expires-at]} (get @cache url)]
    (when (and value (.isAfter ^java.time.Instant expires-at (java.time.Instant/now)))
      value)))

(defn- cache-evict!
  "Remove all expired entries from the cache. Called before each put to bound
   memory growth in long-running processes."
  []
  (let [now (java.time.Instant/now)]
    (swap! cache (fn [m]
                   (into {} (remove (fn [[_ v]]
                                      (.isAfter now ^java.time.Instant (:expires-at v)))
                                    m))))))

(def ^:private cache-max-entries
  "Upper bound on JSON cache entries. Parsed facts responses can be tens of
   MB each, so the cache is bounded by count in addition to the periodic
   expired-entry sweep; the entries closest to expiry are dropped first."
  256)

(defn- cache-put! [url value]
  (when (zero? (swap! put-count #(mod (inc %) eviction-interval)))
    (cache-evict!))
  (let [ttl (cache-ttl-for url)
        expires-at (.plusMillis (java.time.Instant/now) ttl)]
    (swap! cache
           (fn [m]
             (let [m (assoc m url {:value value :expires-at expires-at})
                   overflow (- (count m) cache-max-entries)]
               (if (pos? overflow)
                 (into {} (drop overflow) (sort-by #(:expires-at (val %)) m))
                 m))))))

;;; ---------------------------------------------------------------------------
;;; Bounded raw-response cache
;;;
;;; Raw (string) responses — filing HTML, filing indexes, .idx files — can be
;;; several MB each, so unlike the JSON cache this one is bounded by entry
;;; count: when full, the oldest-inserted entries are evicted first.
;;; This makes repeated REPL calls like (e/text f) → (e/items f) → (e/tables f)
;;; hit the network once instead of re-downloading the document every time.
;;; ---------------------------------------------------------------------------

(def ^:private raw-cache-max-entries 64)

(def ^:private raw-cache-ttl
  "TTL in milliseconds for raw responses (filing documents are immutable
   once published, so a long TTL is safe)."
  (* 60 60 1000))

(def ^:private raw-cache (atom {}))

(defn- raw-cache-get [url]
  (let [{:keys [value expires-at]} (get @raw-cache url)]
    (when (and value (.isAfter ^java.time.Instant expires-at (java.time.Instant/now)))
      value)))

(defn- raw-cache-put! [url value]
  (let [now (java.time.Instant/now)]
    (swap! raw-cache
           (fn [m]
             (let [live (into {} (remove (fn [[_ v]]
                                           (.isAfter now ^java.time.Instant (:expires-at v)))
                                         m))
                   trimmed (if (>= (count live) raw-cache-max-entries)
                             (->> live
                                  (sort-by (fn [[_ v]] (:inserted-at v)))
                                  (drop (inc (- (count live) raw-cache-max-entries)))
                                  (into {}))
                             live)]
               (assoc trimmed url {:value value
                                   :expires-at (.plusMillis now raw-cache-ttl)
                                   :inserted-at now}))))))

(defonce ^:private extra-cache-clearers (atom {}))

(defn register-cache-clearer!
  "Register (or replace) a named 0-arg fn that clear-cache! also runs.
   Lets higher-level namespaces hook their own caches into clear-cache!
   (e.g. edgar.company's ticker maps) without a circular dependency."
  [k f]
  (swap! extra-cache-clearers assoc k f))

(defn clear-cache!
  "Clear the in-memory HTTP response caches (JSON and raw), any registered
   derived caches (e.g. the ticker maps), and reset the eviction counter.
   After calling this, the next cache-put! will perform a full eviction
   sweep."
  []
  (reset! cache {})
  (reset! raw-cache {})
  (reset! put-count (dec eviction-interval))
  (doseq [[_ f] @extra-cache-clearers] (f))
  nil)

;;; ---------------------------------------------------------------------------
;;; Disk cache (opt-in, off by default)
;;;
;;; Persists HTTP response bodies across sessions as one pair of files per
;;; URL under a cache directory: <sha1>.edn (metadata: url, expiry) and
;;; <sha1>.dat (the body). No extra dependencies, transparent on disk,
;;; cleared with clear-disk-cache! or plain rm.
;;;
;;; TTLs are longer than the in-memory layer because the point is
;;; cross-session reuse: filing documents under /Archives/ are immutable
;;; once published (30-day default), JSON endpoints get 24 hours.
;;; ---------------------------------------------------------------------------

(def ^:private disk-cache-config (atom nil))

(defn- sha1-hex [^String s]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-1")
                   (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) d))))

(defn enable-disk-cache!
  "Enable the persistent on-disk HTTP cache (off by default).
   Options:
     :dir             - cache directory (default ~/.edgarjure/http-cache)
     :ttl-json-ms     - TTL for JSON endpoints: submissions, companyfacts,
                        tickers, search (default 24 h)
     :ttl-raw-ms      - TTL for raw documents: filing HTML/XML, indexes
                        (default 30 days; published filings are immutable)
   Returns the config map in effect."
  [& {:keys [dir ttl-json-ms ttl-raw-ms]
      :or {ttl-json-ms (* 24 60 60 1000)
           ttl-raw-ms (* 30 24 60 60 1000)}}]
  (let [dir (java.io.File. (str (or dir (str (System/getProperty "user.home")
                                             "/.edgarjure/http-cache"))))]
    (.mkdirs dir)
    (reset! disk-cache-config {:dir dir
                               :ttl-json-ms ttl-json-ms
                               :ttl-raw-ms ttl-raw-ms})))

(defn disable-disk-cache!
  "Disable the on-disk HTTP cache (files are kept; delete with
   clear-disk-cache! first if desired)."
  []
  (reset! disk-cache-config nil))

(defn- disk-entry-files [url raw?]
  ;; raw? is part of the key: the same URL fetched raw and as JSON must not
  ;; share an entry (different TTLs, and a raw body handed to the JSON path
  ;; would fail to parse)
  (when-let [{:keys [dir]} @disk-cache-config]
    (let [h (sha1-hex (str url (when raw? "#raw")))]
      [(java.io.File. ^java.io.File dir (str h ".edn"))
       (java.io.File. ^java.io.File dir (str h ".dat"))])))

(defn- disk-cache-get
  "Return the cached body string for url, or nil. Deletes expired entries."
  [url raw?]
  (when-let [[meta-f data-f] (disk-entry-files url raw?)]
    (when (and (.isFile ^java.io.File meta-f) (.isFile ^java.io.File data-f))
      (let [{:keys [expires-at]} (clojure.edn/read-string (slurp meta-f))]
        (if (> (long expires-at) (System/currentTimeMillis))
          (slurp data-f)
          (do (.delete ^java.io.File meta-f)
              (.delete ^java.io.File data-f)
              nil))))))

(defn- disk-cache-put! [url raw? ^String body]
  (when-let [{:keys [ttl-json-ms ttl-raw-ms]} @disk-cache-config]
    (when-let [[meta-f data-f] (disk-entry-files url raw?)]
      (let [ttl (if raw? ttl-raw-ms ttl-json-ms)
            tmp (java.io.File/createTempFile "edgarjure" ".tmp"
                                             (.getParentFile ^java.io.File data-f))]
        (spit tmp body)
        ;; only write the metadata once the body is in place — and clean the
        ;; tmp file up on failure, since it matches no cache-maintenance filter
        (if (.renameTo tmp ^java.io.File data-f)
          (spit meta-f (pr-str {:url url
                                :raw? raw?
                                :expires-at (+ (System/currentTimeMillis) (long ttl))}))
          (.delete tmp))))))

(defn clear-disk-cache!
  "Delete every entry in the on-disk cache directory (whether or not the
   disk cache is currently enabled). Returns the number of entries removed."
  [& {:keys [dir]}]
  (let [dir (java.io.File. (str (or dir
                                    (:dir @disk-cache-config)
                                    (str (System/getProperty "user.home")
                                         "/.edgarjure/http-cache"))))
        files (filter #(re-matches #"[0-9a-f]{40}\.(edn|dat)" (.getName ^java.io.File %))
                      (or (.listFiles dir) []))]
    (doseq [^java.io.File f files] (.delete f))
    (quot (count files) 2)))

(defn disk-cache-stats
  "Return {:dir path :entries n :bytes total} for the on-disk cache, or nil
   when disabled."
  []
  (when-let [{:keys [dir]} @disk-cache-config]
    (let [files (filter #(re-matches #"[0-9a-f]{40}\.(edn|dat)" (.getName ^java.io.File %))
                        (or (.listFiles ^java.io.File dir) []))]
      {:dir (str dir)
       :entries (count (filter #(clojure.string/ends-with? (.getName ^java.io.File %) ".dat") files))
       :bytes (reduce + 0 (map #(.length ^java.io.File %) files))})))

;;; ---------------------------------------------------------------------------
;;; HTTP GET helpers (with exponential backoff retry)
;;; ---------------------------------------------------------------------------

(def ^:private max-retries 3)
(def ^:private retry-base-ms 2000)

(defn- retryable? [status]
  (or (= status 429) (>= status 500)))

(defn- http-get-with-retry
  "Execute a hato GET with exponential backoff retry on 429/5xx and transport errors.
   Uses :throw-exceptions? false so all HTTP status codes are returned as response maps
   rather than thrown — enabling status-based retry logic to work correctly.
   Transport-level exceptions (timeouts, connection resets) are also retried.
   Returns the response map on success.
   Throws ex-info ::http-error on exhausted retries or non-retryable 4xx."
  [url opts]
  (loop [attempt 0]
    (throttle!)
    (let [resp (try
                 (hato/get url (assoc opts :throw-exceptions? false))
                 (catch Exception e
                   (if (< attempt max-retries)
                     (do (Thread/sleep (* retry-base-ms (long (Math/pow 2 attempt))))
                         ::transport-error)
                     (throw (ex-info (str "Transport error fetching " url)
                                     {:type ::http-error :url url}
                                     e)))))]
      (if (= resp ::transport-error)
        (recur (inc attempt))
        (let [status (:status resp)]
          (cond
            (< status 400) resp
            (and (retryable? status) (< attempt max-retries))
            (do (Thread/sleep (* retry-base-ms (long (Math/pow 2 attempt))))
                (recur (inc attempt)))
            :else
            (throw (ex-info (str "HTTP " status " from SEC API")
                            {:type ::http-error :status status :url url}))))))))

(defn edgar-get
  "Rate-limited GET against any SEC URL.
   Returns parsed JSON as a Clojure map, or raw body string if :raw? true.
   JSON responses are cached in memory (5 min for metadata, 1 hr for XBRL facts).
   Raw responses are cached in a bounded cache (64 entries, 1 hr TTL) so that
   repeated content access on the same filing does not re-download it.
   When the opt-in disk cache is enabled (enable-disk-cache!), responses are
   also persisted across sessions and consulted between the memory caches
   and the network.
   Retries on 429/5xx with exponential backoff (up to 3 attempts).
   Options:
     :raw?  - return body as string instead of parsing JSON (default false)"
  [url & {:keys [raw?] :or {raw? false}}]
  (if raw?
    (or (raw-cache-get url)
        (when-let [body (disk-cache-get url true)]
          (raw-cache-put! url body)
          body)
        (let [body (:body (http-get-with-retry url {:http-client @http-client
                                                    :headers (identity-header)
                                                    :as :string}))]
          (raw-cache-put! url body)
          (disk-cache-put! url true body)
          body))
    (or (cache-get url)
        (when-let [body (disk-cache-get url false)]
          (let [result (json/read-value body json/keyword-keys-object-mapper)]
            (cache-put! url result)
            result))
        (let [body (:body (http-get-with-retry url {:http-client @http-client
                                                    :headers (identity-header)
                                                    :as :string}))
              result (json/read-value body json/keyword-keys-object-mapper)]
          (cache-put! url result)
          (disk-cache-put! url false body)
          result))))

(defn edgar-get-bytes
  "Rate-limited GET returning raw bytes — for binary/archive downloads.
   Retries on 429/5xx with exponential backoff."
  [url]
  (:body (http-get-with-retry url {:http-client @http-client
                                   :headers (identity-header)
                                   :as :byte-array})))

;;; ---------------------------------------------------------------------------
;;; SEC base URLs
;;; ---------------------------------------------------------------------------

(def base-url "https://www.sec.gov")
(def data-url "https://data.sec.gov")
(def archives-url "https://www.sec.gov/Archives/edgar/data")
(def full-index-url "https://www.sec.gov/Archives/edgar/full-index")
(def submissions-url (str data-url "/submissions"))
(def facts-url (str data-url "/api/xbrl/companyfacts"))
(def tickers-url (str base-url "/files/company_tickers.json"))
(def efts-url "https://efts.sec.gov/LATEST/search-index")

(defn cik-url [cik]
  (str submissions-url "/CIK" (format "%010d" (Long/parseLong (str cik))) ".json"))

(defn facts-endpoint [cik]
  (str facts-url "/CIK" (format "%010d" (Long/parseLong (str cik))) ".json"))

(defn archives-path [cik accession-no]
  (str archives-url "/" cik "/" (clojure.string/replace accession-no "-" "")))
