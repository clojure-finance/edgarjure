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
    (format "%010d" (Long/parseLong (str (:cik_str entry))))))

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

(defn- shape-address
  "Extract a clean address map from a raw SEC address node."
  [addr]
  (when addr
    {:street1 (:street1 addr)
     :street2 (some-> (:street2 addr) not-empty)
     :city (:city addr)
     :state (:stateOrCountry addr)
     :state-description (:stateOrCountryDescription addr)
     :zip (:zipCode addr)
     :foreign? (= 1 (:isForeignLocation addr))}))

(defn company-metadata
  "Return a shaped metadata map for a company from the SEC submissions endpoint.
   Accepts ticker or CIK. Extracts the most useful fields from the raw response.

   Returns:
     {:cik               \"0000320193\"
      :name              \"Apple Inc.\"
      :tickers           [\"AAPL\"]
      :exchanges         [\"Nasdaq\"]
      :sic               \"3571\"
      :sic-description   \"Electronic Computers\"
      :entity-type       \"operating\"
      :category          \"Large accelerated filer\"
      :state-of-inc      \"CA\"
      :state-of-inc-description \"CA\"
      :fiscal-year-end   \"0926\"   ; MMDD format as returned by SEC
      :ein               \"942404110\"
      :phone             \"(408) 996-1010\"
      :website           \"\"
      :addresses         {:business {...} :mailing {...}}
      :former-names      [{:name \"...\" :date \"...\"}]}"
  [ticker-or-cik]
  (let [raw (get-company ticker-or-cik)]
    {:cik (format "%010d" (Long/parseLong (str (:cik raw))))
     :name (:name raw)
     :tickers (:tickers raw)
     :exchanges (:exchanges raw)
     :sic (:sic raw)
     :sic-description (:sicDescription raw)
     :entity-type (:entityType raw)
     :category (:category raw)
     :state-of-inc (:stateOfIncorporation raw)
     :state-of-inc-description (:stateOfIncorporationDescription raw)
     :fiscal-year-end (:fiscalYearEnd raw)
     :ein (:ein raw)
     :phone (:phone raw)
     :website (not-empty (:website raw))
     :investor-website (not-empty (:investorWebsite raw))
     :addresses {:business (shape-address (get-in raw [:addresses :business]))
                 :mailing (shape-address (get-in raw [:addresses :mailing]))}
     :former-names (mapv #(hash-map :name (:name %) :date (:date %))
                         (:formerNames raw))}))

(defn company-cik
  "Return the zero-padded 10-digit CIK for a ticker or CIK input.
   Throws ex-info with :type ::unknown-ticker when a ticker cannot be resolved."
  [ticker-or-cik]
  (if (re-matches #"\d+" (str ticker-or-cik))
    (format "%010d" (Long/parseLong (str ticker-or-cik)))
    (or (ticker->cik ticker-or-cik)
        (throw (ex-info (str "Unknown ticker: " ticker-or-cik)
                        {:type ::unknown-ticker :ticker ticker-or-cik})))))

;;; ---------------------------------------------------------------------------
;;; Company search via EFTS full-text search
;;; ---------------------------------------------------------------------------

(defn search-companies
  "Search EDGAR for companies matching a name query string.
   Returns a seq of shaped result maps with keys:
     :entity-name - company display name (parsed from display_names)
     :cik         - zero-padded 10-digit CIK
     :location    - business location string or nil
     :inc-states  - vector of incorporation state codes"
  [query & {:keys [limit] :or {limit 10}}]
  (let [resp (core/edgar-get (str core/efts-url
                                  "?q=" (java.net.URLEncoder/encode query "UTF-8")
                                  "&hits.hits._source=display_names,ciks,biz_locations,inc_states"))
        hits (get-in resp [:hits :hits])
        shaped (keep (fn [hit]
                       (let [src (:_source hit)
                             cik (first (:ciks src))
                             display (first (:display_names src))]
                         (when (and cik display)
                           {:entity-name (-> display
                                             (str/replace #"\s*\(CIK [^)]+\)" "")
                                             (str/replace #"\s*\([^)]+\)\s*$" "")
                                             str/trim)
                            :cik cik
                            :location (first (:biz_locations src))
                            :inc-states (vec (:inc_states src))})))
                     hits)
        deduped (vals (reduce (fn [acc m] (if (contains? acc (:cik m)) acc (assoc acc (:cik m) m)))
                              {}
                              shaped))]
    (take limit deduped)))
