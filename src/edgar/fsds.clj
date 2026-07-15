(ns edgar.fsds
  "SEC Financial Statement Data Sets (DERA) access.
   https://www.sec.gov/dera/data/financial-statement-data-sets

   Quarterly ZIPs containing four tab-delimited tables:
     sub — one row per submission: adsh, cik, name, sic, form, period,
           fy, fp, filed, and filer metadata
     num — one row per numeric fact: adsh, tag, version, ddate, qtrs,
           uom, segments, value
     pre — statement placement: adsh, stmt (BS/IS/CF/EQ/CI), report, line,
           tag — i.e. which statement each tag appeared on and in what order
     tag — tag metadata: version, custom flag, datatype, iord (instant/
           duration), crdr (credit/debit), label, documentation

   Why this matters for standardization: unlike the companyfacts API, these
   sets include company extension tags and statement placement — the two
   ingredients Compustat-style cross-company standardization needs most.
   One bulk download covers every filer for a quarter, instead of one HTTP
   call per company.

   Usage:
     (require '[edgar.fsds :as fsds])
     (def zip (fsds/download-quarter! 2024 1 \"/data/fsds\"))
     (def sub (fsds/load-table zip :sub))
     (def num (fsds/load-table zip :num))
     (def pre (fsds/load-table zip :pre))

     ;; e.g. income statement placement for one filing:
     ;; (-> pre
     ;;     (ds/filter-column :adsh #(= % \"0000320193-24-000006\"))
     ;;     (ds/filter-column :stmt #(= % \"IS\")))"
  (:require [edgar.core :as core]
            [babashka.fs :as fs]
            [tech.v3.dataset :as ds])
  (:import [java.util.zip ZipFile]
           [java.io FileOutputStream]))

(defn quarter-url
  "URL of the FSDS zip for a year/quarter, e.g. (quarter-url 2024 1)."
  [year quarter]
  (str "https://www.sec.gov/files/dera/data/financial-statement-data-sets/"
       year "q" quarter ".zip"))

(defn download-quarter!
  "Download the FSDS zip for year/quarter into dir.
   Skips the download when the file already exists unless :force? is true.
   Returns the path of the zip file.

   Note: these files are large (tens to hundreds of MB)."
  [year quarter dir & {:keys [force?]}]
  (let [out-file (fs/path dir (str year "q" quarter ".zip"))]
    (when (or force? (not (fs/exists? out-file)))
      (fs/create-dirs dir)
      (let [bytes (core/edgar-get-bytes (quarter-url year quarter))]
        (with-open [out (FileOutputStream. (str out-file))]
          (.write out ^bytes bytes))))
    (str out-file)))

(def ^:private table-names #{:sub :num :pre :tag})

(defn load-table
  "Load one FSDS table from a downloaded zip as a tech.ml.dataset.
   table: :sub | :num | :pre | :tag
   Columns are keywordized. num.txt for a busy quarter has millions of rows —
   loading it needs a correspondingly sized heap."
  [zip-path table]
  (when-not (table-names table)
    (throw (ex-info (str "Unknown FSDS table: " table " (expected :sub :num :pre :tag)")
                    {:type ::unknown-table :table table})))
  (with-open [zf (ZipFile. (str zip-path))]
    (let [entry-name (str (name table) ".txt")
          entry (.getEntry zf entry-name)]
      (when-not entry
        (throw (ex-info (str entry-name " not found in " zip-path)
                        {:type ::missing-entry :zip (str zip-path) :entry entry-name})))
      (with-open [in (.getInputStream zf entry)]
        (ds/->dataset in {:file-type :tsv
                          :key-fn keyword
                          :dataset-name (str (fs/file-name zip-path) "/" entry-name)})))))
