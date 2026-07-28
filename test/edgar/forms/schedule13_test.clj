(ns edgar.forms.schedule13-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.forms.schedule13 :as s13]
            [edgar.filing :as filing]))

;;; ---------------------------------------------------------------------------
;;; Fixture XML — modeled on real XML-era filings (13G: AAPL/Vanguard-style
;;; accession 0002100119-26-000139; 13D: Identiv/Radoff group filing,
;;; accession 0000921895-25-000830). The two schemas spell fields
;;; differently — both spellings are exercised here.
;;; ---------------------------------------------------------------------------

(def ^:private schedule13g-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?><edgarSubmission xmlns=\"http://www.sec.gov/edgar/schedule13g\" xmlns:com=\"http://www.sec.gov/edgar/common\">
<schemaVersion>X0202</schemaVersion>
<headerData>
<submissionType>SCHEDULE 13G</submissionType>
</headerData>
<formData>
<coverPageHeader>
<securitiesClassTitle>Common Stock</securitiesClassTitle>
<eventDateRequiresFilingThisStatement>03/31/2026</eventDateRequiresFilingThisStatement>
<issuerInfo>
<issuerCik>0000320193</issuerCik>
<issuerName>Apple Inc</issuerName>
<issuerCusips>
<issuerCusipNumber>037833100</issuerCusipNumber>
</issuerCusips>
<issuerPrincipalExecutiveOfficeAddress>
<com:street1>1 Apple Park Way</com:street1>
<com:city>Cupertino</com:city>
</issuerPrincipalExecutiveOfficeAddress>
</issuerInfo>
<designateRulesPursuantThisScheduleFiled>
<designateRulePursuantThisScheduleFiled>Rule 13d-1(b)</designateRulePursuantThisScheduleFiled>
</designateRulesPursuantThisScheduleFiled>
</coverPageHeader>
<coverPageHeaderReportingPersonDetails>
<reportingPersonName>Big Index Advisors</reportingPersonName>
<citizenshipOrOrganization>PA</citizenshipOrOrganization>
<reportingPersonBeneficiallyOwnedNumberOfShares>
<soleVotingPower>145321305</soleVotingPower>
<sharedVotingPower>0</sharedVotingPower>
<soleDispositivePower>1099168953</soleDispositivePower>
<sharedDispositivePower>0</sharedDispositivePower>
</reportingPersonBeneficiallyOwnedNumberOfShares>
<reportingPersonBeneficiallyOwnedAggregateNumberOfShares>1099168953</reportingPersonBeneficiallyOwnedAggregateNumberOfShares>
<classPercent>7.48</classPercent>
<typeOfReportingPerson>IA</typeOfReportingPerson>
</coverPageHeaderReportingPersonDetails>
</formData>
</edgarSubmission>")

(def ^:private schedule13d-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?><edgarSubmission xmlns=\"http://www.sec.gov/edgar/schedule13D\" xmlns:com=\"http://www.sec.gov/edgar/common\">
<headerData>
<submissionType>SCHEDULE 13D</submissionType>
</headerData>
<formData>
<coverPageHeader>
<securitiesClassTitle>Common Stock, $0.001 par value per share</securitiesClassTitle>
<dateOfEvent>03/20/2025</dateOfEvent>
<previouslyFiledFlag>true</previouslyFiledFlag>
<issuerInfo>
<issuerCIK>0001036044</issuerCIK>
<issuerCUSIP>45170X205</issuerCUSIP>
<issuerName>Identiv, Inc.</issuerName>
<address>
<com:street1>1900-B CARNEGIE AVE.</com:street1>
<com:city>SANTA ANA</com:city>
</address>
</issuerInfo>
</coverPageHeader>
<reportingPersons>
<reportingPersonInfo>
<reportingPersonCIK>0001496916</reportingPersonCIK>
<reportingPersonName>Radoff Family Foundation</reportingPersonName>
<fundType>WC</fundType>
<citizenshipOrOrganization>TX</citizenshipOrOrganization>
<soleVotingPower>0.00</soleVotingPower>
<sharedVotingPower>195000.00</sharedVotingPower>
<soleDispositivePower>0.00</soleDispositivePower>
<sharedDispositivePower>195000.00</sharedDispositivePower>
<aggregateAmountOwned>195000.00</aggregateAmountOwned>
<percentOfClass>0.8</percentOfClass>
<typeOfReportingPerson>CO</typeOfReportingPerson>
</reportingPersonInfo>
<reportingPersonInfo>
<reportingPersonCIK>0001380585</reportingPersonCIK>
<reportingPersonName>Radoff Bradley Louis</reportingPersonName>
<citizenshipOrOrganization>United States of America</citizenshipOrOrganization>
<soleVotingPower>2202710.00</soleVotingPower>
<sharedVotingPower>195000.00</sharedVotingPower>
<soleDispositivePower>2202710.00</soleDispositivePower>
<sharedDispositivePower>195000.00</sharedDispositivePower>
<aggregateAmountOwned>2397710.00</aggregateAmountOwned>
<percentOfClass>10.3</percentOfClass>
<typeOfReportingPerson>IN</typeOfReportingPerson>
</reportingPersonInfo>
</reportingPersons>
</formData>
</edgarSubmission>")

(defn- with-xml [xml f]
  (with-redefs [filing/filing-index
                (fn [_] {:files [{:name "primary_doc.xml" :type "SCHEDULE 13G" :sequence "1"}]})
                filing/filing-document
                (fn [_ _ & _] xml)]
    (f)))

;;; ---------------------------------------------------------------------------
;;; Schedule 13G
;;; ---------------------------------------------------------------------------

(deftest parse-schedule13g-test
  (with-xml schedule13g-xml
    #(let [r (s13/parse-schedule13 {:form "SCHEDULE 13G"})]
       (testing "form and schedule from the XML submissionType"
         (is (= "SCHEDULE 13G" (:form r)))
         (is (= :13g (:schedule r))))
       (testing "cover page fields (13G spellings)"
         (is (= "Common Stock" (:security-class r)))
         (is (= "03/31/2026" (:event-date r)))
         (is (= "Rule 13d-1(b)" (:rule r))))
       (testing "issuer normalized from issuerCik/issuerCusipNumber"
         (is (= {:cik "0000320193" :name "Apple Inc" :cusip "037833100"} (:issuer r))))
       (testing "reporting person normalized from the 13G element names"
         (is (= 1 (count (:reporting-persons r))))
         (let [p (first (:reporting-persons r))]
           (is (= "Big Index Advisors" (:name p)))
           (is (= 1.099168953E9 (:aggregate-owned p)))
           (is (= 7.48 (:percent-of-class p)))
           (is (= 1.45321305E8 (:sole-voting p)))
           (is (= 0.0 (:shared-voting p)))
           (is (= "IA" (:type p)))
           (is (nil? (:cik p)) "13G cover page carries no reporting-person CIK"))))))

;;; ---------------------------------------------------------------------------
;;; Schedule 13D
;;; ---------------------------------------------------------------------------

(deftest parse-schedule13d-test
  (with-xml schedule13d-xml
    #(let [r (s13/parse-schedule13 {:form "SCHEDULE 13D"})]
       (testing "form and schedule"
         (is (= "SCHEDULE 13D" (:form r)))
         (is (= :13d (:schedule r))))
       (testing "cover page fields (13D spellings)"
         (is (= "03/20/2025" (:event-date r)))
         (is (nil? (:rule r)) "rule designation is a 13G-only field"))
       (testing "issuer normalized from issuerCIK/issuerCUSIP"
         (is (= {:cik "0001036044" :name "Identiv, Inc." :cusip "45170X205"} (:issuer r))))
       (testing "group filing: every reportingPersonInfo becomes a person"
         (is (= 2 (count (:reporting-persons r))))
         (let [[p1 p2] (:reporting-persons r)]
           (is (= "Radoff Family Foundation" (:name p1)))
           (is (= "0001496916" (:cik p1)))
           (is (= "WC" (:fund-type p1)))
           (is (= 195000.0 (:shared-voting p1)))
           (is (= 0.8 (:percent-of-class p1)))
           (is (= "Radoff Bradley Louis" (:name p2)))
           (is (= 2397710.0 (:aggregate-owned p2)))
           (is (= 10.3 (:percent-of-class p2)))
           (is (= "IN" (:type p2))))))))

