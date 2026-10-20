(ns edgar.company
  (:require [edgar.core :as core]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; CIK / ticker lookup
;;; SEC endpoint: https://www.sec.gov/files/company_tickers.json
;;; Returns {0 {:cik_str "...", :ticker "...", :title "..."}, 1 ...}
;;; ---------------------------------------------------------------------------

(def ^:private tickers-cache (atom nil))
(def ^:private tickers-by-ticker-cache (atom nil))

(defn- rank
  "Numeric rank of a company_tickers.json entry key (:0, :1, ...). For a
   multi-class CIK the lowest-ranked entry is the primary share class
   (GOOGL ranks before GOOG, BRK-B before BRK-A)."
  [k]
  (Long/parseLong (if (keyword? k) (name k) (str k))))

(defn- load-tickers! []
  (when-not @tickers-cache
    (let [data (core/edgar-get core/tickers-url)]
      (reset! tickers-by-ticker-cache
              (into {}
                    (map (fn [[_ v]] [(str/upper-case (:ticker v)) v]))
                    data))
      (reset! tickers-cache data)))
  @tickers-cache)

(defn- tickers-by-ticker []
  (load-tickers!)
  @tickers-by-ticker-cache)

(defn clear-ticker-cache!
  "Clear the cached ticker maps so the next lookup refetches
   company_tickers.json — long-running processes pick up new listings.
   Also run automatically by edgar.core/clear-cache!."
  []
  (reset! tickers-cache nil)
  (reset! tickers-by-ticker-cache nil))

(core/register-cache-clearer! ::tickers clear-ticker-cache!)

(defn- normalize-ticker
  "Map share-class dots to the SEC's dash form: \"BRK.B\" → \"BRK-B\"."
  [ticker]
  (str/replace ticker "." "-"))

(defn ticker->cik
  "Resolve a ticker symbol to a CIK string (zero-padded to 10 digits).
   Case-insensitive; share-class dots are accepted (\"BRK.B\" = \"BRK-B\").
   Returns nil if not found."
  [ticker]
  (let [by-ticker (tickers-by-ticker)
        t (str/upper-case (str/trim (str ticker)))]
    (when-let [entry (or (get by-ticker t)
                         (get by-ticker (normalize-ticker t)))]
      (core/pad-cik (:cik_str entry)))))

(defn cik->ticker
  "Reverse lookup: CIK integer or string → ticker symbol.
   For a CIK with several share classes the primary (lowest-ranked) listing
   in company_tickers.json is returned, e.g. 1652044 → \"GOOGL\".
   Returns nil if the CIK has no ticker."
  [cik]
  (let [cik-long (Long/parseLong (str/trim (str cik)))]
    (some->> (load-tickers!)
             (filter (fn [[_ v]] (= cik-long (Long/parseLong (str (:cik_str v))))))
             seq
             (apply min-key (comp rank key))
             val
             :ticker)))

;;; ---------------------------------------------------------------------------
;;; Company metadata
;;; SEC endpoint: https://data.sec.gov/submissions/CIK##########.json
;;; ---------------------------------------------------------------------------

(defn company-cik
  "Return the zero-padded 10-digit CIK for a ticker or CIK input.
   A CIK may be an integer (320193) or a numeric string with or without
   leading zeros. Throws ex-info with :type ::unknown-ticker when a ticker
   cannot be resolved."
  [ticker-or-cik]
  (if (or (integer? ticker-or-cik)
          (re-matches #"\d+" (str/trim (str ticker-or-cik))))
    (core/pad-cik ticker-or-cik)
    (or (ticker->cik ticker-or-cik)
        (throw (ex-info (str "Unknown ticker: " ticker-or-cik)
                        {:type ::unknown-ticker :ticker ticker-or-cik})))))

(defn get-company
  "Fetch full company metadata map from SEC submissions endpoint.
   Accepts ticker string or CIK (string or integer).
   Returns a map with keys :cik :name :tickers :sic :sic-description
   :state-of-incorporation :fiscal-year-end :filings and more.
   Throws ex-info with :type ::unknown-ticker when a ticker cannot be resolved."
  [ticker-or-cik]
  (core/edgar-get (core/cik-url (company-cik ticker-or-cik))))

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
     :country (:country addr)
     :country-code (:countryCode addr)
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
    {:cik (core/pad-cik (:cik raw))
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

;;; ---------------------------------------------------------------------------
;;; Company search via the EDGAR entity index (the company-name typeahead
;;; behind efts.sec.gov/LATEST/search-index?keysTyped=...). Each hit is one
;;; entity: {:_id "320193" :_source {:entity "Apple Inc. (AAPL)"
;;; :tickers "AAPL" ...}}. At most 10 hits are returned per query and
;;; from= paging is ignored by the endpoint.
;;; ---------------------------------------------------------------------------

(defn- shape-search-hit
  "Shape one entity-index hit. The display name carries the tickers in a
   trailing parenthetical — \"Alphabet Inc. (GOOG, GOOGL)\" — which is
   stripped from :name only when the hit actually has tickers, so names
   such as \"ACME (HOLDINGS) INC\" stay intact."
  [hit]
  (let [{:keys [entity tickers]} (:_source hit)
        cik (:_id hit)
        tickers (if (str/blank? tickers) [] (str/split (str/trim tickers) #"\s*,\s*"))]
    (when (and (some? cik) (re-matches #"\d+" (str cik)) (not (str/blank? entity)))
      {:cik (core/pad-cik cik)
       :name (str/trim (if (seq tickers)
                         (str/replace entity #"\s*\([^()]*\)\s*$" "")
                         entity))
       :tickers tickers})))

(defn search-companies
  "Search EDGAR entities (companies, funds, individual filers) by name.
   Uses the EDGAR company-name index — the typeahead behind the SEC's
   company search box — so only entities whose CURRENT name matches the
   query are returned, best match first. Ticker and CIK queries match too.

   Returns a vector of maps:
     :cik     - zero-padded 10-digit CIK
     :name    - current entity name, ticker suffix removed
     :tickers - vector of ticker strings (empty for entities without one)

   Options:
     :limit - max results (default 10; the SEC endpoint returns at most 10)"
  [query & {:keys [limit] :or {limit 10}}]
  (let [resp (core/edgar-get (str core/efts-url "?keysTyped=" (core/url-encode query)))]
    (->> (get-in resp [:hits :hits])
         (keep shape-search-hit)
         (reduce (fn [acc m]
                   (if (some #(= (:cik m) (:cik %)) acc) acc (conj acc m)))
                 [])
         (take limit)
         vec)))
