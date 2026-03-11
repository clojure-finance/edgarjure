(ns edgar.forms.form4-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.forms.form4 :as form4]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; Fixture XML — minimal but representative Form 4
;;; ---------------------------------------------------------------------------

(def ^:private form4-xml
  "<?xml version=\"1.0\"?>
<ownershipDocument>
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

(defn- parse-root []
  (#'edgar.forms.form4/parse-xml-str form4-xml))

;;; ---------------------------------------------------------------------------
;;; parse-issuer
;;; ---------------------------------------------------------------------------

(deftest parse-issuer-test
  (let [root (parse-root)
        issuer (#'edgar.forms.form4/parse-issuer root)]
    (testing "extracts CIK"
      (is (= "0000320193" (:cik issuer))))
    (testing "extracts name"
      (is (= "Apple Inc." (:name issuer))))
    (testing "extracts ticker"
      (is (= "AAPL" (:ticker issuer))))))

;;; ---------------------------------------------------------------------------
;;; parse-owner
;;; ---------------------------------------------------------------------------

(deftest parse-owner-test
  (let [root (parse-root)
        owner (#'edgar.forms.form4/parse-owner root)]
    (testing "extracts owner CIK"
      (is (= "0001214128" (:cik owner))))
    (testing "extracts owner name"
      (is (= "COOK TIMOTHY D" (:name owner))))
    (testing "is-officer? is true"
      (is (true? (:is-officer? owner))))
    (testing "is-director? is false"
      (is (false? (:is-director? owner))))
    (testing "extracts officer title"
      (is (= "Chief Executive Officer" (:officer-title owner))))))

;;; ---------------------------------------------------------------------------
;;; parse-non-derivative
;;; ---------------------------------------------------------------------------

(deftest parse-non-derivative-test
  (let [root (parse-root)
        txns (vec (#'edgar.forms.form4/parse-non-derivative root))]
    (testing "returns one transaction"
      (is (= 1 (count txns))))
    (let [t (first txns)]
      (testing "type is :non-derivative"
        (is (= :non-derivative (:type t))))
      (testing "security title is Common Stock"
        (is (= "Common Stock" (:security-title t))))
      (testing "date is parsed"
        (is (= "2024-01-15" (:date t))))
      (testing "coding is S (sale)"
        (is (= "S" (:coding t))))
      (testing "shares is numeric"
        (is (= 50000.0 (:shares t))))
      (testing "price is numeric"
        (is (= 185.5 (:price t))))
      (testing "acquired-disposed is D"
        (is (= "D" (:acquired-disposed t))))
      (testing "shares-after is numeric"
        (is (= 1200000.0 (:shares-after t)))))))
