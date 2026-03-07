(ns edgar.download
  (:require [edgar.core :as core]
            [edgar.filings :as filings]
            [edgar.filing :as filing]
            [clojure.string :as str]
            [babashka.fs :as fs]))

;;; ---------------------------------------------------------------------------
;;; Single-company bulk downloader (sec-edgar-downloader analog)
;;; ---------------------------------------------------------------------------

(defn download-filings!
  "Download filings for a ticker or CIK to a local directory.
   Options:
     :form            - form type e.g. \"10-K\" (required)
     :limit           - max number of filings (default all)
     :start-date      - \"YYYY-MM-DD\"
     :end-date        - \"YYYY-MM-DD\"
     :download-all?   - download all attachments, not just primary doc (default false)
   Returns a seq of saved file paths."
  [ticker-or-cik dir & {:keys [form limit start-date end-date download-all?]
                        :or {download-all? false}}]
  (let [fs-list (filings/get-filings ticker-or-cik
                                     :form form
                                     :start-date start-date
                                     :end-date end-date
                                     :limit limit)]
    (doall
     (for [f fs-list]
       (try
         (if download-all?
           (filing/filing-save-all! f dir)
           (filing/filing-save! f dir))
         (catch Exception e
           {:error (.getMessage e)
            :accession-number (:accessionNumber f)}))))))

;;; ---------------------------------------------------------------------------
;;; Batch downloader — multiple tickers (secedgar analog)
;;; Uses pmap for parallelism (bounded by rate-limiter in edgar.core)
;;; ---------------------------------------------------------------------------

(defn download-batch!
  "Download filings for a seq of tickers/CIKs to a directory.
   Options same as download-filings! plus :parallelism (default 4).
   Returns a map of {ticker -> [saved-paths]}"
  [tickers dir & {:keys [form limit start-date end-date parallelism]
                  :or {parallelism 4}}]
  (let [download-one (fn [ticker]
                       [ticker
                        (download-filings! ticker dir
                                           :form form
                                           :limit limit
                                           :start-date start-date
                                           :end-date end-date)])]
    (into {}
          (pmap download-one tickers))))

;;; ---------------------------------------------------------------------------
;;; Index downloader — download raw quarterly full-index files
;;; ---------------------------------------------------------------------------

(defn download-index!
  "Download all quarterly index files for a year range to a directory.
   Returns a seq of saved file paths.
   type: \"company\" | \"form\" | \"master\" (default \"company\")"
  [start-year end-year dir & {:keys [type quarters]
                              :or {type "company" quarters [1 2 3 4]}}]
  (doall
   (for [year (range start-year (inc end-year))
         q quarters
         :let [url (filings/full-index-url year q type)
               out-dir (fs/path dir (str year) (str "QTR" q))
               out-file (fs/path out-dir (str type ".idx"))]]
     (do
       (fs/create-dirs out-dir)
       (spit (str out-file)
             (core/edgar-get url :raw? true))
       (str out-file)))))
