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
     (def zip (fsds/download-quarter! 2026 1 \"/data/fsds\"))
     (def sub (fsds/load-table zip :sub))
     (def pre (fsds/load-table zip :pre))
     (def num (fsds/load-table zip :num))     ; millions of rows — needs heap

     ;; Find a company's filing in the quarter:
     (def adsh (-> (fsds/find-submissions sub :cik \"1018724\" :form \"10-K\")
                   (ds/column :adsh) first))

     ;; Income statement exactly as presented — extension tags included
     ;; (their :version is the filing's adsh instead of a us-gaap release):
     (fsds/presentation pre adsh :stmt \"IS\")

     ;; Which statement does each tag sit on? The Compustat-reclass guard
     ;; question: D&A on \"CF\" only means it is embedded in the income
     ;; statement expense lines (strip it); D&A presented on \"IS\" means
     ;; the expense lines already exclude it:
     (get (fsds/statement-placement pre adsh)
          \"DepreciationDepletionAndAmortization\")   ;=> #{\"CF\"} for AMZN

     ;; Values for extension tags the companyfacts API cannot see (e.g.
     ;; AMZN's Fulfillment / Technology and infrastructure, which Compustat
     ;; folds into XSGA/XRD — FY2025 TechnologyAndInfrastructureExpense
     ;; equals Compustat XRD exactly):
     (fsds/facts-for num adsh :qtrs 4 :tag \"FulfillmentExpense\")"
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

;;; ---------------------------------------------------------------------------
;;; Statement placement and extension tags (roadmap: FSDS-based
;;; standardization)
;;;
;;; pre.txt records which financial statement every tag was presented on
;;; (stmt: BS balance sheet, IS income statement, CF cash flow, EQ equity,
;;; CI comprehensive income, UN unclassifiable, CP cover page) and in what
;;; order — for STANDARD AND EXTENSION tags alike. This answers questions
;;; the companyfacts API cannot:
;;;   - does this filer present D&A as its own income statement line?
;;;     (if yes, its reported COGS/SG&A likely already exclude D&A and
;;;     Compustat-style D&A stripping would double-count)
;;;   - which extension tags carry income statement lines, and under what
;;;     labels? (e.g. AMZN's Fulfillment / Technology and content, which
;;;     Compustat folds into XSGA/XRD)
;;; num.txt then carries the values for those extension tags.
;;; ---------------------------------------------------------------------------

(defn find-submissions
  "Filter a loaded :sub table to a company's submissions.
   Options:
     :cik  - company CIK (string or number; FSDS stores unpadded numbers)
     :form - form type string, e.g. \"10-K\"
   Returns a dataset sorted by :filed descending (most recent first)."
  [sub-ds & {:keys [cik form]}]
  (let [ciknum (when cik (Long/parseLong (str cik)))]
    (cond-> sub-ds
      ciknum (ds/filter-column :cik #(= (long %) ciknum))
      form (ds/filter-column :form #(= % form))
      true (ds/sort-by-column :filed (fn [a b] (compare b a))))))

(defn presentation
  "Presentation rows for one submission (adsh), sorted in statement order.
   Options:
     :stmt - restrict to one statement code, e.g. \"IS\" \"BS\" \"CF\"
   Returns a dataset with :stmt :report :line :tag :version :plabel ...;
   extension tags have the filer's own :version (e.g. \"amzn/2025\") rather
   than a us-gaap release."
  [pre-ds adsh & {:keys [stmt]}]
  (cond-> (ds/filter-column pre-ds :adsh #(= % adsh))
    stmt (ds/filter-column :stmt #(= % stmt))
    true (ds/sort-by (fn [row] [(:report row) (:line row)])
                     (fn [a b] (compare a b)))))

(defn statement-placement
  "Map of tag name -> set of statement codes for one submission (adsh).
   Example: {\"DepreciationDepletionAndAmortization\" #{\"CF\"} ...}
   A tag mapped to #{\"CF\"} but not \"IS\" is presented on the cash flow
   statement only — the signal the Compustat reclassification rules need to
   decide whether D&A is embedded in the income statement expense lines."
  [pre-ds adsh]
  (->> (ds/rows (ds/filter-column pre-ds :adsh #(= % adsh)) {:nil-missing? true})
       (reduce (fn [m {:keys [tag stmt]}]
                 (update m tag (fnil conj #{}) stmt))
               {})))

(defn facts-for
  "Numeric facts (num rows) for one submission (adsh), extension tags
   included.
   Options:
     :qtrs - restrict to a window length in quarters (0 = instant,
             1 = quarterly flow, 4 = annual flow)
     :tag  - restrict to one tag name
   Returns a dataset with :tag :version :ddate :qtrs :uom :value ..."
  [num-ds adsh & {:keys [qtrs tag]}]
  (cond-> (ds/filter-column num-ds :adsh #(= % adsh))
    qtrs (ds/filter-column :qtrs #(= (long %) (long qtrs)))
    tag (ds/filter-column :tag #(= % tag))))
