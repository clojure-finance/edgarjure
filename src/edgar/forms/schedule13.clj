(ns edgar.forms.schedule13
  "Schedule 13D / 13G parser — beneficial ownership reports of >5% positions.

   Handles the structured-XML era (SEC mandated XML for Schedules 13D/G
   filed from December 2024; form types \"SCHEDULE 13D\" / \"SCHEDULE 13G\"
   and their /A amendments, with a primary_doc.xml). Legacy \"SC 13D\" /
   \"SC 13G\" filings are HTML/text-only and intentionally NOT registered —
   they fall through to filing-obj's :default raw-HTML result.

   The 13D and 13G XML schemas differ in field naming (issuerCIK vs
   issuerCik, dateOfEvent vs eventDateRequiresFilingThisStatement,
   aggregateAmountOwned vs reportingPersonBeneficiallyOwnedAggregateNumber-
   OfShares, ...); this parser normalizes both into one result shape.

   Usage:
     (require '[edgar.forms.schedule13])  ; side-effectful load registers methods
     (edgar.filing/filing-obj filing)
     ;=> {:form \"SCHEDULE 13D\" :schedule :13d
     ;    :issuer {:cik ... :name ... :cusip ...}
     ;    :reporting-persons [{:name ... :aggregate-owned ... :percent-of-class ...} ...]}"
  (:require [edgar.filing :as filing]
            [edgar.forms.xml :refer [parse-str find-tag find-tags tag-text
                                     parse-double-safe]]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; Reporting persons
;;; ---------------------------------------------------------------------------

(defn- reporting-person
  "Normalize one reporting-person element (either schema) into a map."
  [node]
  {:cik (tag-text node :reportingPersonCIK)
   :name (tag-text node :reportingPersonName)
   :citizenship (tag-text node :citizenshipOrOrganization)
   :sole-voting (parse-double-safe (tag-text node :soleVotingPower))
   :shared-voting (parse-double-safe (tag-text node :sharedVotingPower))
   :sole-dispositive (parse-double-safe (tag-text node :soleDispositivePower))
   :shared-dispositive (parse-double-safe (tag-text node :sharedDispositivePower))
   :aggregate-owned (parse-double-safe
                     (tag-text node :aggregateAmountOwned
                               :reportingPersonBeneficiallyOwnedAggregateNumberOfShares))
   :percent-of-class (parse-double-safe (tag-text node :percentOfClass :classPercent))
   :type (tag-text node :typeOfReportingPerson)
   :fund-type (tag-text node :fundType)
   :comments (tag-text node :comments)})

(defn- reporting-persons [root]
  (let [d (find-tags root :reportingPersonInfo) ; 13D
        g (find-tags root :coverPageHeaderReportingPersonDetails)] ; 13G
    (mapv reporting-person (concat d g))))

;;; ---------------------------------------------------------------------------
;;; XML document locator
;;; ---------------------------------------------------------------------------

(defn- schedule-xml
  "Locate and fetch the schedule's primary XML document. Returns nil when
   the filing has no XML (legacy text/HTML 13D/G)."
  [filing]
  (let [idx (filing/filing-index filing)
        docs (:files idx)
        xml-doc (or (first (filter (fn [{:keys [name type]}]
                                     (and (str/ends-with? (str name) ".xml")
                                          (str/starts-with? (str type) "SCHEDULE 13")))
                                   docs))
                    (first (filter #(= "primary_doc.xml" (:name %)) docs)))]
    (when xml-doc
      (filing/filing-document filing (:name xml-doc) :raw? true))))

;;; ---------------------------------------------------------------------------
;;; Public parse entry point
;;; ---------------------------------------------------------------------------

(defn parse-schedule13
  "Parse a Schedule 13D/13G (XML era) filing map into a structured map.
   Returns nil when the filing carries no XML document. Otherwise:
     {:form              \"SCHEDULE 13D\" | \"SCHEDULE 13G\" (from the XML
                         submissionType; falls back to the filing's :form)
      :schedule          :13d | :13g
      :security-class    class of securities the schedule covers
      :event-date        \"MM/DD/YYYY\" event date triggering the filing
      :rule              designated rule (13G only, e.g. \"Rule 13d-1(b)\")
      :issuer            {:cik ... :name ... :cusip ...}
      :reporting-persons [{:cik :name :citizenship :sole-voting :shared-voting
                           :sole-dispositive :shared-dispositive
                           :aggregate-owned :percent-of-class :type
                           :fund-type :comments} ...]}"
  [filing]
  (when-let [raw (schedule-xml filing)]
    (let [root (parse-str raw)
          subtype (tag-text root :submissionType)
          form (or subtype (:form filing))]
      {:form form
       :schedule (cond
                   (str/includes? (str form) "13D") :13d
                   (str/includes? (str form) "13G") :13g
                   :else nil)
       :security-class (tag-text root :securitiesClassTitle)
       :event-date (tag-text root :dateOfEvent :eventDateRequiresFilingThisStatement)
       :rule (tag-text root :designateRulePursuantThisScheduleFiled)
       :issuer (let [info (find-tag root :issuerInfo)]
                 {:cik (tag-text info :issuerCIK :issuerCik)
                  :name (tag-text info :issuerName)
                  :cusip (tag-text info :issuerCUSIP :issuerCusipNumber)})
       :reporting-persons (reporting-persons root)})))

;;; ---------------------------------------------------------------------------
;;; Register filing-obj methods
;;; ---------------------------------------------------------------------------

;; \"SCHEDULE\" is kept for filing maps produced before the index parser
;; captured multi-word form types (or from other sources that truncate at
;; the first space); the XML submissionType disambiguates 13D vs 13G.
(doseq [form-type ["SCHEDULE 13D" "SCHEDULE 13D/A"
                   "SCHEDULE 13G" "SCHEDULE 13G/A"
                   "SCHEDULE"]]
  (.addMethod ^clojure.lang.MultiFn filing/filing-obj form-type
              parse-schedule13))
