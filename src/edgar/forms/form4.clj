(ns edgar.forms.form4
  "Form 4 parser — Statement of Changes in Beneficial Ownership.
   Registers a filing-obj method for form type \"4\".

   Usage:
     (require '[edgar.forms.form4])   ; side-effectful load registers the method
     (edgar.filing/filing-obj filing) ; => {:form \"4\" :reporting-owner {...} :transactions [...]}"
  (:require [edgar.filing :as filing]
            [clojure.xml :as xml]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; XML helpers (plain clojure.xml — no extra deps)
;;; ---------------------------------------------------------------------------

(defn- parse-xml-str [s]
  (xml/parse (java.io.ByteArrayInputStream. (.getBytes s "UTF-8"))))

(defn- child-text
  "Return trimmed text of the first child element matching tag in an xml node."
  [node tag]
  (some-> (filter #(= tag (:tag %)) (:content node))
          first
          :content
          first
          str
          str/trim
          not-empty))

(defn- find-tag
  "Return the first descendant element with the given tag (depth-first)."
  [node tag]
  (when (map? node)
    (if (= tag (:tag node))
      node
      (some #(find-tag % tag) (:content node)))))

(defn- find-tags
  "Return all descendant elements with the given tag."
  [node tag]
  (when (map? node)
    (let [here (if (= tag (:tag node)) [node] [])]
      (into here (mapcat #(find-tags % tag) (:content node))))))

(defn- tag-text
  "Find a descendant node by tag and return its text content."
  [node tag]
  (some-> (find-tag node tag)
          :content
          first
          str
          str/trim
          not-empty))

(defn- nested-text
  "Walk a path of tags from node and return the leaf text."
  [node & tags]
  (let [leaf (reduce find-tag node tags)]
    (some-> leaf :content first str str/trim not-empty)))

;;; ---------------------------------------------------------------------------
;;; Reporting owner
;;; ---------------------------------------------------------------------------

(defn- parse-owner [root]
  (let [owner (find-tag root :reportingOwner)
        id (find-tag owner :reportingOwnerId)
        addr (find-tag owner :reportingOwnerAddress)
        rel (find-tag owner :reportingOwnerRelationship)]
    {:cik (tag-text id :rptOwnerCik)
     :name (tag-text id :rptOwnerName)
     :street (tag-text addr :rptOwnerStreet1)
     :city (tag-text addr :rptOwnerCity)
     :state (tag-text addr :rptOwnerState)
     :zip (tag-text addr :rptOwnerZipCode)
     :is-director? (= "1" (tag-text rel :isDirector))
     :is-officer? (= "1" (tag-text rel :isOfficer))
     :is-10pct? (= "1" (tag-text rel :isTenPercentOwner))
     :is-other? (= "1" (tag-text rel :isOther))
     :officer-title (tag-text rel :officerTitle)}))

;;; ---------------------------------------------------------------------------
;;; Issuer
;;; ---------------------------------------------------------------------------

(defn- parse-issuer [root]
  (let [issuer (find-tag root :issuer)]
    {:cik (tag-text issuer :issuerCik)
     :name (tag-text issuer :issuerName)
     :ticker (tag-text issuer :issuerTradingSymbol)}))

;;; ---------------------------------------------------------------------------
;;; Transactions
;;; ---------------------------------------------------------------------------

(defn- parse-double-safe [s]
  (when s
    (try (parse-double s) (catch Exception _ nil))))

(defn- parse-non-derivative [root]
  (let [table (find-tag root :nonDerivativeTable)]
    (for [t (find-tags table :nonDerivativeTransaction)]
      {:type :non-derivative
       :security-title (nested-text t :securityTitle :value)
       :date (nested-text t :transactionDate :value)
       :coding (nested-text t :transactionCoding :transactionCode)
       :form-type (nested-text t :transactionCoding :transactionFormType)
       :shares (parse-double-safe (nested-text t :transactionAmounts :transactionShares :value))
       :price (parse-double-safe (nested-text t :transactionAmounts :transactionPricePerShare :value))
       :acquired-disposed (nested-text t :transactionAmounts :transactionAcquiredDisposedCode :value)
       :shares-after (parse-double-safe (nested-text t :postTransactionAmounts :sharesOwnedFollowingTransaction :value))
       :ownership-nature (nested-text t :ownershipNature :directOrIndirectOwnership :value)})))

(defn- parse-derivative [root]
  (let [table (find-tag root :derivativeTable)]
    (for [t (find-tags table :derivativeTransaction)]
      {:type :derivative
       :security-title (nested-text t :securityTitle :value)
       :conversion-price (parse-double-safe (nested-text t :conversionOrExercisePrice :value))
       :date (nested-text t :transactionDate :value)
       :coding (nested-text t :transactionCoding :transactionCode)
       :form-type (nested-text t :transactionCoding :transactionFormType)
       :shares (parse-double-safe (nested-text t :transactionAmounts :transactionShares :value))
       :price (parse-double-safe (nested-text t :transactionAmounts :transactionPricePerShare :value))
       :acquired-disposed (nested-text t :transactionAmounts :transactionAcquiredDisposedCode :value)
       :exercise-date (nested-text t :exerciseDate :value)
       :expiration-date (nested-text t :expirationDate :value)
       :underlying-title (nested-text t :underlyingSecurity :underlyingSecurityTitle :value)
       :underlying-shares (parse-double-safe (nested-text t :underlyingSecurity :underlyingSecurityShares :value))
       :shares-after (parse-double-safe (nested-text t :postTransactionAmounts :sharesOwnedFollowingTransaction :value))
       :ownership-nature (nested-text t :ownershipNature :directOrIndirectOwnership :value)})))

;;; ---------------------------------------------------------------------------
;;; XML document locator
;;; ---------------------------------------------------------------------------

(defn- form4-xml [filing]
  (let [idx (filing/filing-index filing)
        docs (:files idx)
        xml-doc (or (first (filter #(str/ends-with? (str (:name %)) ".xml") docs))
                    (first docs))]
    (when xml-doc
      (filing/filing-document filing (:name xml-doc) :raw? true))))

;;; ---------------------------------------------------------------------------
;;; Public parse entry point
;;; ---------------------------------------------------------------------------

(defn parse-form4
  "Parse a Form 4 filing map into a structured map.
   Returns:
     {:form             \"4\"
      :period-of-report \"YYYY-MM-DD\"
      :date-of-change   \"YYYY-MM-DD\"
      :issuer           {:cik ... :name ... :ticker ...}
      :reporting-owner  {:cik ... :name ... :is-director? ... :officer-title ...}
      :transactions     [{:type :non-derivative|:derivative ...} ...]}"
  [filing]
  (when-let [raw (form4-xml filing)]
    (let [root (parse-xml-str raw)]
      {:form "4"
       :period-of-report (tag-text root :periodOfReport)
       :date-of-change (tag-text root :dateOfOriginalSubmission)
       :issuer (parse-issuer root)
       :reporting-owner (parse-owner root)
       :transactions (concat (parse-non-derivative root)
                             (parse-derivative root))})))

;;; ---------------------------------------------------------------------------
;;; Register filing-obj method
;;; ---------------------------------------------------------------------------

(defmethod filing/filing-obj "4" [filing]
  (parse-form4 filing))
