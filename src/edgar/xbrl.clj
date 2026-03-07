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
   Each map has :taxonomy :concept :unit :end :val :accn :fy :fp :form :filed :frame."
  [facts]
  (for [[taxonomy concepts] facts
        [concept details] concepts
        [unit observations] (:units details)
        obs observations]
    (assoc obs
           :taxonomy (name taxonomy)
           :concept (name concept)
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
  "Fetch company facts for a CIK and return as a tech.ml.dataset."
  [cik]
  (-> cik
      get-company-facts
      facts->dataset))

(defn facts-for-concept
  "Filter a facts dataset to a single concept e.g. \"Assets\" \"Revenues\".
   Returns a dataset sorted by :end descending."
  [ds concept]
  (-> ds
      (ds/filter-column :concept #(= % concept))
      (ds/reverse-rows)))

(defn annual-facts
  "Filter a facts dataset to annual (10-K) observations only."
  [ds]
  (ds/filter-column ds :form #(= % "10-K")))

(defn quarterly-facts
  "Filter a facts dataset to quarterly (10-Q) observations only."
  [ds]
  (ds/filter-column ds :form #(= % "10-Q")))

;;; ---------------------------------------------------------------------------
;;; Concept frames — cross-sectional data for a given concept + period
;;; https://data.sec.gov/api/xbrl/frames/us-gaap/{concept}/{unit}/CY{year}Q{q}I.json
;;; ---------------------------------------------------------------------------

(defn concept-frame-url
  "Build the frames endpoint URL for a cross-sectional concept fetch."
  [taxonomy concept unit frame]
  (str core/data-url "/api/xbrl/frames/"
       taxonomy "/" concept "/" unit "/" frame ".json"))

(defn get-concept-frame
  "Fetch cross-sectional data for a concept across all companies for a period.
   frame examples: \"CY2023Q4I\" (instant) or \"CY2023Q4\" (duration)
   Returns a dataset with columns: accn cik entityName loc end val."
  [taxonomy concept unit frame]
  (let [resp (core/edgar-get (concept-frame-url taxonomy concept unit frame))]
    (ds/->dataset (:data resp)
                  {:column-names (mapv keyword (:columns resp))
                   :dataset-name (str concept "/" frame)})))