;;; ---------------------------------------------------------------------------
;;; Dispatch, truncated form type, and legacy behaviour
;;; ---------------------------------------------------------------------------

(deftest schedule-dispatch-test
  (testing "filing-obj methods registered for XML-era types + truncated SCHEDULE"
    (let [ms (set (keys (methods filing/filing-obj)))]
      (doseq [ft ["SCHEDULE 13D" "SCHEDULE 13D/A" "SCHEDULE 13G" "SCHEDULE 13G/A" "SCHEDULE"]]
        (is (contains? ms ft) ft))))
  (testing "legacy SC 13D/G intentionally NOT registered (default raw-HTML applies)"
    (let [ms (set (keys (methods filing/filing-obj)))]
      (is (not (contains? ms "SC 13D")))
      (is (not (contains? ms "SC 13G"))))))

(deftest truncated-schedule-form-detects-type-from-xml-test
  (with-xml schedule13d-xml
    #(let [r (s13/parse-schedule13 {:form "SCHEDULE"})]
       (testing "a filing map with the truncated form still resolves 13D from the XML"
         (is (= "SCHEDULE 13D" (:form r)))
         (is (= :13d (:schedule r)))))))

(deftest no-xml-returns-nil-test
  (with-redefs [filing/filing-index
                (fn [_] {:files [{:name "sc13d.htm" :type "SC 13D" :sequence "1"}]})
                filing/filing-document
                (fn [_ _ & _] (throw (ex-info "should not fetch" {})))]
    (testing "legacy filing without XML parses to nil, nothing fetched"
      (is (nil? (s13/parse-schedule13 {:form "SC 13D"}))))))
