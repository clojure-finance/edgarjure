(ns edgar.xbrl
  (:require [edgar.core :as core]
            [tech.v3.dataset :as ds]))

;;; ---------------------------------------------------------------------------
;;; Company Facts — SEC pre-parsed XBRL endpoint
;;; https://data.sec.gov/api/xbrl/companyfacts/CIK##########.json
;;;
;;; Structure:
;;;   {:cik  ...
;;;    :entityName "..."
;;;    :facts {:us-gaap {:Assets {:label "..."
;;;                               :description "..."
;;;                               :units {:USD [{:end "2023-09-30"
;;;                                              :val 352583000000
;;;                                              :accn "..."
;;;                                              :fy 2023
;;;                                              :fp "FY"
;;;                                              :form "10-K"
;;;                                              :filed "2023-11-03"
;;;                                              :frame "CY2023Q3I"}
;;;                                             ...]}}}}
;;; ---------------------------------------------------------------------------

(defn get-company-facts
  "Fetch pre-parsed XBRL company facts from SEC data endpoint.
   Returns the raw response map."
  [cik]
  (core/edgar-get (core/facts-endpoint cik)))

(defn- key-str
  "The full text of a JSON object key parsed as a keyword. A unit key such
   as \"USD/shares\" parses as a namespaced keyword, so (name k) alone would
   drop the \"USD/\" part."
  [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn- flatten-facts
  "Flatten the nested facts structure into a seq of maps.
   Each map has :taxonomy :concept :label :description :unit :start (duration
   facts only) :end :val :accn :fy :fp :form :filed :frame."
  [facts]
  (for [[taxonomy concepts] facts
        [concept details] concepts
        [unit observations] (:units details)
        obs observations]
    (assoc obs
           :taxonomy (key-str taxonomy)
           :concept (key-str concept)
           :label (get details :label "")
           :description (get details :description "")
           :unit (key-str unit))))

(def ^:private facts-columns
  [:taxonomy :concept :label :description :unit :start :end :val
   :accn :fy :fp :form :filed :frame])

(defn- concept-set
  "Normalise a :concept option (a string or a collection of strings) to a
   set; nil stays nil."
  [concept]
  (when concept
    (if (string? concept) #{concept} (set concept))))

(defn facts->dataset
  "Convert company facts map to a tech.ml.dataset.
   Returns a dataset with columns, in this order:
   taxonomy concept label description unit start end val accn fy fp form
   filed frame (start only when the company has duration facts). :unit is
   the full SEC unit string, e.g. \"USD\", \"shares\", \"USD/shares\"."
  [facts-map]
  (let [rows (->> (get-in facts-map [:facts])
                  flatten-facts
                  (mapv #(-> %
                             (update :end str)
                             (update :filed str)
                             (update :frame (fnil str "")))))
        d (ds/->dataset rows {:dataset-name (:entityName facts-map)})]
    (if (seq rows)
      (let [present (set (ds/column-names d))
            lead (filterv present facts-columns)]
        (ds/select-columns d (into lead (remove (set lead)) (ds/column-names d))))
      d)))

(defn get-facts-dataset
  "Fetch company facts for a CIK and return as a tech.ml.dataset.
   Options:
     :concept - string or set of strings to filter concepts
     :form    - \"10-K\" | \"10-Q\" to filter by form type
     :sort    - :desc (default) or :asc — sort :end column descending/ascending
                Pass nil to skip sorting."
  [cik & {:keys [concept form sort] :or {sort :desc}}]
  (let [concepts (concept-set concept)
        ds (-> cik get-company-facts facts->dataset)]
    (cond-> ds
      concepts (ds/filter-column :concept #(contains? concepts %))
      form (ds/filter-column :form #(= % form))
      (= sort :desc) (ds/sort-by #(get % :end) #(compare %2 %1))
      (= sort :asc) (ds/sort-by #(get % :end) compare))))

;;; ---------------------------------------------------------------------------
;;; Concept frames — cross-sectional data for a given concept + period
;;; https://data.sec.gov/api/xbrl/frames/us-gaap/{concept}/{unit}/CY{year}Q{q}I.json
;;; ---------------------------------------------------------------------------

(defn get-concepts
  "Return a dataset of all XBRL concepts available for a CIK.
   Columns: :taxonomy :concept :label :description
   Each row is a distinct concept (one row per concept, not per observation)."
  [cik]
  (let [facts-map (get-company-facts cik)
        rows (for [[taxonomy concepts] (:facts facts-map)
                   [concept details] concepts]
               {:taxonomy (name taxonomy)
                :concept (name concept)
                :label (get details :label "")
                :description (get details :description "")})]
    (ds/->dataset rows {:dataset-name (str (:entityName facts-map) " concepts")})))

(defn concept-frame-url
  "Build the frames endpoint URL for a cross-sectional concept fetch."
  [taxonomy concept unit frame]
  (str core/data-url "/api/xbrl/frames/"
       taxonomy "/" concept "/" unit "/" frame ".json"))

(defn- shape-frame-row
  "Normalise one frames :data entry. The SEC returns :cik as a plain number;
   pad it to the 10-digit string used everywhere else in the library."
  [row]
  (if (:cik row)
    (update row :cik
            #(cond
               (number? %) (format "%010d" (long %))
               (and (string? %) (re-matches #"\d+" %)) (format "%010d" (Long/parseLong %))
               :else %))
    row))

(defn get-concept-frame
  "Fetch cross-sectional data for a concept across all companies for a period.
   frame examples: \"CY2023Q4I\" (instant) or \"CY2023Q4\" (duration)
   Options:
     :taxonomy - default \"us-gaap\"
     :unit     - default \"USD\"
   Returns a dataset sorted by :val descending with columns
   :accn :cik :entityName :loc :end :val (:start for duration frames).
   :cik is zero-padded to the 10-digit string form used across the library."
  [concept frame & {:keys [taxonomy unit] :or {taxonomy "us-gaap" unit "USD"}}]
  (let [data (:data (core/edgar-get (concept-frame-url taxonomy concept unit frame)))
        name-opt {:dataset-name (str concept "/" frame)}]
    (if (empty? data)
      (ds/->dataset {:accn [] :cik [] :entityName [] :loc [] :end [] :val []}
                    name-opt)
      (-> (ds/->dataset (map shape-frame-row data) name-opt)
          (ds/sort-by-column :val #(compare %2 %1))))))
