(ns edgar.forms.ownership-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.forms.ownership]
            [edgar.forms.form4 :as form4]
            [edgar.filing :as filing]))

;;; ---------------------------------------------------------------------------
;;; Fixture XML — modeled on real filings (Form 4: AAPL insider sale shape;
;;; Form 3: AAPL 2026 officer initial statement, accession
;;; 0001780525-26-000003; Form 5: AAPL 2024 annual statement, accession
;;; 0000320193-24-000102)
;;; ---------------------------------------------------------------------------

(def ^:private form4-xml
  "<?xml version=\"1.0\"?>
<ownershipDocument>
  <documentType>4</documentType>
  <issuer>
    <issuerCik>0000320193</issuerCik>
    <issuerName>Apple Inc.</issuerName>
    <issuerTradingSymbol>AAPL</issuerTradingSymbol>
  </issuer>
  <reportingOwner>
    <reportingOwnerId>
      <rptOwnerCik>0001214128</rptOwnerCik>
      <rptOwnerName>COOK TIMOTHY D</rptOwnerName>
    </reportingOwnerId>
    <reportingOwnerAddress>
      <rptOwnerStreet1>ONE APPLE PARK WAY</rptOwnerStreet1>
      <rptOwnerCity>CUPERTINO</rptOwnerCity>
      <rptOwnerState>CA</rptOwnerState>
      <rptOwnerZipCode>95014</rptOwnerZipCode>
    </reportingOwnerAddress>
    <reportingOwnerRelationship>
      <isDirector>0</isDirector>
      <isOfficer>1</isOfficer>
      <isTenPercentOwner>0</isTenPercentOwner>
      <isOther>0</isOther>
      <officerTitle>Chief Executive Officer</officerTitle>
    </reportingOwnerRelationship>
  </reportingOwner>
  <periodOfReport>2024-01-15</periodOfReport>
  <nonDerivativeTable>
    <nonDerivativeTransaction>
      <securityTitle><value>Common Stock</value></securityTitle>
      <transactionDate><value>2024-01-15</value></transactionDate>
      <transactionCoding>
        <transactionCode>S</transactionCode>
        <transactionFormType>4</transactionFormType>
      </transactionCoding>
      <transactionAmounts>
        <transactionShares><value>50000</value></transactionShares>
        <transactionPricePerShare><value>185.50</value></transactionPricePerShare>
        <transactionAcquiredDisposedCode><value>D</value></transactionAcquiredDisposedCode>
      </transactionAmounts>
      <postTransactionAmounts>
        <sharesOwnedFollowingTransaction><value>1200000</value></sharesOwnedFollowingTransaction>
      </postTransactionAmounts>
      <ownershipNature>
        <directOrIndirectOwnership><value>D</value></directOrIndirectOwnership>
      </ownershipNature>
    </nonDerivativeTransaction>
  </nonDerivativeTable>
  <derivativeTable/>
</ownershipDocument>")

(def ^:private form3-xml
  "<?xml version=\"1.0\"?>
<ownershipDocument>
  <schemaVersion>X0206</schemaVersion>
  <documentType>3</documentType>
  <periodOfReport>2026-03-01</periodOfReport>
  <noSecuritiesOwned>0</noSecuritiesOwned>
  <issuer>
    <issuerCik>0000320193</issuerCik>
    <issuerName>Apple Inc.</issuerName>
    <issuerTradingSymbol>AAPL</issuerTradingSymbol>
  </issuer>
  <reportingOwner>
    <reportingOwnerId>
      <rptOwnerCik>0001780525</rptOwnerCik>
      <rptOwnerName>Newstead Jennifer</rptOwnerName>
    </reportingOwnerId>
    <reportingOwnerRelationship>
      <isDirector>0</isDirector>
      <isOfficer>1</isOfficer>
      <isTenPercentOwner>0</isTenPercentOwner>
      <isOther>0</isOther>
      <officerTitle>SVP, GC and Secretary</officerTitle>
    </reportingOwnerRelationship>
  </reportingOwner>
  <nonDerivativeTable></nonDerivativeTable>
  <derivativeTable>
    <derivativeHolding>
      <securityTitle><value>Restricted Stock Unit</value></securityTitle>
      <conversionOrExercisePrice><footnoteId id=\"F2\"/></conversionOrExercisePrice>
      <exerciseDate><footnoteId id=\"F1\"/></exerciseDate>
      <expirationDate><footnoteId id=\"F1\"/></expirationDate>
      <underlyingSecurity>
        <underlyingSecurityTitle><value>Common Stock</value></underlyingSecurityTitle>
        <underlyingSecurityShares><value>301040</value></underlyingSecurityShares>
      </underlyingSecurity>
      <ownershipNature>
        <directOrIndirectOwnership><value>D</value></directOrIndirectOwnership>
      </ownershipNature>
    </derivativeHolding>
  </derivativeTable>
</ownershipDocument>")

(def ^:private form5-xml
  "<?xml version=\"1.0\"?>
<ownershipDocument>
  <schemaVersion>X0508</schemaVersion>
  <documentType>5</documentType>
  <periodOfReport>2024-09-28</periodOfReport>
  <notSubjectToSection16>0</notSubjectToSection16>
  <form3HoldingsReported>0</form3HoldingsReported>
  <form4TransactionsReported>0</form4TransactionsReported>
  <issuer>
    <issuerCik>0000320193</issuerCik>
    <issuerName>Apple Inc.</issuerName>
    <issuerTradingSymbol>AAPL</issuerTradingSymbol>
  </issuer>
  <reportingOwner>
    <reportingOwnerId>
      <rptOwnerCik>0001059235</rptOwnerCik>
      <rptOwnerName>DOE JANE</rptOwnerName>
    </reportingOwnerId>
    <reportingOwnerRelationship>
      <isDirector>1</isDirector>
      <isOfficer>0</isOfficer>
      <isTenPercentOwner>0</isTenPercentOwner>
      <isOther>0</isOther>
    </reportingOwnerRelationship>
  </reportingOwner>
  <nonDerivativeTable>
    <nonDerivativeTransaction>
      <securityTitle><value>Common Stock</value></securityTitle>
      <transactionDate><value>2024-04-18</value></transactionDate>
      <transactionCoding>
        <transactionFormType>5</transactionFormType>
        <transactionCode>W</transactionCode>
        <equitySwapInvolved>0</equitySwapInvolved>
      </transactionCoding>
      <transactionAmounts>
        <transactionShares><value>42</value></transactionShares>
        <transactionPricePerShare><value>0</value></transactionPricePerShare>
        <transactionAcquiredDisposedCode><value>A</value></transactionAcquiredDisposedCode>
      </transactionAmounts>
      <postTransactionAmounts>
        <sharesOwnedFollowingTransaction><value>6042</value></sharesOwnedFollowingTransaction>
      </postTransactionAmounts>
      <ownershipNature>
        <directOrIndirectOwnership><value>I</value></directOrIndirectOwnership>
        <natureOfOwnership><value>By spouse</value><footnoteId id=\"F1\"/></natureOfOwnership>
      </ownershipNature>
    </nonDerivativeTransaction>
    <nonDerivativeHolding>
      <securityTitle><value>Common Stock</value></securityTitle>
      <postTransactionAmounts>
        <sharesOwnedFollowingTransaction><value>60975</value></sharesOwnedFollowingTransaction>
      </postTransactionAmounts>
      <ownershipNature>
        <directOrIndirectOwnership><value>D</value></directOrIndirectOwnership>
      </ownershipNature>
    </nonDerivativeHolding>
  </nonDerivativeTable>
</ownershipDocument>")

(defn- parse-str [s]
  (#'edgar.forms.ownership/parse-xml-str s))

;;; ---------------------------------------------------------------------------
;;; Issuer and owner (Form 4 fixture)
;;; ---------------------------------------------------------------------------

(deftest parse-issuer-test
  (let [issuer (#'edgar.forms.ownership/parse-issuer (parse-str form4-xml))]
    (testing "extracts CIK, name, ticker"
      (is (= "0000320193" (:cik issuer)))
      (is (= "Apple Inc." (:name issuer)))
      (is (= "AAPL" (:ticker issuer))))))

(deftest parse-owner-test
  (let [owner (#'edgar.forms.ownership/parse-owner (parse-str form4-xml))]
    (testing "extracts owner identity and relationship"
      (is (= "0001214128" (:cik owner)))
      (is (= "COOK TIMOTHY D" (:name owner)))
      (is (true? (:is-officer? owner)))
      (is (false? (:is-director? owner)))
      (is (= "Chief Executive Officer" (:officer-title owner))))))

;;; ---------------------------------------------------------------------------
;;; Transactions (Form 4 fixture)
;;; ---------------------------------------------------------------------------

(deftest parse-non-derivative-transactions-test
  (let [txns (vec (#'edgar.forms.ownership/parse-non-derivative-transactions
                   (parse-str form4-xml)))]
    (testing "returns one transaction"
      (is (= 1 (count txns))))
    (let [t (first txns)]
      (is (= :non-derivative (:type t)))
      (is (= "Common Stock" (:security-title t)))
      (is (= "2024-01-15" (:date t)))
      (is (= "S" (:coding t)))
      (is (= 50000.0 (:shares t)))
      (is (= 185.5 (:price t)))
      (is (= "D" (:acquired-disposed t)))
      (is (= 1200000.0 (:shares-after t))))))

;;; ---------------------------------------------------------------------------
;;; Form 3 — holdings only
;;; ---------------------------------------------------------------------------

(deftest parse-form3-holdings-test
  (let [root (parse-str form3-xml)
        deriv (vec (#'edgar.forms.ownership/parse-derivative-holdings root))
        nonderiv (vec (#'edgar.forms.ownership/parse-non-derivative-holdings root))]
    (testing "empty nonDerivativeTable yields no non-derivative holdings"
      (is (empty? nonderiv)))
    (testing "derivative holding parsed"
      (is (= 1 (count deriv)))
      (let [h (first deriv)]
        (is (= :derivative (:type h)))
        (is (= "Restricted Stock Unit" (:security-title h)))
        (is (= "Common Stock" (:underlying-title h)))
        (is (= 301040.0 (:underlying-shares h)))
        (is (= "D" (:ownership-nature h)))))
    (testing "footnote-only price/date elements parse as nil, not garbage"
      (let [h (first deriv)]
        (is (nil? (:conversion-price h)))
        (is (nil? (:exercise-date h)))
        (is (nil? (:expiration-date h)))))))

;;; ---------------------------------------------------------------------------
;;; Form 5 — transactions and holdings together
;;; ---------------------------------------------------------------------------

(deftest parse-form5-test
  (let [root (parse-str form5-xml)
        txns (vec (#'edgar.forms.ownership/parse-non-derivative-transactions root))
        holds (vec (#'edgar.forms.ownership/parse-non-derivative-holdings root))]
    (testing "transaction with code W and indirect ownership"
      (is (= 1 (count txns)))
      (let [t (first txns)]
        (is (= "W" (:coding t)))
        (is (= "5" (:form-type t)))
        (is (= 42.0 (:shares t)))
        (is (= "I" (:ownership-nature t)))
        (is (= "By spouse" (:nature-of-ownership t)))))
    (testing "holding rows are NOT double-counted as transactions"
      (is (= 1 (count txns))))
    (testing "non-derivative holding parsed"
      (is (= 1 (count holds)))
      (let [h (first holds)]
        (is (= "Common Stock" (:security-title h)))
        (is (= 60975.0 (:shares-owned h)))
        (is (= "D" (:ownership-nature h)))))))

;;; ---------------------------------------------------------------------------
;;; Full parse + form detection
;;; ---------------------------------------------------------------------------

(deftest parse-ownership-form-full-test
  (with-redefs [filing/filing-index
                (fn [_] {:files [{:name "ownership.xml" :type "3" :sequence "1"}]})
                filing/filing-document
                (fn [_ _ & _] form3-xml)]
    (let [result (edgar.forms.ownership/parse-ownership-form {:form "3"})]
      (testing ":form comes from the XML documentType"
        (is (= "3" (:form result))))
      (testing "period, issuer, owner, holdings all present"
        (is (= "2026-03-01" (:period-of-report result)))
        (is (= "Apple Inc." (get-in result [:issuer :name])))
        (is (= "Newstead Jennifer" (get-in result [:reporting-owner :name])))
        (is (= 1 (count (:holdings result))))
        (is (empty? (:transactions result)))))))

(deftest filing-obj-dispatch-test
  (testing "filing-obj methods registered for all six form types"
    (let [methods (set (keys (methods filing/filing-obj)))]
      (doseq [ft ["3" "3/A" "4" "4/A" "5" "5/A"]]
        (is (contains? methods ft) ft)))))

(deftest parse-form4-delegation-test
  (with-redefs [filing/filing-index
                (fn [_] {:files [{:name "ownership.xml" :type "4" :sequence "1"}]})
                filing/filing-document
                (fn [_ _ & _] form4-xml)]
    (testing "edgar.forms.form4/parse-form4 still works via delegation"
      (let [result (form4/parse-form4 {:form "4"})]
        (is (= "4" (:form result)))
        (is (= 1 (count (:transactions result))))))))

;;; ---------------------------------------------------------------------------
;;; XML document locator
;;; ---------------------------------------------------------------------------

(deftest ownership-xml-excludes-ixbrl-test
  (let [f #'edgar.forms.ownership/ownership-xml]
    (testing "prefers plain .xml over iXBRL _htm.xml instance doc"
      (with-redefs [filing/filing-index
                    (fn [_] {:files [{:name "aapl-20240115_htm.xml" :type "EX-101.INS" :sequence "2"}
                                     {:name "ownership.xml" :type "4" :sequence "1"}]
                             :formType "4"})
                    filing/filing-document
                    (fn [_ name & _] (str "fetched:" name))]
        (is (= "fetched:ownership.xml" (f {})))))
    (testing "returns nil when no qualifying xml exists — never feeds HTML to the XML parser"
      (with-redefs [filing/filing-index
                    (fn [_] {:files [{:name "primary.htm" :type "4" :sequence "1"}]
                             :formType "4"})
                    filing/filing-document
                    (fn [_ name & _] (str "fetched:" name))]
        (is (nil? (f {}))
            "falling back to an HTML doc would crash xml/parse downstream")))))
