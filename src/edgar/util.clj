(ns edgar.util
  "Internal helpers shared by the statement, panel and validation namespaces:
   lenient date parsing, observation-window arithmetic, restatement
   deduplication and column-ordered dataset construction. Not part of the
   public API."
  (:require [tech.v3.dataset :as ds])
  (:import [java.time LocalDate]
           [java.time.temporal ChronoUnit]))

(defn parse-date
  "Parse an ISO date (string, LocalDate or anything whose str is ISO).
   Returns nil for nil, blank or unparseable input."
  ^LocalDate [d]
  (cond
    (nil? d) nil
    (instance? LocalDate d) d
    :else (let [s (str d)]
            (when-not (empty? s)
              (try (LocalDate/parse s) (catch Exception _ nil))))))

(defn days-between
  "Signed number of days from LocalDate a to LocalDate b."
  ^long [^LocalDate a ^LocalDate b]
  (.between ChronoUnit/DAYS a b))

(defn window-days
  "Length in days of a row's :start..:end window; -1 for instant rows (and
   for unparseable dates), so any duration window outranks them."
  ^long [row]
  (let [s (parse-date (:start row))
        e (parse-date (:end row))]
    (if (and s e) (days-between s e) -1)))

(defn duration-months
  "Classify a duration row's window as 3, 6, 9 or 12 months, or nil when the
   row is instant or does not resemble a whole number of fiscal quarters.
   Ranges are tolerant of 52/53-week fiscal calendars."
  [row]
  (let [days (window-days row)]
    (cond
      (<= 75 days 115) 3
      (<= 160 days 200) 6
      (<= 250 days 290) 9
      (<= 340 days 380) 12
      :else nil)))

(defn quarterly-window?
  "True for a 3-, 6- or 9-month window. A 10-K's facts carry such
   quarterly-footnote windows alongside its fiscal-year figures."
  [row]
  (contains? #{3 6 9} (duration-months row)))

(defn filed-on-or-before?
  "True when the row's :filed date is on or before as-of (ISO strings)."
  [as-of row]
  (not (pos? (compare (str (:filed row)) (str as-of)))))

(defn preferred-observation?
  "True when observation a should replace b as the value of a wide cell:
   the longer window wins; for the same window length the later :filed
   wins."
  [a b]
  (let [c (compare (window-days a) (window-days b))]
    (if (zero? c)
      (pos? (compare (str (:filed a)) (str (:filed b))))
      (pos? c))))

(defn latest-filed
  "Keep the most recently :filed row per (key-fn row) group (restatement
   deduplication; among equal :filed dates the later row wins). Returns a
   seq of rows in unspecified order."
  [key-fn rows]
  (->> rows
       (group-by key-fn)
       (map (fn [[_ group]]
              (reduce #(if (pos? (compare (str (:filed %1)) (str (:filed %2)))) %1 %2)
                      group)))))

(defn ordered-dataset
  "Build a dataset from row maps with columns in a deterministic order:
   the columns named in leading-cols first (those present in the rows), then
   every other key, ordered by rank-fn (default: (str key)). Avoids the
   hash-map key order ds/->dataset otherwise inherits from wide rows."
  ([rows leading-cols] (ordered-dataset rows leading-cols str nil))
  ([rows leading-cols rank-fn] (ordered-dataset rows leading-cols rank-fn nil))
  ([rows leading-cols rank-fn opts]
   (let [rows (vec rows)
         present (into #{} (mapcat keys) rows)
         lead (filterv present leading-cols)
         lead-set (set lead)
         others (sort-by rank-fn (remove lead-set present))
         d (if opts (ds/->dataset rows opts) (ds/->dataset rows))]
     (if (empty? rows)
       d
       (ds/select-columns d (into lead others))))))
