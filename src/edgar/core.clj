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
   Example: (set-identity! \"Dr. B buehlmaier@hku.hk\")"
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

(defn edgar-get
  "Rate-limited GET against any SEC URL.
   Returns parsed JSON as a Clojure map, or raw body string if :raw? true.
   Options:
     :raw?    - return body as string instead of parsing JSON (default false)
     :as      - override hato :as coercion (default :string)"
  [url & {:keys [raw?] :or {raw? false}}]
  (throttle!)
  (let [resp (hato/get url
                       {:http-client @http-client
                        :headers (identity-header)
                        :as :string})]
    (if raw?
      (:body resp)
      (json/read-value (:body resp) json/keyword-keys-object-mapper))))

(defn edgar-get-bytes
  "Rate-limited GET returning raw bytes — for binary/archive downloads."
  [url]
  (throttle!)
  (:body (hato/get url
                   {:http-client @http-client
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
