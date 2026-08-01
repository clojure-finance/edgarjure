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

   Two access styles:

   1. Managed (what the :compustat statement view uses): enable the local
      quarter cache and let per-filing contexts stream what they need —
        (fsds/enable-cache!)                        ; or (e/enable-fsds!)
        (fsds/annual-filing-context \"1018724\" \"2026-02-06\")
        ;=> {:adsh ... :placement {tag #{\"IS\" ...}} :is-extension-values [...]}

   2. Manual table access for ad-hoc analysis:
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
            [clojure.string :as str]
            [tech.v3.dataset :as ds])
  (:import [java.util.zip ZipFile]
           [java.io BufferedReader InputStreamReader FileOutputStream]
           [java.nio.charset StandardCharsets]
           [java.time LocalDate]))

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

(defn- placement-map
  "Fold pre.txt rows ({:tag :stmt ...}) into {tag #{statement-codes}}."
  [pre-rows]
  (reduce (fn [m {:keys [tag stmt]}]
            (update m tag (fnil conj #{}) stmt))
          {} pre-rows))

(defn statement-placement
  "Map of tag name -> set of statement codes for one submission (adsh).
   Example: {\"DepreciationDepletionAndAmortization\" #{\"CF\"} ...}
   A tag mapped to #{\"CF\"} but not \"IS\" is presented on the cash flow
   statement only — the signal the Compustat reclassification rules need to
   decide whether D&A is embedded in the income statement expense lines."
  [pre-ds adsh]
  (placement-map
   (ds/rows (ds/filter-column pre-ds :adsh #(= % adsh)) {:nil-missing? true})))

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

;;; ---------------------------------------------------------------------------
;;; Local quarter cache + per-filing streaming access (roadmap: FSDS wiring)
;;;
;;; Loading whole FSDS tables is expensive (num.txt has millions of rows).
;;; The statement views need only ONE filing's rows, and every FSDS table is
;;; adsh-prefixed, so the functions below stream the zip entries line by line
;;; and keep just the matching rows. Quarter zips live in an opt-in local
;;; cache (~/.edgarjure/fsds by default) shared by all companies.
;;; ---------------------------------------------------------------------------

(def default-cache-dir
  (str (System/getProperty "user.home") "/.edgarjure/fsds"))

(defonce ^:private cache-config (atom nil))

(defn enable-cache!
  "Enable the local FSDS quarter cache (downloads on first use per quarter).
   Options: :dir (default ~/.edgarjure/fsds). Returns the active config.
   When enabled, the income statement's :view :compustat augments its
   reclassification rules with FSDS statement placement and extension-tag
   operands for 10-K periods (10-Q queries build 10-K rows internally for
   Q4/LTM derivation, so they can trigger quarter downloads too)."
  [& {:keys [dir]}]
  (reset! cache-config {:dir (or dir default-cache-dir)}))

(defn disable-cache!
  "Disable the local FSDS quarter cache. Cached files stay on disk."
  []
  (reset! cache-config nil))

(defn cache-enabled? [] (some? @cache-config))

(defn- active-dir [] (:dir @cache-config default-cache-dir))

(defn quarter-of-date
  "The FSDS [year quarter] whose dataset contains filings RECEIVED on date
   (ISO string or LocalDate)."
  [d]
  (let [ld (if (instance? LocalDate d) d (LocalDate/parse (str d)))]
    [(.getYear ld) (inc (quot (dec (.getMonthValue ld)) 3))]))

(defonce ^:private missing-quarters (atom #{}))

(defn cached-quarter!
  "Path of the year/quarter FSDS zip in the local cache, downloading it on
   first use. Returns nil when the quarter isn't published (404 remembered
   for the session) or the cache is disabled and no :dir is given. Transient
   failures (timeouts, 5xx, disk errors) rethrow WITHOUT marking the quarter
   missing, so a later call can retry."
  [year quarter & {:keys [dir]}]
  (let [dir (or dir (when (cache-enabled?) (active-dir)))]
    (when (and dir (not (@missing-quarters [year quarter])))
      (try
        (download-quarter! year quarter dir)
        (catch Exception e
          (if (re-find #"404" (str (ex-message e)))
            (do (swap! missing-quarters conj [year quarter]) nil)
            (throw e)))))))

(defn- zip-entry-line-seq*
  "Call f with the lazy line seq of one zip entry (FSDS tables are
   ISO-8859-1; tab-delimited, header first)."
  [zip-path entry-name f]
  (with-open [zf (ZipFile. (str zip-path))]
    (let [entry (.getEntry zf entry-name)]
      (when-not entry
        (throw (ex-info (str entry-name " not found in " zip-path)
                        {:type ::missing-entry :zip (str zip-path) :entry entry-name})))
      (with-open [rdr (BufferedReader.
                       (InputStreamReader. (.getInputStream zf entry)
                                           StandardCharsets/ISO_8859_1))]
        (f (line-seq rdr))))))

(defn- rows-where
  "Stream one FSDS table out of a quarter zip, keeping rows for which
   (pred fields-vector) is true. Returns a vector of keywordized maps."
  [zip-path table pred]
  (zip-entry-line-seq*
   zip-path (str (name table) ".txt")
   (fn [lines]
     (let [header (mapv keyword (str/split (first lines) #"\t" -1))]
       (into []
             (comp (map #(str/split % #"\t" -1))
                   (filter pred)
                   (map #(zipmap header %)))
             (rest lines))))))

(defn filing-rows
  "One filing's :pre or :num rows streamed from a quarter zip (all FSDS
   tables are adsh-first, so this never loads the full table). All values
   are strings; callers parse what they need."
  [zip-path table adsh]
  (rows-where zip-path table #(= (first %) adsh)))

(defn submission-row
  "The sub.txt row (keywordized string map) of a company's latest submission
   of the given form in a quarter zip, or nil. cik may be padded or not."
  [zip-path cik & {:keys [form]}]
  (let [ciks (str (Long/parseLong (str cik)))]
    (->> (rows-where zip-path :sub #(= (second %) ciks))
         (filter #(or (nil? form) (= form (:form %))))
         (sort-by :filed #(compare %2 %1))
         first)))

(defn- parse-double* [s] (when-not (str/blank? s) (Double/parseDouble s)))

(defn- ddate->local-date [s]
  (when (and s (= 8 (count s)))
    (LocalDate/parse (str (subs s 0 4) "-" (subs s 4 6) "-" (subs s 6 8)))))

(defn- standard-version?
  "True for us-gaap/dei/srt/ifrs release versions; extension tags carry a
   filer-specific version instead."
  [version]
  (boolean (re-matches #"(?:us-gaap|dei|srt|ifrs(?:-full)?|invest|country|currency)/\d{4}(?:q\d)?" (str version))))

(defonce ^:private context-cache (atom {}))

(defn annual-filing-context
  "FSDS context of the 10-K a company filed in the quarter containing
   filed-date:
     {:adsh      accession number
      :placement {tag #{\"IS\" \"CF\" ...}}   (statement placement, all tags)
      :is-extension-values [{:tag :label :value :end}]  (annual values of
                            extension tags presented on the income statement;
                            :end is the FSDS month-end period date)}
   Returns nil when the quarter isn't cached/published or the filing isn't
   found. Results are cached per [cik year quarter] for the session."
  [cik filed-date & {:keys [dir]}]
  (let [[y q] (quarter-of-date filed-date)
        ck [(str cik) y q]]
    (if-let [hit (find @context-cache ck)]
      (val hit)
      (let [ctx
            (when-let [zip (cached-quarter! y q :dir dir)]
              (when-let [subrow (submission-row zip cik :form "10-K")]
                (let [adsh (:adsh subrow)
                      pre (filing-rows zip :pre adsh)
                      placement (placement-map pre)
                      ext-is-tags (->> pre
                                       (filter #(and (= "IS" (:stmt %))
                                                     (not (standard-version? (:version %)))))
                                       (map (juxt :tag :plabel))
                                       (into {}))
                      ext-values (when (seq ext-is-tags)
                                   (->> (filing-rows zip :num adsh)
                                        (filter #(and (contains? ext-is-tags (:tag %))
                                                      (= "4" (:qtrs %))
                                                      (= "USD" (:uom %))
                                                      ;; consolidated only: no
                                                      ;; segment/coreg breakdown
                                                      (str/blank? (or (:segments %) ""))
                                                      (str/blank? (or (:coreg %) ""))))
                                        (keep (fn [{:keys [tag ddate value]}]
                                                (when-let [v (parse-double* value)]
                                                  {:tag tag
                                                   :label (get ext-is-tags tag)
                                                   :value v
                                                   :end (ddate->local-date ddate)})))
                                        (distinct)
                                        vec))]
                  {:adsh adsh
                   :placement placement
                   :is-extension-values (or ext-values [])})))]
        (swap! context-cache assoc ck ctx)
        ctx))))
