(ns edgar.download
  (:require [edgar.core :as core]
            [edgar.filings :as filings]
            [edgar.filing :as filing]
            [clojure.string :as str]
            [babashka.fs :as fs]))

;;; ---------------------------------------------------------------------------
;;; Result envelope helpers
;;; ---------------------------------------------------------------------------

(defn- ok [path] {:status :ok :path path})
(defn- err [accession type msg] {:status :error :accession-number accession :type type :message msg})

;;; ---------------------------------------------------------------------------
;;; Single-company bulk downloader (sec-edgar-downloader analog)
;;; ---------------------------------------------------------------------------

(defn- output-path
  "Return the output file path for a filing's primary document."
  [f dir]
  (let [form (:form f)
        cik (:cik f)
        acc (:accessionNumber f)
        idx (filing/filing-index f)
        primary (filing/primary-doc idx)]
    (when primary
      (str (fs/path dir form cik acc (:name primary))))))

(defn download-filings!
  "Download filings for a ticker or CIK to a local directory.
   Options:
     :form            - form type e.g. \"10-K\" (required)
     :limit           - max number of filings (default all)
     :start-date      - \"YYYY-MM-DD\"
     :end-date        - \"YYYY-MM-DD\"
     :download-all?   - download all attachments, not just primary doc (default false)
     :skip-existing?  - skip filings whose output file already exists (default false)
   Returns a seq of result maps per filing:
     {:status :ok    :path  \"...\"}              ; primary-doc-only download
     {:status :ok    :paths [\"...\" \"...\"]}     ; download-all? download
     {:status :skipped :accession-number \"...\"}
     {:status :error   :accession-number \"...\" :type ... :message \"...\"}"
  [ticker-or-cik dir & {:keys [form limit start-date end-date download-all? skip-existing?]
                        :or {download-all? false skip-existing? false}}]
  (let [fs-list (filings/get-filings ticker-or-cik
                                     :form form
                                     :start-date start-date
                                     :end-date end-date
                                     :limit limit)]
    (doall
     (for [f fs-list]
       (try
         (if (and skip-existing?
                  (when-let [p (output-path f dir)] (fs/exists? (fs/path p))))
           {:status :skipped :accession-number (:accessionNumber f)}
           (if download-all?
             {:status :ok :paths (filing/filing-save-all! f dir)}
             (ok (filing/filing-save! f dir))))
         (catch Exception e
           (let [data (ex-data e)
                 type (or (:type data) :exception)]
             (err (:accessionNumber f) type (.getMessage e)))))))))

;;; ---------------------------------------------------------------------------
;;; Batch downloader — multiple tickers (secedgar analog)
;;; ---------------------------------------------------------------------------

(defn download-batch!
  "Download filings for a seq of tickers/CIKs to a directory.
   Options same as download-filings! plus:
     :parallelism - number of concurrent downloads (default 4)
   Returns a map of {ticker -> [result-maps]}"
  [tickers dir & {:keys [form limit start-date end-date parallelism download-all? skip-existing?]
                  :or {parallelism 4 download-all? false skip-existing? false}}]
  (let [download-one (fn [ticker]
                       [ticker
                        (download-filings! ticker dir
                                           :form form
                                           :limit limit
                                           :start-date start-date
                                           :end-date end-date
                                           :download-all? download-all?
                                           :skip-existing? skip-existing?)])
        partitions (partition-all parallelism tickers)]
    (into {}
          (mapcat #(pmap download-one %) partitions))))

;;; ---------------------------------------------------------------------------
;;; Index downloader — download raw quarterly full-index files
;;; ---------------------------------------------------------------------------

(defn download-index!
  "Download all quarterly index files for a year range to a directory.
   Returns a seq of result maps {:status :ok :path ...} or {:status :error ...}.
   type: \"company\" | \"form\" | \"master\" (default \"company\")"
  [start-year end-year dir & {:keys [type quarters]
                              :or {type "company" quarters [1 2 3 4]}}]
  (doall
   (for [year (range start-year (inc end-year))
         q quarters
         :let [url (filings/full-index-url year q type)
               out-dir (fs/path dir (str year) (str "QTR" q))
               out-file (fs/path out-dir (str type ".idx"))]]
     (try
       (fs/create-dirs out-dir)
       (spit (str out-file) (core/edgar-get url :raw? true))
       (ok (str out-file))
       (catch Exception e
         (err (str year "/QTR" q) :exception (.getMessage e)))))))
