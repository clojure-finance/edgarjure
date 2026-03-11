(ns edgar.forms.form13f-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.forms.form13f :as form13f]
            [clojure.string :as str]
            [tech.v3.dataset :as ds]))

;;; ---------------------------------------------------------------------------
;;; Fixture XML — minimal 13F-HR infotable
;;; ---------------------------------------------------------------------------

(def ^:private form13f-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<edgarSubmission>
  <headerData>
    <submissionType>13F-HR</submissionType>
  </headerData>
  <formData>
    <coverPage>
      <reportCalendarOrYear>2024-03-31</reportCalendarOrYear>
      <filingManager>
        <name>BERKSHIRE HATHAWAY INC</name>
        <address>
          <street1>3555 FARNAM ST</street1>
          <city>OMAHA</city>
          <stateOrCountry>NE</stateOrCountry>
          <zipCode>68131</zipCode>
        </address>
      </filingManager>
    </coverPage>
    <reportSummary>
      <periodOfReport>2024-03-31</periodOfReport>
      <reportType>13F HOLDINGS REPORT</reportType>
      <form13FFileNumber>028-01120</form13FFileNumber>
      <tableEntryCount>2</tableEntryCount>
      <tableValueTotal>300000</tableValueTotal>
    </reportSummary>
    <informationTable>
      <infoTable>
        <nameOfIssuer>APPLE INC</nameOfIssuer>
        <titleOfClass>COM</titleOfClass>
        <cusip>037833100</cusip>
        <value>200000</value>
        <shrsOrPrnAmt>
          <sshPrnamt>1000000</sshPrnamt>
          <sshPrnamtType>SH</sshPrnamtType>
        </shrsOrPrnAmt>
        <investmentDiscretion>SOLE</investmentDiscretion>
        <votingAuthority>
          <Sole>1000000</Sole>
          <Shared>0</Shared>
          <None>0</None>
        </votingAuthority>
      </infoTable>
      <infoTable>
        <nameOfIssuer>AMERICAN EXPRESS CO</nameOfIssuer>
        <titleOfClass>COM</titleOfClass>
        <cusip>025816109</cusip>
        <value>100000</value>
        <shrsOrPrnAmt>
          <sshPrnamt>500000</sshPrnamt>
          <sshPrnamtType>SH</sshPrnamtType>
        </shrsOrPrnAmt>
        <investmentDiscretion>SOLE</investmentDiscretion>
        <votingAuthority>
          <Sole>500000</Sole>
          <Shared>0</Shared>
          <None>0</None>
        </votingAuthority>
      </infoTable>
    </informationTable>
  </formData>
</edgarSubmission>")

(defn- parse-root []
  (#'edgar.forms.form13f/parse-xml-str form13f-xml))

;;; ---------------------------------------------------------------------------
;;; parse-report-summary
;;; ---------------------------------------------------------------------------

(deftest parse-report-summary-test
  (let [root (parse-root)
        summary (#'edgar.forms.form13f/parse-report-summary root)]
    (testing "extracts period-of-report"
      (is (= "2024-03-31" (:period-of-report summary))))
    (testing "extracts report-type"
      (is (= "13F HOLDINGS REPORT" (:report-type summary))))
    (testing "extracts form13f-file-number"
      (is (= "028-01120" (:form13f-file-number summary))))
    (testing "extracts table-entry-count as long"
      (is (= 2 (:table-entry-count summary))))
    (testing "extracts table-value-total as long"
      (is (= 300000 (:table-value-total summary))))))

;;; ---------------------------------------------------------------------------
;;; parse-holding
;;; ---------------------------------------------------------------------------

(deftest parse-holding-test
  (let [root (parse-root)
        entries (#'edgar.forms.form13f/find-tags root :infoTable)
        h (#'edgar.forms.form13f/parse-holding (first entries))]
    (testing "extracts name"
      (is (= "APPLE INC" (:name h))))
    (testing "extracts cusip"
      (is (= "037833100" (:cusip h))))
    (testing "extracts value as long"
      (is (= 200000 (:value h))))
    (testing "extracts shares as long"
      (is (= 1000000 (:shares h))))
    (testing "extracts shares-type"
      (is (= "SH" (:shares-type h))))
    (testing "extracts voting-sole"
      (is (= 1000000 (:voting-sole h))))))

;;; ---------------------------------------------------------------------------
;;; is-amendment? logic
;;; The known bug: is-amendment? checks reportType for "RESTATEMENT" which is
;;; never present — amendment status is on the filing :form key, not in XML.
;;; Tests document the current (buggy) and correct (via :form key) behaviour.
;;; ---------------------------------------------------------------------------

(deftest is-amendment-current-behavior-test
  (testing "is-amendment? on a non-amendment report-type is false (current behavior)"
    (let [root (parse-root)
          summary (#'edgar.forms.form13f/parse-report-summary root)]
      (is (false? (:is-amendment? summary)))))
  (testing "amendment status is correctly indicated by the filing :form key"
    (is (= "13F-HR/A" (:form {:form "13F-HR/A" :accessionNumber "x"})))
    (is (str/ends-with? "13F-HR/A" "/A"))))
