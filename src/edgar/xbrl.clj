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
   :cik is zero-padded to the 10-digit string form used across the library.

   The SEC frames endpoint returns :data as a sequence of maps; a legacy
   :columns + vector-of-vectors shape is also accepted for robustness."
  [concept frame & {:keys [taxonomy unit] :or {taxonomy "us-gaap" unit "USD"}}]
  (let [resp (core/edgar-get (concept-frame-url taxonomy concept unit frame))
        raw-cols (:columns resp)
        data (:data resp)
        name-opt {:dataset-name (str concept "/" frame)}]
    (if (or (nil? data) (empty? data))
      (ds/->dataset {:accn [] :cik [] :entityName [] :loc [] :end [] :val []}
                    name-opt)
      (let [rows (if (map? (first data))
                   (map shape-frame-row data)
                   (let [cols (if (seq raw-cols)
                                (mapv keyword raw-cols)
                                (let [n (count (first data))]
                                  (if (= 6 n)
                                    [:accn :cik :entityName :loc :end :val]
                                    (mapv #(keyword (str "col" %)) (range n)))))]
                     (map #(shape-frame-row (zipmap cols %)) data)))]
        (let [result (ds/->dataset rows name-opt)]
          (if (some #{:val} (ds/column-names result))
            (ds/sort-by-column result :val #(compare %2 %1))
            result))))))
