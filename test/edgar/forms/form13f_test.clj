(ns edgar.forms.form13f-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.forms.form13f :as form13f]
            [edgar.forms.xml :as x]
            [edgar.filing :as filing]
            [tech.v3.dataset :as ds]))

;;; ---------------------------------------------------------------------------
;;; Fixtures — captured from real filings, trimmed but structurally exact.
;;;
;;; cover-xml:        primary_doc.xml of Berkshire Hathaway 13F-HR
;;;                   0001193125-26-352200 (otherManagers2Info cut from 14
;;;                   entries to 2; the summary counts are as filed)
;;; amend-cover-xml:  primary_doc.xml of Berkshire Hathaway 13F-HR/A
;;;                   0000950123-25-008361 (complete)
;;; infotable-xml:    the INFORMATION TABLE of 0001193125-26-352200, first
;;;                   2 of 89 infoTable entries
;;; ---------------------------------------------------------------------------

(def ^:private cover-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<edgarSubmission xsi:schemaLocation=\"http://www.sec.gov/edgar/thirteenffiler eis_13F_Filer.xsd\" xmlns:ns1=\"http://www.sec.gov/edgar/common\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns=\"http://www.sec.gov/edgar/thirteenffiler\">
  <schemaVersion>X0202</schemaVersion>
  <headerData>
    <submissionType>13F-HR</submissionType>
    <filerInfo>
      <liveTestFlag>LIVE</liveTestFlag>
      <flags/>
      <filer>
        <credentials>
          <cik>0001067983</cik>
          <ccc>XXXXXXXX</ccc>
        </credentials>
      </filer>
      <periodOfReport>06-30-2026</periodOfReport>
    </filerInfo>
  </headerData>
  <formData>
    <coverPage>
      <reportCalendarOrQuarter>06-30-2026</reportCalendarOrQuarter>
      <isAmendment>false</isAmendment>
      <filingManager>
        <name>Berkshire Hathaway Inc</name>
        <address>
          <ns1:street1>3555 Farnam Street</ns1:street1>
          <ns1:city>Omaha</ns1:city>
          <ns1:stateOrCountry>NE</ns1:stateOrCountry>
          <ns1:zipCode>68131</ns1:zipCode>
        </address>
      </filingManager>
      <reportType>13F HOLDINGS REPORT</reportType>
      <form13FFileNumber>028-04545</form13FFileNumber>
      <provideInfoForInstruction5>N</provideInfoForInstruction5>
    </coverPage>
    <signatureBlock>
      <name>Marc D. Hamburg</name>
      <title>Senior Vice President</title>
      <phone>402-346-1400</phone>
      <signature>Marc D. Hamburg</signature>
      <city>Omaha</city>
      <stateOrCountry>NE</stateOrCountry>
      <signatureDate>08-14-2026</signatureDate>
    </signatureBlock>
    <summaryPage>
      <otherIncludedManagersCount>14</otherIncludedManagersCount>
      <tableEntryTotal>89</tableEntryTotal>
      <tableValueTotal>299253556246</tableValueTotal>
      <isConfidentialOmitted>false</isConfidentialOmitted>
      <otherManagers2Info>
        <otherManager2>
          <sequenceNumber>1</sequenceNumber>
          <otherManager>
            <form13FFileNumber>28-2226</form13FFileNumber>
            <name>Berkshire Hathaway Homestate Insurance Co.</name>
          </otherManager>
        </otherManager2>
        <otherManager2>
          <sequenceNumber>2</sequenceNumber>
          <otherManager>
            <form13FFileNumber>28-5678</form13FFileNumber>
            <name>Berkshire Hathaway Life Insurance Co of Nebraska</name>
          </otherManager>
        </otherManager2>
      </otherManagers2Info>
    </summaryPage>
  </formData>
</edgarSubmission>")

(def ^:private amend-cover-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<edgarSubmission xsi:schemaLocation=\"http://www.sec.gov/edgar/thirteenffiler eis_13F_Filer.xsd\" xmlns:ns1=\"http://www.sec.gov/edgar/common\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns=\"http://www.sec.gov/edgar/thirteenffiler\">
  <schemaVersion>X0202</schemaVersion>
  <headerData>
    <submissionType>13F-HR/A</submissionType>
    <filerInfo>
      <liveTestFlag>LIVE</liveTestFlag>
      <flags/>
      <filer>
        <credentials>
          <cik>0001067983</cik>
          <ccc>XXXXXXXX</ccc>
        </credentials>
      </filer>
      <periodOfReport>03-31-2025</periodOfReport>
    </filerInfo>
  </headerData>
  <formData>
    <coverPage>
      <reportCalendarOrQuarter>03-31-2025</reportCalendarOrQuarter>
      <isAmendment>true</isAmendment>
      <amendmentNo>1</amendmentNo>
      <amendmentInfo>
        <amendmentType>NEW HOLDINGS</amendmentType>
        <confDeniedExpired>true</confDeniedExpired>
        <dateDeniedExpired>08-14-2025</dateDeniedExpired>
        <dateReported>05-15-2025</dateReported>
        <reasonForNonConfidentiality>Confidential Treatment Expired</reasonForNonConfidentiality>
      </amendmentInfo>
      <filingManager>
        <name>Berkshire Hathaway Inc</name>
        <address>
          <ns1:street1>3555 Farnam Street</ns1:street1>
          <ns1:city>Omaha</ns1:city>
          <ns1:stateOrCountry>NE</ns1:stateOrCountry>
          <ns1:zipCode>68131</ns1:zipCode>
        </address>
      </filingManager>
      <reportType>13F HOLDINGS REPORT</reportType>
      <form13FFileNumber>028-04545</form13FFileNumber>
      <provideInfoForInstruction5>N</provideInfoForInstruction5>
    </coverPage>
    <signatureBlock>
      <name>Marc D. Hamburg</name>
      <title>Senior Vice President</title>
      <phone>402-346-1400</phone>
      <signature>Marc D. Hamburg</signature>
      <city>Omaha</city>
      <stateOrCountry>NE</stateOrCountry>
      <signatureDate>08-14-2025</signatureDate>
    </signatureBlock>
    <summaryPage>
      <otherIncludedManagersCount>2</otherIncludedManagersCount>
      <tableEntryTotal>4</tableEntryTotal>
      <tableValueTotal>1106550356</tableValueTotal>
      <isConfidentialOmitted>false</isConfidentialOmitted>
      <otherManagers2Info>
        <otherManager2>
          <sequenceNumber>4</sequenceNumber>
          <otherManager>
            <form13FFileNumber>28-554</form13FFileNumber>
            <name>Buffett Warren E</name>
          </otherManager>
        </otherManager2>
        <otherManager2>
          <sequenceNumber>11</sequenceNumber>
          <otherManager>
            <form13FFileNumber>28-718</form13FFileNumber>
            <name>National Indemnity Co</name>
          </otherManager>
        </otherManager2>
      </otherManagers2Info>
    </summaryPage>
  </formData>
</edgarSubmission>")

(def ^:private infotable-xml
  "<informationTable xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns=\"http://www.sec.gov/edgar/document/thirteenf/informationtable\">
  <infoTable>
    <nameOfIssuer>ALLY FINL INC</nameOfIssuer>
    <titleOfClass>COM</titleOfClass>
    <cusip>02005N100</cusip>
    <value>577211815</value>
    <shrsOrPrnAmt>
      <sshPrnamt>12561737</sshPrnamt>
      <sshPrnamtType>SH</sshPrnamtType>
    </shrsOrPrnAmt>
    <investmentDiscretion>DFND</investmentDiscretion>
    <otherManager>4</otherManager>
    <votingAuthority>
      <Sole>12561737</Sole>
      <Shared>0</Shared>
      <None>0</None>
    </votingAuthority>
  </infoTable>
  <infoTable>
    <nameOfIssuer>ALLY FINL INC</nameOfIssuer>
    <titleOfClass>COM</titleOfClass>
    <cusip>02005N100</cusip>
    <value>128838056</value>
    <shrsOrPrnAmt>
      <sshPrnamt>2803875</sshPrnamt>
      <sshPrnamtType>SH</sshPrnamtType>
    </shrsOrPrnAmt>
    <investmentDiscretion>DFND</investmentDiscretion>
    <otherManager>2,4,11</otherManager>
    <votingAuthority>
      <Sole>2803875</Sole>
      <Shared>0</Shared>
      <None>0</None>
    </votingAuthority>
  </infoTable>
</informationTable>")

;; Some filers prefix every element (ns1:informationTable, ns1:infoTable, ...).
(def ^:private infotable-ns-xml
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

;;; ---------------------------------------------------------------------------
;;; Cover document (primary_doc.xml)
;;; ---------------------------------------------------------------------------

(deftest mdy->iso-test
  (let [f #'edgar.forms.form13f/mdy->iso]
    (is (= "2026-06-30" (f "06-30-2026")))
    (is (= "2026-06-30" (f "2026-06-30")) "non-MM-DD-YYYY input unchanged")
    (is (nil? (f nil)))))

(deftest parse-cover-test
  (let [c (#'edgar.forms.form13f/parse-cover (x/parse-str cover-xml))]
    (testing "header: submission type and period (MM-DD-YYYY -> ISO)"
      (is (= "13F-HR" (:submission-type c)))
      (is (= "2026-06-30" (:period-of-report c))))
    (testing "cover page"
      (is (= "13F HOLDINGS REPORT" (:report-type c)))
      (is (= "028-04545" (:form13f-file-number c))
          "the filer's own number, not an otherManagers2Info entry's")
      (is (false? (:is-amendment? c)))
      (is (nil? (:amendment-number c)))
      (is (nil? (:amendment-type c))))
    (testing "summary page"
      (is (= 14 (:other-managers-count c)))
      (is (= 89 (:table-entry-count c)))
      (is (= 299253556246 (:table-value-total c))))))

(deftest parse-cover-amendment-test
  (let [c (#'edgar.forms.form13f/parse-cover (x/parse-str amend-cover-xml))]
    (is (= "13F-HR/A" (:submission-type c)))
    (is (= "2025-03-31" (:period-of-report c)))
    (is (true? (:is-amendment? c)))
    (is (= 1 (:amendment-number c)))
    (is (= "NEW HOLDINGS" (:amendment-type c)))
    (is (= 4 (:table-entry-count c)))
    (is (= 1106550356 (:table-value-total c)))))

(deftest parse-manager-test
  (is (= {:name "Berkshire Hathaway Inc" :street "3555 Farnam Street"
          :city "Omaha" :state "NE" :zip "68131"}
         (#'edgar.forms.form13f/parse-manager (x/parse-str cover-xml)))))

;;; ---------------------------------------------------------------------------
;;; Information table
;;; ---------------------------------------------------------------------------

(deftest parse-holding-test
  (let [[h1 h2] (#'edgar.forms.form13f/parse-holdings (x/parse-str infotable-xml))]
    (testing "fields of the first position"
      (is (= "ALLY FINL INC" (:name h1)))
      (is (= "02005N100" (:cusip h1)))
      (is (= "COM" (:title h1)))
      (is (= 577211815 (:value h1)))
      (is (= 12561737 (:shares h1)))
      (is (= "SH" (:shares-type h1)))
      (is (= "DFND" (:investment-discretion h1)))
      (is (= "4" (:other-managers h1)))
      (is (= 12561737 (:voting-sole h1)))
      (is (= 0 (:voting-none h1))))
    (testing "comma-separated other-manager list kept as filed"
      (is (= "2,4,11" (:other-managers h2))))))

(deftest find-tags-namespace-prefix-test
  (let [root (x/parse-str infotable-ns-xml)
        entries (x/find-tags root :infoTable)]
    (testing "find-tags matches ns1:-prefixed infoTable elements"
      (is (= 2 (count entries))))
    (testing "parse-holding works on ns1:-prefixed entry"
      (let [h (#'edgar.forms.form13f/parse-holding (first entries))]
        (is (= "APPLE INC" (:name h)))
        (is (= "037833100" (:cusip h)))
        (is (= 200000 (:value h)))
        (is (= 1000000 (:shares h)))))
    (testing "find-tag matches ns1:-prefixed single element"
      (is (some? (x/find-tag root :infoTable))))))

;;; ---------------------------------------------------------------------------
;;; Document locator
;;; ---------------------------------------------------------------------------

(defn- fetched-names
  "Run fetch-documents against a stubbed filing index; return the names of
   the documents that were fetched for :cover and :infotable."
  [files]
  (with-redefs [filing/filing-index (fn [_] {:files files})
                filing/filing-document (fn [_ name & _] name)]
    (#'edgar.forms.form13f/fetch-documents {})))

(deftest fetch-documents-test
  (testing "real index layout: primary_doc.xml + INFORMATION TABLE + .txt"
    (is (= {:cover "primary_doc.xml" :infotable "56757.xml"}
           (fetched-names [{:name "primary_doc.xml" :type "13F-HR" :sequence "1"}
                           {:name "56757.xml" :type "INFORMATION TABLE" :sequence "2"}
                           {:name "0001193125-26-352200.txt" :type "" :sequence ""}]))))
  (testing "untyped infotable: the non-cover XML is used, never the cover"
    (is (= {:cover "primary_doc.xml" :infotable "infotable.xml"}
           (fetched-names [{:name "primary_doc.xml" :type "13F-HR"}
                           {:name "infotable.xml" :type ""}]))))
  (testing "no infotable at all: the cover is not reused as an infotable"
    (is (= {:cover "primary_doc.xml" :infotable nil}
           (fetched-names [{:name "primary_doc.xml" :type "13F-HR"}])))))

;;; ---------------------------------------------------------------------------
;;; Full parse
;;; ---------------------------------------------------------------------------

(defn- parse-with [filing docs]
  (with-redefs [edgar.forms.form13f/fetch-documents (fn [_] docs)]
    (form13f/parse-form13f filing)))

(deftest parse-form13f-test
  (testing "13F-HR: cover fields from primary_doc.xml, holdings from the infotable"
    (let [r (parse-with {:form "13F-HR"} {:cover cover-xml :infotable infotable-xml})]
      (is (= "13F-HR" (:form r)))
      (is (= "2026-06-30" (:period-of-report r)))
      (is (= "028-04545" (:form13f-file-number r)))
      (is (= 89 (:table-entry-count r)))
      (is (= 299253556246 (:table-value-total r)))
      (is (= 14 (:other-managers-count r)))
      (is (false? (:is-amendment? r)))
      (is (= "Berkshire Hathaway Inc" (get-in r [:manager :name])))
      (is (= 2 (ds/row-count (:holdings r))))
      (is (= (+ 577211815 128838056) (:total-value r)))
      (is (not (contains? r :submission-type)))))
  (testing "13F-HR/A: :form and amendment fields from the XML"
    (let [r (parse-with {:form "13F-HR/A"} {:cover amend-cover-xml :infotable infotable-xml})]
      (is (= "13F-HR/A" (:form r)))
      (is (true? (:is-amendment? r)))
      (is (= 1 (:amendment-number r)))
      (is (= "NEW HOLDINGS" (:amendment-type r)))
      (is (= "2025-03-31" (:period-of-report r)))))
  (testing "missing cover: :form and amendment status fall back to the filing"
    (let [r (parse-with {:form "13F-HR/A"} {:cover nil :infotable infotable-xml})]
      (is (= "13F-HR/A" (:form r)))
      (is (true? (:is-amendment? r)))
      (is (nil? (:period-of-report r)))
      (is (= 2 (ds/row-count (:holdings r))))))
  (testing "missing infotable: empty holdings dataset, cover still parsed"
    (let [r (parse-with {:form "13F-HR"} {:cover cover-xml :infotable nil})]
      (is (= 0 (ds/row-count (:holdings r))))
      (is (= 0 (:total-value r)))
      (is (= 89 (:table-entry-count r)))))
  (testing "nil when the filing has neither document"
    (is (nil? (parse-with {:form "13F-HR"} {:cover nil :infotable nil})))))

(deftest filing-obj-dispatch-test
  (let [ms (set (keys (methods filing/filing-obj)))]
    (is (contains? ms "13F-HR"))
    (is (contains? ms "13F-HR/A"))))
