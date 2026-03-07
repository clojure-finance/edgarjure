(ns edgar.company
  (:require [edgar.core :as core]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; CIK / ticker lookup
;;; SEC endpoint: https://www.sec.gov/files/company_tickers.json
;;; Returns {0 {:cik_str "...", :ticker "...", :title "..."}, 1 ...}
;;; ---------------------------------------------------------------------------

(def ^:private tickers-cache (atom nil))

(defn- load-tickers! []
  (when-not @tickers-cache
    (reset! tickers-cache
            (core/edgar-get core/tickers-url)))
  @tickers-cache)

(defn- tickers-by-ticker []
  (into {}
        (map (fn [[_ v]] [(str/upper-case (:ticker v)) v]))
        (load-tickers!)))

(defn ticker->cik
  "Resolve a ticker symbol to a CIK string (zero-padded to 10 digits).
   Returns nil if not found."
  [ticker]
  (when-let [entry (get (tickers-by-ticker) (str/upper-case ticker))]
    (format "%010d" (:cik_str entry))))

(defn cik->ticker
  "Reverse lookup: CIK integer or string → ticker symbol."
  [cik]
  (let [cik-long (Long/parseLong (str cik))]
    (->> (load-tickers!)
         vals
         (filter #(= cik-long (Long/parseLong (str (:cik_str %)))))
         first
         :ticker)))

;;; ---------------------------------------------------------------------------
;;; Company metadata
;;; SEC endpoint: https://data.sec.gov/submissions/CIK##########.json
;;; ---------------------------------------------------------------------------

(defn get-company
  "Fetch full company metadata map from SEC submissions endpoint.
   Accepts ticker string or CIK (string or integer).
   Returns a map with keys :cik :name :tickers :sic :sic-description
   :state-of-incorporation :fiscal-year-end :filings and more."
  [ticker-or-cik]
  (let [cik (if (re-matches #"\d+" (str ticker-or-cik))
              (format "%010d" (Long/parseLong (str ticker-or-cik)))
              (ticker->cik (str ticker-or-cik)))]
    (when-not cik
      (throw (ex-info (str "Could not resolve CIK for: " ticker-or-cik)
                      {:ticker-or-cik ticker-or-cik})))
    (core/edgar-get (core/cik-url cik))))

(defn company-name
  "Return the company name for a ticker or CIK."
  [ticker-or-cik]
  (:name (get-company ticker-or-cik)))

(defn company-cik
  "Return the zero-padded 10-digit CIK for a ticker or CIK input."
  [ticker-or-cik]
  (if (re-matches #"\d+" (str ticker-or-cik))
    (format "%010d" (Long/parseLong (str ticker-or-cik)))
    (ticker->cik ticker-or-cik)))

;;; ---------------------------------------------------------------------------
;;; Company search via EFTS full-text search
;;; ---------------------------------------------------------------------------

(defn search-companies
  "Search EDGAR for companies matching a name query string.
   Returns a seq of result maps with :entity-name :cik :category."
  [query & {:keys [limit] :or {limit 10}}]
  (let [resp (core/edgar-get (str core/efts-url
                                  "?q=" (java.net.URLEncoder/encode query "UTF-8")
                                  "&dateRange=custom"
                                  "&forms=10-K"
                                  "&hits.hits._source=period_of_report,entity_name,file_num,period_of_report,biz_location,inc_states"))]
    (->> (get-in resp [:hits :hits])
         (take limit)
         (map #(get % :_source)))))
