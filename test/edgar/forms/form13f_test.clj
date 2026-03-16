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

(def ^:private form13f-ns-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
<ns1:informationTable xmlns:ns1=\"http://www.sec.gov/edgar/document/thirteenf/informationtable\">
  <ns1:infoTable>
    <ns1:nameOfIssuer>APPLE INC</ns1:nameOfIssuer>
    <ns1:titleOfClass>COM</ns1:titleOfClass>
    <ns1:cusip>037833100</ns1:cusip>
    <ns1:value>200000</ns1:value>
    <ns1:shrsOrPrnAmt>
      <ns1:sshPrnamt>1000000</ns1:sshPrnamt>
      <ns1:sshPrnamtType>SH</ns1:sshPrnamtType>
    </ns1:shrsOrPrnAmt>
    <ns1:investmentDiscretion>SOLE</ns1:investmentDiscretion>
    <ns1:votingAuthority>
      <ns1:Sole>1000000</ns1:Sole>
      <ns1:Shared>0</ns1:Shared>
      <ns1:None>0</ns1:None>
    </ns1:votingAuthority>
  </ns1:infoTable>
  <ns1:infoTable>
    <ns1:nameOfIssuer>MICROSOFT CORP</ns1:nameOfIssuer>
    <ns1:titleOfClass>COM</ns1:titleOfClass>
    <ns1:cusip>594918104</ns1:cusip>
    <ns1:value>150000</ns1:value>
    <ns1:shrsOrPrnAmt>
      <ns1:sshPrnamt>400000</ns1:sshPrnamt>
      <ns1:sshPrnamtType>SH</ns1:sshPrnamtType>
    </ns1:shrsOrPrnAmt>
    <ns1:investmentDiscretion>SOLE</ns1:investmentDiscretion>
    <ns1:votingAuthority>
      <ns1:Sole>400000</ns1:Sole>
      <ns1:Shared>0</ns1:Shared>
      <ns1:None>0</ns1:None>
    </ns1:votingAuthority>
  </ns1:infoTable>
</ns1:informationTable>")

(deftest find-tags-namespace-prefix-test
  (let [root (#'edgar.forms.form13f/parse-xml-str form13f-ns-xml)
        entries (#'edgar.forms.form13f/find-tags root :infoTable)]
    (testing "find-tags matches ns1:-prefixed infoTable elements"
      (is (= 2 (count entries))))
    (testing "parse-holding works on ns1:-prefixed entry"
      (let [h (#'edgar.forms.form13f/parse-holding (first entries))]
        (is (= "APPLE INC" (:name h)))
        (is (= "037833100" (:cusip h)))
        (is (= 200000 (:value h)))
        (is (= 1000000 (:shares h)))))
    (testing "find-tag matches ns1:-prefixed single element"
      (let [node (#'edgar.forms.form13f/find-tag root :infoTable)]
        (is (some? node))))))

(def ^:private cover-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<edgarSubmission>
  <headerData>
    <submissionType>13F-HR</submissionType>
  </headerData>
  <formData>
    <coverPage>
      <filingManager>
        <name>GOLDMAN SACHS GROUP INC</name>
        <address>
          <street1>200 WEST ST</street1>
          <city>NEW YORK</city>
          <stateOrCountry>NY</stateOrCountry>
          <zipCode>10282</zipCode>
        </address>
      </filingManager>
    </coverPage>
    <reportSummary>
      <periodOfReport>2024-03-31</periodOfReport>
      <reportType>13F HOLDINGS REPORT</reportType>
      <form13FFileNumber>028-00019</form13FFileNumber>
      <tableEntryCount>2</tableEntryCount>
      <tableValueTotal>350000</tableValueTotal>
    </reportSummary>
  </formData>
</edgarSubmission>")

(def ^:private infotable-xml-separate
  "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
<ns1:informationTable xmlns:ns1=\"http://www.sec.gov/edgar/document/thirteenf/informationtable\">
  <ns1:infoTable>
    <ns1:nameOfIssuer>APPLE INC</ns1:nameOfIssuer>
    <ns1:titleOfClass>COM</ns1:titleOfClass>
    <ns1:cusip>037833100</ns1:cusip>
    <ns1:value>200000</ns1:value>
    <ns1:shrsOrPrnAmt>
      <ns1:sshPrnamt>1000000</ns1:sshPrnamt>
      <ns1:sshPrnamtType>SH</ns1:sshPrnamtType>
    </ns1:shrsOrPrnAmt>
    <ns1:investmentDiscretion>SOLE</ns1:investmentDiscretion>
    <ns1:votingAuthority>
      <ns1:Sole>1000000</ns1:Sole>
      <ns1:Shared>0</ns1:Shared>
      <ns1:None>0</ns1:None>
    </ns1:votingAuthority>
  </ns1:infoTable>
  <ns1:infoTable>
    <ns1:nameOfIssuer>MICROSOFT CORP</ns1:nameOfIssuer>
    <ns1:titleOfClass>COM</ns1:titleOfClass>
    <ns1:cusip>594918104</ns1:cusip>
    <ns1:value>150000</ns1:value>
    <ns1:shrsOrPrnAmt>
      <ns1:sshPrnamt>400000</ns1:sshPrnamt>
      <ns1:sshPrnamtType>SH</ns1:sshPrnamtType>
    </ns1:shrsOrPrnAmt>
    <ns1:investmentDiscretion>SOLE</ns1:investmentDiscretion>
    <ns1:votingAuthority>
      <ns1:Sole>400000</ns1:Sole>
      <ns1:Shared>0</ns1:Shared>
      <ns1:None>0</ns1:None>
    </ns1:votingAuthority>
  </ns1:infoTable>
</ns1:informationTable>")

(deftest parse-form13f-two-document-test
  (testing "two-document submission: header from cover XML, holdings from infotable XML"
    (let [mock-filing {:form "13F-HR" :cik "0000042352" :accessionNumber "0000042352-24-000001"}
          result (with-redefs
                  [edgar.forms.form13f/find-infotable-xml (fn [_] infotable-xml-separate)
                   edgar.forms.form13f/find-primary-cover-xml (fn [_] cover-xml)]
                   (form13f/parse-form13f mock-filing))]
      (testing "manager comes from cover XML, not infotable"
        (is (= "GOLDMAN SACHS GROUP INC" (get-in result [:manager :name])))
        (is (= "NEW YORK" (get-in result [:manager :city]))))
      (testing "period-of-report from cover XML"
        (is (= "2024-03-31" (:period-of-report result))))
      (testing "holdings come from infotable XML"
        (is (= 2 (ds/row-count (:holdings result)))))
      (testing "total-value sums infotable values"
        (is (= 350000 (:total-value result))))
      (testing "is-amendment? false for 13F-HR"
        (is (false? (:is-amendment? result))))))
  (testing "two-document submission: amendment status from filing :form key"
    (let [mock-filing {:form "13F-HR/A" :cik "0000042352" :accessionNumber "0000042352-24-000002"}
          result (with-redefs
                  [edgar.forms.form13f/find-infotable-xml (fn [_] infotable-xml-separate)
                   edgar.forms.form13f/find-primary-cover-xml (fn [_] cover-xml)]
                   (form13f/parse-form13f mock-filing))]
      (testing "is-amendment? true for 13F-HR/A regardless of XML reportType"
        (is (true? (:is-amendment? result))))
      (testing "manager still comes from cover XML"
        (is (= "GOLDMAN SACHS GROUP INC" (get-in result [:manager :name]))))
      (testing "holdings still come from infotable"
        (is (= 2 (ds/row-count (:holdings result))))))))

(deftest parse-form13f-single-document-fallback-test
  (testing "single-document submission: cover XML used for both header and holdings when no infotable"
    (let [mock-filing {:form "13F-HR" :cik "0000042352" :accessionNumber "0000042352-20-000001"}
          result (with-redefs
                  [edgar.forms.form13f/find-infotable-xml (fn [_] nil)
                   edgar.forms.form13f/find-primary-cover-xml (fn [_] form13f-xml)]
                   (form13f/parse-form13f mock-filing))]
      (testing "returns non-nil result"
        (is (some? result)))
      (testing "period-of-report extracted"
        (is (= "2024-03-31" (:period-of-report result))))
      (testing "holdings extracted from the single doc"
        (is (= 2 (ds/row-count (:holdings result)))))
      (testing "total-value sums correctly"
        (is (= 300000 (:total-value result))))))
  (testing "returns nil when both cover and infotable are nil"
    (let [mock-filing {:form "13F-HR"}
          result (with-redefs
                  [edgar.forms.form13f/find-infotable-xml (fn [_] nil)
                   edgar.forms.form13f/find-primary-cover-xml (fn [_] nil)]
                   (form13f/parse-form13f mock-filing))]
      (is (nil? result)))))
