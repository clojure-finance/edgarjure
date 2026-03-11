(ns edgar.core
  (:require [hato.client :as hato]
            [jsonista.core :as json])
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

(defn- cache-put! [url value]
  (let [ttl (cache-ttl-for url)
        expires-at (.plusMillis (java.time.Instant/now) ttl)]
    (swap! cache assoc url {:value value :expires-at expires-at})))

(defn clear-cache!
  "Clear the in-memory HTTP response cache."
  []
  (reset! cache {}))

;;; ---------------------------------------------------------------------------
;;; HTTP GET helpers (with exponential backoff retry)
;;; ---------------------------------------------------------------------------

(def ^:private max-retries 3)
(def ^:private retry-base-ms 2000)

(defn- retryable? [status]
  (or (= status 429) (>= status 500)))

(defn- http-get-with-retry
  "Execute a hato GET with exponential backoff retry on 429/5xx.
   Returns the response map. Throws ex-info ::http-error on exhausted retries or 4xx."
  [url opts]
  (loop [attempt 0]
    (throttle!)
    (let [resp (hato/get url opts)
          status (:status resp)]
      (cond
        (< status 400) resp
        (and (retryable? status) (< attempt max-retries))
        (do (Thread/sleep (* retry-base-ms (long (Math/pow 2 attempt))))
            (recur (inc attempt)))
        :else
        (throw (ex-info (str "HTTP " status " from SEC API")
                        {:type ::http-error :status status :url url}))))))

(defn edgar-get
  "Rate-limited GET against any SEC URL.
   Returns parsed JSON as a Clojure map, or raw body string if :raw? true.
   JSON responses are cached in memory (5 min for metadata, 1 hr for XBRL facts).
   Retries on 429/5xx with exponential backoff (up to 3 attempts).
   Options:
     :raw?  - return body as string instead of parsing JSON; skips cache (default false)"
  [url & {:keys [raw?] :or {raw? false}}]
  (if raw?
    (:body (http-get-with-retry url {:http-client @http-client
                                     :headers (identity-header)
                                     :as :string}))
    (if-let [cached (cache-get url)]
      cached
      (let [result (json/read-value
                    (:body (http-get-with-retry url {:http-client @http-client
                                                     :headers (identity-header)
                                                     :as :string}))
                    json/keyword-keys-object-mapper)]
        (cache-put! url result)
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
