(ns edgar.xbrl
  (:require [edgar.core :as core]
            [tech.v3.dataset :as ds]
            [tech.v3.datatype :as dtype]))

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

(defn- flatten-facts
  "Flatten the nested facts structure into a seq of maps.
   Each map has :taxonomy :concept :label :description :unit :end :val :accn :fy :fp :form :filed :frame."
  [facts]
  (for [[taxonomy concepts] facts
        [concept details] concepts
        [unit observations] (:units details)
        obs observations]
    (assoc obs
           :taxonomy (name taxonomy)
           :concept (name concept)
           :label (get details :label "")
           :description (get details :description "")
           :unit (name unit))))

(defn facts->dataset
  "Convert company facts map to a tech.ml.dataset.
   Returns a dataset with columns:
   taxonomy concept unit end val accn fy fp form filed frame"
  [facts-map]
  (let [rows (->> (get-in facts-map [:facts])
                  flatten-facts
                  (map #(-> %
                            (update :end str)
                            (update :filed str)
                            (update :frame (fnil str "")))))]
    (ds/->dataset rows {:dataset-name (:entityName facts-map)})))

(defn get-facts-dataset
  "Fetch company facts for a CIK and return as a tech.ml.dataset.
   Options:
     :concept - string or set of strings to filter concepts
     :form    - \"10-K\" | \"10-Q\" to filter by form type
     :sort    - :desc (default) or :asc — sort :end column descending/ascending
                Pass nil to skip sorting."
  [cik & {:keys [concept form sort] :or {sort :desc}}]
  (let [concept-set (when concept
                      (if (string? concept) #{concept} (set concept)))
        ds (-> cik get-company-facts facts->dataset)]
    (cond-> ds
      concept-set (ds/filter-column :concept #(contains? concept-set %))
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

(defn get-concept-frame
  "Fetch cross-sectional data for a concept across all companies for a period.
   frame examples: \"CY2023Q4I\" (instant) or \"CY2023Q4\" (duration)
   Options:
     :taxonomy - default \"us-gaap\"
     :unit     - default \"USD\"
   Returns a dataset with columns: accn cik entityName loc end val."
  [concept frame & {:keys [taxonomy unit] :or {taxonomy "us-gaap" unit "USD"}}]
  (let [resp (core/edgar-get (concept-frame-url taxonomy concept unit frame))]
    (ds/->dataset (:data resp)
                  {:column-names (mapv keyword (:columns resp))
                   :dataset-name (str concept "/" frame)})))
