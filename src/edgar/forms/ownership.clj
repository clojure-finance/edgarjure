(ns edgar.forms.ownership
  "Generic parser for SEC Section 16 ownership forms — Forms 3, 4, and 5
   (plus their /A amendments). All three share the ownershipDocument XML
   schema:

     Form 3 - initial statement: holdings only (nonDerivativeHolding /
              derivativeHolding), no transactions
     Form 4 - statement of changes: transactions (may also carry holdings)
     Form 5 - annual statement: transactions and holdings

   Registers filing-obj methods for \"3\" \"3/A\" \"4\" \"4/A\" \"5\" \"5/A\".

   Usage:
     (require '[edgar.forms.ownership])   ; side-effectful load registers methods
     (edgar.filing/filing-obj filing)
     ;=> {:form \"3\" :issuer {...} :reporting-owner {...}
     ;    :transactions [...] :holdings [...]}"
  (:require [edgar.filing :as filing]
            [clojure.xml :as xml]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; XML helpers (plain clojure.xml — no extra deps)
;;; ---------------------------------------------------------------------------

(defn- parse-xml-str [s]
  (xml/parse (java.io.ByteArrayInputStream. (.getBytes s "UTF-8"))))

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

(defn- parse-double-safe [s]
  (when s
    (try (parse-double s) (catch Exception _ nil))))

;;; ---------------------------------------------------------------------------
;;; Issuer and reporting owner
;;; ---------------------------------------------------------------------------

(defn- parse-issuer [root]
  (let [issuer (find-tag root :issuer)]
    {:cik (tag-text issuer :issuerCik)
     :name (tag-text issuer :issuerName)
     :ticker (tag-text issuer :issuerTradingSymbol)}))

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
;;; Transactions (Forms 4 and 5)
;;; ---------------------------------------------------------------------------

(defn- parse-non-derivative-transactions [root]
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
       :ownership-nature (nested-text t :ownershipNature :directOrIndirectOwnership :value)
       :nature-of-ownership (nested-text t :ownershipNature :natureOfOwnership :value)})))

(defn- parse-derivative-transactions [root]
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
       :ownership-nature (nested-text t :ownershipNature :directOrIndirectOwnership :value)
       :nature-of-ownership (nested-text t :ownershipNature :natureOfOwnership :value)})))

;;; ---------------------------------------------------------------------------
;;; Holdings (Form 3; also legal on Forms 4 and 5)
;;; ---------------------------------------------------------------------------

(defn- parse-non-derivative-holdings [root]
  (let [table (find-tag root :nonDerivativeTable)]
    (for [h (find-tags table :nonDerivativeHolding)]
      {:type :non-derivative
       :security-title (nested-text h :securityTitle :value)
       :shares-owned (parse-double-safe (nested-text h :postTransactionAmounts :sharesOwnedFollowingTransaction :value))
       :ownership-nature (nested-text h :ownershipNature :directOrIndirectOwnership :value)
       :nature-of-ownership (nested-text h :ownershipNature :natureOfOwnership :value)})))

(defn- parse-derivative-holdings [root]
  (let [table (find-tag root :derivativeTable)]
    (for [h (find-tags table :derivativeHolding)]
      {:type :derivative
       :security-title (nested-text h :securityTitle :value)
       :conversion-price (parse-double-safe (nested-text h :conversionOrExercisePrice :value))
       :exercise-date (nested-text h :exerciseDate :value)
       :expiration-date (nested-text h :expirationDate :value)
       :underlying-title (nested-text h :underlyingSecurity :underlyingSecurityTitle :value)
       :underlying-shares (parse-double-safe (nested-text h :underlyingSecurity :underlyingSecurityShares :value))
       :shares-owned (parse-double-safe (nested-text h :postTransactionAmounts :sharesOwnedFollowingTransaction :value))
       :ownership-nature (nested-text h :ownershipNature :directOrIndirectOwnership :value)
       :nature-of-ownership (nested-text h :ownershipNature :natureOfOwnership :value)})))

;;; ---------------------------------------------------------------------------
;;; XML document locator
;;; ---------------------------------------------------------------------------

(defn- ownership-xml
  "Locate and fetch the ownership XML document. Returns nil when the filing
   has no XML attachment (falling back to a non-XML document would only hand
   HTML to the XML parser)."
  [filing]
  (let [idx (filing/filing-index filing)
        docs (:files idx)
        xml-doc (first (filter (fn [{:keys [name]}]
                                 (and (str/ends-with? (str name) ".xml")
                                      (not (str/ends-with? (str name) "_htm.xml"))))
                               docs))]
    (when xml-doc
      (filing/filing-document filing (:name xml-doc) :raw? true))))

;;; ---------------------------------------------------------------------------
;;; Public parse entry point
;;; ---------------------------------------------------------------------------

(defn parse-ownership-form
  "Parse a Form 3/4/5 (or /A) filing map into a structured map.
   Returns nil when the filing has no XML document. Otherwise:
     {:form             \"3\" | \"4\" | \"5\" (from the XML documentType;
                        falls back to the filing's :form)
      :period-of-report \"YYYY-MM-DD\"
      :date-of-change   \"YYYY-MM-DD\" (when present)
      :issuer           {:cik ... :name ... :ticker ...}
      :reporting-owner  {:cik ... :name ... :is-director? ... :officer-title ...}
      :transactions     [{:type :non-derivative|:derivative ...} ...]
      :holdings         [{:type :non-derivative|:derivative ...} ...]}
   Forms 3 have holdings and no transactions; Forms 4/5 mainly transactions,
   possibly holdings as well."
  [filing]
  (when-let [raw (ownership-xml filing)]
    (let [root (parse-xml-str raw)]
      {:form (or (tag-text root :documentType) (:form filing))
       :period-of-report (tag-text root :periodOfReport)
       :date-of-change (tag-text root :dateOfOriginalSubmission)
       :issuer (parse-issuer root)
       :reporting-owner (parse-owner root)
       :transactions (concat (parse-non-derivative-transactions root)
                             (parse-derivative-transactions root))
       :holdings (concat (parse-non-derivative-holdings root)
                         (parse-derivative-holdings root))})))

;;; ---------------------------------------------------------------------------
;;; Register filing-obj methods
;;; ---------------------------------------------------------------------------

(doseq [form-type ["3" "3/A" "4" "4/A" "5" "5/A"]]
  (.addMethod ^clojure.lang.MultiFn filing/filing-obj form-type
              parse-ownership-form))
