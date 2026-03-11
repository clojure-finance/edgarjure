(ns edgar.schema
  "Malli schemas and validation helpers for the edgarjure public API.

   Usage (in edgar.api):
     (schema/validate! ::filings-args {:ticker-or-cik toc :form form ...})

   All public validate! calls throw ex-info with :type ::invalid-args on failure.
   The error message includes the human-readable Malli explanation."
  (:require [malli.core :as m]
            [malli.error :as me]))

;;; ---------------------------------------------------------------------------
;;; Primitive schemas
;;; ---------------------------------------------------------------------------

(def NonBlankString
  [:and :string [:fn {:error/message "must be a non-blank string"}
                 #(not (clojure.string/blank? %))]])

(def TickerOrCIK
  [:and :string [:fn {:error/message "must be a non-blank ticker or CIK string"}
                 #(not (clojure.string/blank? %))]])

(def ISODate
  [:and :string [:re {:error/message "must be an ISO date string YYYY-MM-DD"}
                 #"^\d{4}-\d{2}-\d{2}$"]])

(def FormType
  [:and :string [:fn {:error/message "must be a non-blank form type string e.g. \"10-K\""}
                 #(not (clojure.string/blank? %))]])

(def ShapeKw
  [:enum {:error/message "must be :long or :wide"} :long :wide])

(def ConceptArg
  [:or
   NonBlankString
   [:sequential NonBlankString]])

(def AccessionNumber
  [:and :string [:re {:error/message "must be a dashed accession number XXXXXXXXXX-YY-ZZZZZZ"}
                 #"^\d{10}-\d{2}-\d{6}$"]])

(def PositiveInt
  [:int {:min 0 :error/message "must be a non-negative integer"}])

(def TaxonomyStr
  [:and :string [:fn {:error/message "must be a non-blank taxonomy string e.g. \"us-gaap\""}
                 #(not (clojure.string/blank? %))]])

(def FrameStr
  [:and :string [:re {:error/message "must be a frame string e.g. \"CY2023Q4I\" or \"CY2023\""}
                 #"^CY\d{4}(Q[1-4]I?)?$"]])

;;; ---------------------------------------------------------------------------
;;; Function argument schemas
;;; ---------------------------------------------------------------------------

(def InitArgs
  [:map
   [:name-and-email NonBlankString]])

(def FilingsArgs
  [:map
   [:ticker-or-cik TickerOrCIK]
   [:form {:optional true} [:maybe FormType]]
   [:start-date {:optional true} [:maybe ISODate]]
   [:end-date {:optional true} [:maybe ISODate]]
   [:limit {:optional true} [:maybe PositiveInt]]
   [:include-amends? {:optional true} :boolean]])

(def FilingArgs
  [:map
   [:ticker-or-cik TickerOrCIK]
   [:form FormType]
   [:n PositiveInt]
   [:include-amends? :boolean]])

(def FactsArgs
  [:map
   [:ticker-or-cik TickerOrCIK]
   [:concept {:optional true} [:maybe ConceptArg]]
   [:form {:optional true} [:maybe FormType]]])

(def StatementArgs
  [:map
   [:ticker-or-cik TickerOrCIK]
   [:form FormType]
   [:shape ShapeKw]
   [:as-of {:optional true} [:maybe ISODate]]])

(def FrameArgs
  [:map
   [:concept NonBlankString]
   [:period FrameStr]
   [:taxonomy TaxonomyStr]
   [:unit NonBlankString]])

(def PanelArgs
  [:map
   [:tickers [:sequential TickerOrCIK]]
   [:concept {:optional true} [:maybe ConceptArg]]
   [:form FormType]
   [:as-of {:optional true} [:maybe ISODate]]])

(def SearchArgs
  [:map
   [:query NonBlankString]
   [:limit PositiveInt]])

(def SearchFilingsArgs
  [:map
   [:query NonBlankString]
   [:forms {:optional true} [:maybe [:sequential FormType]]]
   [:start-date {:optional true} [:maybe ISODate]]
   [:end-date {:optional true} [:maybe ISODate]]
   [:limit PositiveInt]])

(def TablesArgs
  [:map
   [:filing-map [:map [:accessionNumber :string]]]
   [:nth {:optional true} [:maybe PositiveInt]]
   [:min-rows PositiveInt]
   [:min-cols PositiveInt]])

(def FilingByAccessionArgs
  [:map
   [:accession-number AccessionNumber]])

;;; ---------------------------------------------------------------------------
;;; Validation helper
;;; ---------------------------------------------------------------------------

(defn validate!
  "Validate args-map against schema. Throws ex-info with :type ::invalid-args
   and a human-readable :message on failure. Returns nil on success."
  [schema args-map]
  (when-not (m/validate schema args-map)
    (let [explanation (m/explain schema args-map)
          message (-> explanation me/humanize str)]
      (throw (ex-info (str "Invalid arguments: " message)
                      {:type ::invalid-args
                       :args args-map
                       :errors (me/humanize explanation)})))))
