(ns edgar.reclass-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.reclass :as reclass]
            [edgar.financials :as fin]
            [tech.v3.dataset :as ds]))

;;; ---------------------------------------------------------------------------
;;; Formula evaluation
;;; ---------------------------------------------------------------------------

(defn- by-li [rows]
  (into {} (map (juxt :line-item identity)) rows))

(deftest eval-formula-test
  (let [f #'edgar.reclass/eval-formula
        g (by-li [{:line-item "A" :val 100.0}
                  {:line-item "B" :val 30.0}
                  {:line-item "Z" :val 0.0}])]
    (testing ":= copies the operand"
      (is (= {:val 100.0 :used ["A"]} (f [:= "A"] g))))
    (testing ":+ sums operands"
      (is (= {:val 130.0 :used ["A" "B"]} (f [:+ "A" "B"] g))))
    (testing ":- subtracts rest from first"
      (is (= {:val 70.0 :used ["A" "B"]} (f [:- "A" "B"] g))))
    (testing "missing required operand blocks the formula"
      (is (nil? (f [:- "A" "Missing"] g))))
    (testing "[:opt x] contributes 0 when absent and is not in :used"
      (is (= {:val 100.0 :used ["A"]} (f [:+ "A" [:opt "Missing"]] g))))
    (testing "[:opt x] contributes its value when present"
      (is (= {:val 130.0 :used ["A" "B"]} (f [:+ "A" [:opt "B"]] g))))
    (testing ":neg-sum negates the sum of present operands"
      (is (= {:val -130.0 :used ["A" "B"]} (f [:neg-sum "A" "B" "Missing"] g))))
    (testing ":neg-sum ignores zero-valued operands"
      (is (= {:val -100.0 :used ["A"]} (f [:neg-sum "A" "Z"] g))))
    (testing ":neg-sum is nil when no operand is present with a non-zero value"
      (is (nil? (f [:neg-sum "Missing" "Z"] g))))
    (testing ":max picks the largest present operand and names only it in :used"
      (is (= {:val 100.0 :used ["A"]} (f [:max "B" "Missing" "A"] g))))
    (testing ":max is nil when no operand is present"
      (is (nil? (f [:max "Missing" "Other"] g))))))

(deftest eval-formula-negated-operand-test
  (let [f #'edgar.reclass/eval-formula
        g (by-li [{:line-item "A" :val 100.0}
                  {:line-item "Net" :val -30.0}])]
    (testing "[:neg x] contributes the negated value and names x in :used"
      (is (= {:val 130.0 :used ["A" "Net"]} (f [:+ "A" [:neg "Net"]] g))))
    (testing "[:opt [:neg x]] negates when present"
      (is (= {:val 130.0 :used ["A" "Net"]} (f [:+ "A" [:opt [:neg "Net"]]] g))))
    (testing "[:opt [:neg x]] contributes 0 when absent"
      (is (= {:val 100.0 :used ["A"]} (f [:+ "A" [:opt [:neg "Missing"]]] g))))
    (testing "a required [:neg x] blocks the formula when absent"
      (is (nil? (f [:+ "A" [:neg "Missing"]] g))))))

;;; ---------------------------------------------------------------------------
;;; Guards
;;; ---------------------------------------------------------------------------

(deftest guard-passes-test
  (let [f #'edgar.reclass/guard-passes?
        g (by-li [{:line-item "D&A" :val 10.0}
                  {:line-item "COGS" :val 100.0 :concept "CostOfRevenue"}
                  {:line-item "Derived" :val 5.0 :concept nil}])]
    (testing ":lt passes when strictly less"
      (is (f [:lt "D&A" "COGS"] g))
      (is (not (f [:lt "COGS" "D&A"] g))))
    (testing ":lt fails when an operand is missing"
      (is (not (f [:lt "D&A" "Missing"] g))))
    (testing ":concept-not-in fails when the concept is in the set"
      (is (not (f [:concept-not-in "COGS" #{"CostOfRevenue"}] g))))
    (testing ":concept-not-in passes when the concept is not in the set"
      (is (f [:concept-not-in "COGS" #{"SomethingElse"}] g)))
    (testing ":concept-not-in passes for rows without a concept (derived rows)"
      (is (f [:concept-not-in "Derived" #{"CostOfRevenue"}] g)))
    (testing ":concept-in requires an existing row with a concept in the set"
      (is (f [:concept-in "COGS" #{"CostOfRevenue"}] g))
      (is (not (f [:concept-in "COGS" #{"SomethingElse"}] g))))
    (testing ":concept-in fails for missing rows and derived (nil-concept) rows"
      (is (not (f [:concept-in "Missing" #{"CostOfRevenue"}] g)))
      (is (not (f [:concept-in "Derived" #{"CostOfRevenue"}] g))))
    (testing ":present / :absent check line-item existence"
      (is (f [:present "COGS"] g))
      (is (not (f [:present "Missing"] g)))
      (is (f [:absent "Missing"] g))
      (is (not (f [:absent "COGS"] g))))
    (testing "unknown guard op fails closed"
      (is (not (f [:unknown "A" "B"] g))))))

(deftest guard-lte-and-ties-test
  (let [f #'edgar.reclass/guard-passes?
        g (by-li [{:line-item "Revenue" :val 1000.0}
                  {:line-item "Cost of Revenue" :val 300.0}
                  {:line-item "Excise Taxes" :val 250.0}
                  {:line-item "Gross Profit" :val 451.0}
                  {:line-item "Goodwill" :val 250.0}])]
    (testing ":lte passes on equality and strictly-less, fails otherwise"
      (is (f [:lte "Goodwill" "Excise Taxes"] g))
      (is (f [:lte "Cost of Revenue" "Revenue"] g))
      (is (not (f [:lte "Revenue" "Cost of Revenue"] g)))
      (is (not (f [:lte "Missing" "Revenue"] g))))
    (testing ":ties passes when the formula is within tolerance of the label"
      (is (f [:ties [:- "Revenue" "Cost of Revenue" "Excise Taxes"] "Gross Profit" 0.005] g)
          "450 vs 451: 0.2% off"))
    (testing ":ties fails outside tolerance"
      (is (not (f [:ties [:- "Revenue" "Cost of Revenue"] "Gross Profit" 0.005] g))
          "700 vs 451"))
    (testing ":ties fails when the formula or the label cannot be resolved"
      (is (not (f [:ties [:- "Revenue" "Missing"] "Gross Profit" 0.005] g)))
      (is (not (f [:ties [:- "Revenue" "Cost of Revenue"] "Missing" 0.005] g))))
    (testing ":at-least compares the formula to a share of the label's value"
      (is (f [:at-least [:+ "Cost of Revenue"] "Revenue" 0.3] g) "300 >= 0.3 x 1000")
      (is (not (f [:at-least [:+ "Cost of Revenue"] "Revenue" 0.5] g)) "300 < 500"))
    (testing ":at-least passes when the label is absent, fails when the formula is"
      (is (f [:at-least [:+ "Cost of Revenue"] "Missing" 0.5] g))
      (is (not (f [:at-least [:+ "Missing"] "Revenue" 0.1] g))))))

;;; ---------------------------------------------------------------------------
;;; apply-ruleset — engine mechanics
;;; ---------------------------------------------------------------------------

(def ^:private period {:unit "USD" :start "2014-09-28" :end "2015-09-26"
                       :form "10-K" :filed "2015-10-28"})

(defn- row [li val & [extra]]
  (merge period {:line-item li :val val :method :direct} extra))

(deftest apply-ruleset-basic-test
  (let [rules {:rules [{:id :cogs-ex-da
                        :target "COGS (Compustat)"
                        :formula [:- "Cost of Revenue" "D&A"]
                        :guards [[:lt "D&A" "Cost of Revenue"]]}]}
        rows [(row "Cost of Revenue" 100.0)]
        aux [(row "D&A" 10.0)]
        result (vec (reclass/apply-ruleset rows aux rules))
        cogs (first (filter #(= "COGS (Compustat)" (:line-item %)) result))]
    (testing "reclassified row is added with the right value"
      (is (= 90.0 (:val cogs))))
    (testing "provenance fields are set"
      (is (= :reclassified (:method cogs)))
      (is (= :cogs-ex-da (:rule cogs)))
      (is (= ["Cost of Revenue" "D&A"] (:derived-from cogs)))
      (is (nil? (:concept cogs))))
    (testing "original rows are preserved"
      (is (some #(= "Cost of Revenue" (:line-item %)) result)))
    (testing "aux rows are not emitted"
      (is (not-any? #(= "D&A" (:line-item %)) result)))))

(deftest special-items-combined-restructuring-test
  ;; a filer tagging BOTH a combined restructuring+impairment concept and a
  ;; separate impairment concept must not have the impairment counted twice
  (let [rows [(row "Operating Income" 500.0 {:concept "OperatingIncomeLoss"})
              (row "Restructuring Charges" 80.0
                   {:concept "RestructuringCostsAndAssetImpairmentCharges"})
              (row "Impairment Charges" 30.0 {:concept "AssetImpairmentCharges"})]
        result (vec (reclass/apply-ruleset rows [] reclass/compustat-income-ruleset))
        spi (first (filter #(= "Special Items (Compustat)" (:line-item %)) result))]
    (is (= -80.0 (:val spi)) "impairment already inside the combined tag")
    (is (= :special-items-combined-restructuring (:rule spi))))
  ;; separate tags: both count
  (let [rows [(row "Operating Income" 500.0 {:concept "OperatingIncomeLoss"})
              (row "Restructuring Charges" 80.0 {:concept "RestructuringCharges"})
              (row "Impairment Charges" 30.0 {:concept "AssetImpairmentCharges"})]
        spi (->> (reclass/apply-ruleset rows [] reclass/compustat-income-ruleset)
                 (filter #(= "Special Items (Compustat)" (:line-item %))) first)]
    (is (= -110.0 (:val spi)))
    (is (= :special-items (:rule spi)))))

(deftest special-items-aggregate-impairment-test
  ;; modeled on INTC FY2024 (verified against the 10-K text): the
  ;; AssetImpairmentCharges aggregate (3631) already contains the goodwill
  ;; impairment that is also tagged separately (2984)
  (let [spi (fn [rows]
              (->> (reclass/apply-ruleset rows [] reclass/compustat-income-ruleset)
                   (filter #(= "Special Items (Compustat)" (:line-item %))) first))
        base [(row "Operating Income" -11678.0 {:concept "OperatingIncomeLoss"})
              (row "Restructuring Charges" 3491.0 {:concept "RestructuringCharges"})
              (row "Impairment Charges" 3631.0 {:concept "AssetImpairmentCharges"})]]
    (testing "goodwill inside the aggregate is not counted twice"
      (let [s (spi (conj base (row "Goodwill Impairment" 2984.0
                                   {:concept "GoodwillImpairmentLoss"})))]
        (is (= -7122.0 (:val s)))
        (is (= :special-items-aggregate-impairment (:rule s)))))
    (testing "a goodwill amount larger than the aggregate is added separately"
      (let [s (spi (conj base (row "Goodwill Impairment" 5000.0
                                   {:concept "GoodwillImpairmentLoss"})))]
        (is (= -12122.0 (:val s)))
        (is (= :special-items (:rule s)))))
    (testing "a narrower impairment concept keeps goodwill separate"
      (let [s (spi [(row "Impairment Charges" 300.0
                         {:concept "ImpairmentOfLongLivedAssetsHeldAndUsed"})
                    (row "Goodwill Impairment" 200.0 {:concept "GoodwillImpairmentLoss"})])]
        (is (= -500.0 (:val s)))
        (is (= :special-items (:rule s)))))
    (testing "no goodwill tag: the general rule applies unchanged"
      (let [s (spi base)]
        (is (= -7122.0 (:val s)))
        (is (= :special-items (:rule s)))))))

(deftest apply-ruleset-statement-row-shadows-aux-test
  ;; an income statement carrying its own "D&A" line (e.g. via custom
  ;; :concepts chains); the cash-flow aux row with the same label must NOT
  ;; shadow it as a rule operand
  (let [rules {:rules [{:id :dp
                        :target "DP (Compustat)"
                        :formula [:= "D&A"]}]}
        rows [(row "D&A" 100.0 {:concept "DepreciationAndAmortization"})]
        aux [(row "D&A" 120.0)]
        result (vec (reclass/apply-ruleset rows aux rules))
        dp (first (filter #(= "DP (Compustat)" (:line-item %)) result))
        da-rows (filter #(= "D&A" (:line-item %)) result)]
    (testing "the statement row wins the operand lookup"
      (is (= 100.0 (:val dp))))
    (testing "the statement row survives in the output, the aux row does not"
      (is (= [100.0] (mapv :val da-rows))))))

(deftest apply-ruleset-guard-blocks-test
  (let [rules {:rules [{:id :cogs-ex-da
                        :target "COGS (Compustat)"
                        :formula [:- "Cost of Revenue" "D&A"]
                        :guards [[:lt "D&A" "Cost of Revenue"]]}]}
        rows [(row "Cost of Revenue" 5.0)]
        aux [(row "D&A" 10.0)]
        result (vec (reclass/apply-ruleset rows aux rules))]
    (testing "rule does not fire when the guard fails (D&A > COGS)"
      (is (not-any? #(= "COGS (Compustat)" (:line-item %)) result)))))

(deftest apply-ruleset-sequential-visibility-test
  ;; regression: :special-items must be visible to :oiadp within the SAME
  ;; pass, otherwise [:opt "SPI"] evaluates to 0 and emits OI unadjusted
  ;; (found live on MSFT FY2012: the 6193 goodwill impairment was ignored)
  (let [rules {:rules [{:id :special-items
                        :target "SPI"
                        :formula [:neg-sum "Restructuring Charges"]}
                       {:id :oiadp
                        :target "OIADP"
                        :formula [:- "Operating Income" [:opt "SPI"]]}]}
        rows [(row "Operating Income" 100.0)
              (row "Restructuring Charges" 25.0)]
        result (vec (reclass/apply-ruleset rows [] rules))
        oiadp (first (filter #(= "OIADP" (:line-item %)) result))]
    (testing "special items are added back to operating income"
      (is (= 125.0 (:val oiadp))))
    (testing "the SPI operand appears in :derived-from"
      (is (= ["Operating Income" "SPI"] (:derived-from oiadp))))))

(deftest apply-ruleset-rule-order-priority-test
  (let [rules {:rules [{:id :first
                        :target "XSGA"
                        :formula [:= "SG&A Expense"]}
                       {:id :second
                        :target "XSGA"
                        :formula [:= "General and Administrative Expense"]}]}
        both [(row "SG&A Expense" 50.0)
              (row "General and Administrative Expense" 20.0)]
        only-2nd [(row "General and Administrative Expense" 20.0)]]
    (testing "earlier rule wins when both could fire"
      (let [xsga (->> (reclass/apply-ruleset both [] rules)
                      (filter #(= "XSGA" (:line-item %))) first)]
        (is (= 50.0 (:val xsga)))
        (is (= :first (:rule xsga)))))
    (testing "later rule fires as fallback"
      (let [xsga (->> (reclass/apply-ruleset only-2nd [] rules)
                      (filter #(= "XSGA" (:line-item %))) first)]
        (is (= 20.0 (:val xsga)))
        (is (= :second (:rule xsga)))))))

(deftest apply-ruleset-period-isolation-test
  (let [rules {:rules [{:id :r :target "T" :formula [:- "A" "B"]}]}
        rows [(row "A" 100.0)
              (row "B" 10.0)
              ;; different period: only A present -> no T for it
              (row "A" 200.0 {:start "2013-09-29" :end "2014-09-27"})]
        result (vec (reclass/apply-ruleset rows [] rules))
        ts (filter #(= "T" (:line-item %)) result)]
    (testing "rule fires only in the period group with all operands"
      (is (= 1 (count ts)))
      (is (= 90.0 (:val (first ts))))
      (is (= "2015-09-26" (:end (first ts)))))))

(deftest apply-ruleset-passthrough-test
  (let [rows [(row "Revenue" 100.0)]]
    (testing "nil ruleset returns rows unchanged"
      (is (= rows (reclass/apply-ruleset rows [] nil))))
    (testing "empty rules returns rows unchanged"
      (is (= rows (reclass/apply-ruleset rows [] {:rules []}))))))

;;; ---------------------------------------------------------------------------
;;; Compustat ruleset — EDN loading and end-to-end application
;;; ---------------------------------------------------------------------------

(deftest compustat-ruleset-loaded-test
  (let [rs reclass/compustat-income-ruleset]
    (testing "ruleset loads from EDN with metadata"
      (is (= :compustat (:ruleset rs)))
      (is (= :income (:statement rs)))
      (is (string? (:version rs))))
    (testing "cash-flow aux declares the D&A and DP-component dependencies"
      (is (= ["D&A" "Depreciation" "Amortization of Intangibles"]
             (get-in rs [:aux :cash-flow]))))
    (testing "all documented targets are present"
      (is (= #{"DP (Compustat)" "COGS (Compustat)" "XSGA (Compustat)"
               "Gross Profit (Compustat)" "XOPR (Compustat)"
               "Special Items (Compustat)" "OIADP (Compustat)"
               "OIBDP (Compustat)" "Revenue (Compustat)" "XRD (Compustat)"}
             (set (map :target (:rules rs))))))
    (testing "the FSDS spec declares the placement markers and extension labels"
      (is (= #{"D&A Presented on Income Statement" "R&D Not Presented on Income Statement"}
             (set (map :label (get-in rs [:fsds :markers])))))
      (is (every? (comp seq :tags) (get-in rs [:fsds :markers])))
      (is (seq (get-in rs [:fsds :extension-labels]))))
    (testing "extension patterns match the expense tags, not tags merely containing them"
      (let [label-of (fn [tag] (some (fn [[p l]] (when (re-find (re-pattern p) tag) l))
                                     (get-in rs [:fsds :extension-labels])))]
        (is (= "Fulfillment Expense" (label-of "FulfillmentExpense")))
        (is (= "Technology and Content Expense" (label-of "TechnologyAndInfrastructureExpense")))
        (is (nil? (label-of "StockBasedCompensationExpenseAssociatedWithFulfillmentExpense")))
        (is (nil? (label-of "ShareBasedCompensationTechnologyAndContentExpense")))))
    (testing "rule ids are unique"
      (let [ids (map :id (:rules rs))]
        (is (= (count ids) (count (set ids))))))))

(deftest ruleset-for-test
  (testing "income and balance have rulesets, cash flow does not"
    (is (some? (reclass/ruleset-for :income)))
    (is (some? (reclass/ruleset-for :balance)))
    (is (nil? (reclass/ruleset-for :cash-flow)))))

;;; ---------------------------------------------------------------------------
;;; Compustat balance-sheet ruleset
;;; ---------------------------------------------------------------------------

(def ^:private instant {:unit "USD" :start nil :end "2015-09-26"
                        :form "10-K" :filed "2015-10-28"})

(defn- irow [li val & [extra]]
  (merge instant {:line-item li :val val :method :direct} extra))

(deftest compustat-balance-ruleset-loaded-test
  (let [rs reclass/compustat-balance-ruleset]
    (testing "ruleset loads from EDN with metadata"
      (is (= :compustat (:ruleset rs)))
      (is (= :balance (:statement rs))))
    (testing "no cross-statement aux required"
      (is (nil? (:aux rs))))
    (testing "rule ids are unique"
      (let [ids (map :id (:rules rs))]
        (is (= (count ids) (count (set ids))))))))

(deftest compustat-balance-end-to-end-test
  ;; a filer with AOCI, both NCI kinds, mezzanine preferred, finance leases
  (let [rows [(irow "Retained Earnings" 500.0 {:concept "RetainedEarningsAccumulatedDeficit"})
              (irow "AOCI" -60.0 {:concept "AccumulatedOtherComprehensiveIncomeLossNetOfTax"})
              (irow "Noncontrolling Interest" 30.0 {:concept "MinorityInterest"})
              (irow "Redeemable Noncontrolling Interest" 25.0
                    {:concept "RedeemableNoncontrollingInterestEquityCarryingAmount"})
              (irow "Preferred Stock" 10.0 {:concept "PreferredStockValue"})
              (irow "Redeemable Preferred Stock" 40.0
                    {:concept "RedeemablePreferredStockCarryingAmount"})
              (irow "Stockholders Equity" 800.0 {:concept "StockholdersEquity"})
              (irow "Current Debt" 100.0 {:concept "DebtCurrent"})
              (irow "Finance Lease Liability (Current)" 15.0
                    {:concept "FinanceLeaseLiabilityCurrent"})
              (irow "Long-Term Debt" 400.0 {:concept "LongTermDebtNoncurrent"})
              (irow "Finance Lease Liability (Non-Current)" 80.0
                    {:concept "FinanceLeaseLiabilityNoncurrent"})
              (irow "Accounts Receivable" 200.0 {:concept "AccountsReceivableNetCurrent"})
              (irow "Nontrade Receivables" 35.0 {:concept "NontradeReceivablesCurrent"})
              (irow "Income Taxes Receivable" 5.0 {:concept "IncomeTaxesReceivable"})
              (irow "Cash and Equivalents" 120.0
                    {:concept "CashAndCashEquivalentsAtCarryingValue"})
              (irow "Short-Term Investments" 70.0 {:concept "ShortTermInvestments"})]
        result (vec (reclass/apply-ruleset rows [] reclass/compustat-balance-ruleset))
        v (fn [li] (:val (first (filter #(= li (:line-item %)) result))))]
    (is (= 440.0 (v "RE (Compustat)")) "REUNA + AOCI")
    (is (= 25.0 (v "MIB (Compustat)")) "mezzanine NCI only")
    (is (= 30.0 (v "MIBN (Compustat)")) "equity-section NCI")
    (is (= 55.0 (v "MIBT (Compustat)")) "MIB + MIBN")
    (is (= 115.0 (v "DLC (Compustat)")) "DebtCurrent + current finance leases")
    (is (= 480.0 (v "DLTT (Compustat)")) "noncurrent debt + noncurrent finance leases")
    (is (= 240.0 (v "RECT (Compustat)")) "trade + nontrade + tax refunds")
    (is (= 50.0 (v "PSTK (Compustat)")) "equity preferred + mezzanine preferred")
    (is (= 840.0 (v "SEQ (Compustat)")) "parent equity + mezzanine preferred")
    (is (= 790.0 (v "CEQ (Compustat)")) "SEQ - PSTK")
    (is (= 870.0 (v "TEQ (Compustat)")) "SEQ + MIBN (MIB stays outside)")
    (is (= 190.0 (v "CHE (Compustat)")) "cash + short-term investments")))

(deftest compustat-balance-guards-test
  (testing "RECT copies a total-receivables concept unchanged"
    (let [rows [(irow "Accounts Receivable" 250.0 {:concept "ReceivablesNetCurrent"})
                (irow "Income Taxes Receivable" 5.0 {:concept "IncomeTaxesReceivable"})]
          rect (->> (reclass/apply-ruleset rows [] reclass/compustat-balance-ruleset)
                    (filter #(= "RECT (Compustat)" (:line-item %))) first)]
      (is (= 250.0 (:val rect)))
      (is (= :rect-total (:rule rect)))))
  (testing "DLTT subtracts the current portion from a combined LongTermDebt tag"
    (let [rows [(irow "Long-Term Debt" 500.0 {:concept "LongTermDebt"})
                (irow "Current Portion of Long-Term Debt" 50.0
                      {:concept "LongTermDebtCurrent"})]
          dltt (->> (reclass/apply-ruleset rows [] reclass/compustat-balance-ruleset)
                    (filter #(= "DLTT (Compustat)" (:line-item %))) first)]
      (is (= 450.0 (:val dltt)))
      (is (= :dltt-from-combined (:rule dltt)))))
  (testing "SEQ for a filer tagging only the including-NCI equity concept"
    ;; the chains label that concept "Total Equity"; the standardized-view
    ;; identities re-derive parent equity before the rules run
    (let [f #'edgar.financials/apply-identities
          rows (f [(irow "Total Equity" 830.0
                         {:concept "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest"})
                   (irow "Noncontrolling Interest" 30.0 {:concept "MinorityInterest"})]
                  fin/balance-sheet-identities)
          result (vec (reclass/apply-ruleset rows [] reclass/compustat-balance-ruleset))
          v (fn [li] (first (filter #(= li (:line-item %)) result)))]
      (is (= 800.0 (:val (v "Stockholders Equity"))) "parent equity re-derived")
      (is (= :derived (:method (v "Stockholders Equity"))))
      (is (= 800.0 (:val (v "SEQ (Compustat)"))) "SEQ excludes NCI")
      (is (= :seq (:rule (v "SEQ (Compustat)"))))
      (is (= 830.0 (:val (v "TEQ (Compustat)"))) "TEQ = SEQ + MIBN, NCI counted once")))
  (testing "post-ASC-842 operating leases fold into DLC and DLTT"
    ;; empirically confirmed twice (2026-07-31 and the 2026-08-01 A/B):
    ;; current Compustat vintages include operating-lease liabilities in debt
    (let [rows [(irow "Current Debt" 100.0 {:concept "DebtCurrent"})
                (irow "Operating Lease Liability (Current)" 20.0
                      {:concept "OperatingLeaseLiabilityCurrent"})
                (irow "Long-Term Debt" 400.0 {:concept "LongTermDebtNoncurrent"})
                (irow "Operating Lease Liability (Non-Current)" 90.0
                      {:concept "OperatingLeaseLiabilityNoncurrent"})]
          result (vec (reclass/apply-ruleset rows [] reclass/compustat-balance-ruleset))
          v (fn [li] (first (filter #(= li (:line-item %)) result)))]
      (is (= 120.0 (:val (v "DLC (Compustat)"))))
      (is (= 490.0 (:val (v "DLTT (Compustat)"))))))
  (testing "OtherLongTermDebt is not added to DLTT (component of LongTermDebtNoncurrent)"
    (let [rows [(irow "Long-Term Debt" 400.0 {:concept "LongTermDebtNoncurrent"})
                (irow "Other Long-Term Debt" 60.0 {:concept "OtherLongTermDebt"})]
          dltt (->> (reclass/apply-ruleset rows [] reclass/compustat-balance-ruleset)
                    (filter #(= "DLTT (Compustat)" (:line-item %))) first)]
      (is (= 400.0 (:val dltt)) "adding it double-counts — confirmed by A/B validation")))
  (testing "DLC does not double-count leases already inside a combined CPLTD tag"
    ;; Current Debt is derived (CPLTD + STB) and the CPLTD concept already
    ;; includes capital leases — the separate lease tag must not be added again
    (let [f #'edgar.financials/apply-identities
          rows (f [(irow "Current Portion of Long-Term Debt" 50.0
                         {:concept "LongTermDebtAndCapitalLeaseObligationsCurrent"})
                   (irow "Short-Term Borrowings" 20.0
                         {:concept "ShortTermBorrowings"})
                   (irow "Capital Lease Obligation (Current)" 15.0
                         {:concept "CapitalLeaseObligationsCurrent"})]
                  fin/balance-sheet-identities)
          dlc (->> (reclass/apply-ruleset rows [] reclass/compustat-balance-ruleset)
                   (filter #(= "DLC (Compustat)" (:line-item %))) first)]
      (is (= 70.0 (:val dlc)) "CPLTD + STB only, leases not re-added")
      (is (= :dlc-cpltd-lease-included (:rule dlc)))))
  (testing "DLC still adds lease tags onto a direct DebtCurrent row"
    (let [rows [(irow "Current Debt" 100.0 {:concept "DebtCurrent"})
                (irow "Capital Lease Obligation (Current)" 15.0
                      {:concept "CapitalLeaseObligationsCurrent"})]
          dlc (->> (reclass/apply-ruleset rows [] reclass/compustat-balance-ruleset)
                   (filter #(= "DLC (Compustat)" (:line-item %))) first)]
      (is (= 115.0 (:val dlc)))
      (is (= :dlc (:rule dlc)))))
  (testing "CHE copies a combined cash-and-investments concept unchanged"
    (let [rows [(irow "Cash and Equivalents" 190.0
                      {:concept "CashCashEquivalentsAndShortTermInvestments"})
                (irow "Short-Term Investments" 70.0 {:concept "ShortTermInvestments"})]
          che (->> (reclass/apply-ruleset rows [] reclass/compustat-balance-ruleset)
                   (filter #(= "CHE (Compustat)" (:line-item %))) first)]
      (is (= 190.0 (:val che)))
      (is (= :che-combined (:rule che))))))

(deftest current-debt-identity-test
  ;; the standardized-view identities build Current Debt from components as a
  ;; SUM (Compustat DLC = DD1 + NP), with single-component fallbacks, and the
  ;; sequential engine emits only the first matching identity per target
  (let [f #'edgar.financials/apply-identities
        both [(irow "Current Portion of Long-Term Debt" 50.0)
              (irow "Short-Term Borrowings" 20.0)]
        one [(irow "Short-Term Borrowings" 20.0)]
        v (fn [rows]
            (let [ds (filter #(= "Current Debt" (:line-item %))
                             (f rows fin/balance-sheet-identities))]
              [(count ds) (:val (first ds))]))]
    (testing "components sum when both are present"
      (is (= [1 70.0] (v both))))
    (testing "single component falls through, still exactly one row"
      (is (= [1 20.0] (v one))))))

(deftest compustat-end-to-end-test
  ;; values modeled on AAPL FY2015 (verified live against the SEC API and a
  ;; 2016-vintage FUNDA extract: COGS 140089 - 9200 D&A = Compustat 130889
  ;; ... here scaled-down synthetic analogues with the same structure)
  (let [rows [(row "Revenue" 1000.0 {:concept "SalesRevenueNet"})
              (row "Cost of Revenue" 600.0 {:concept "CostOfRevenue"})
              (row "SG&A Expense" 100.0 {:concept "SellingGeneralAndAdministrativeExpense"})
              (row "R&D Expense" 50.0 {:concept "ResearchAndDevelopmentExpense"})
              (row "Operating Income" 250.0 {:concept "OperatingIncomeLoss"})
              (row "Restructuring Charges" 20.0 {:concept "RestructuringCharges"})]
        aux [(row "D&A" 40.0)]
        result (vec (reclass/apply-ruleset rows aux reclass/compustat-income-ruleset))
        v (fn [li] (:val (first (filter #(= li (:line-item %)) result))))]
    (is (= 40.0 (v "DP (Compustat)")))
    (is (= 560.0 (v "COGS (Compustat)")) "COGS - D&A")
    (is (= 150.0 (v "XSGA (Compustat)")) "SG&A + R&D")
    (is (= 440.0 (v "Gross Profit (Compustat)")) "Revenue - Compustat COGS")
    (is (= 710.0 (v "XOPR (Compustat)")) "COGS-c + XSGA-c")
    (is (= -20.0 (v "Special Items (Compustat)")) "negated charges")
    (is (= 270.0 (v "OIADP (Compustat)")) "OI - SPI = OI + charges")
    (is (= 310.0 (v "OIBDP (Compustat)")) "OIADP + D&A")
    (testing "no excise taxes tagged -> Revenue (Compustat) is the revenue as tagged"
      (is (= 1000.0 (v "Revenue (Compustat)"))))))

(deftest compustat-dp-components-preference-test
  ;; modeled on AMZN FY2023 (verified live): cash-flow D&A 48663 includes
  ;; capitalized-content amortization, but Compustat DP = Depreciation 30225
  ;; + intangible amortization 706 = 30931; COGS must use the component DP
  (let [rows [(row "Cost of Revenue" 304739.0 {:concept "CostOfGoodsAndServicesSold"})]
        aux [(row "D&A" 48663.0)
             (row "Depreciation" 30225.0)
             (row "Amortization of Intangibles" 706.0)]
        result (vec (reclass/apply-ruleset rows aux reclass/compustat-income-ruleset))
        v (fn [li] (:val (first (filter #(= li (:line-item %)) result))))
        dp-row (first (filter #(= "DP (Compustat)" (:line-item %)) result))]
    (testing "component sum wins over the broader combined D&A tag"
      (is (= 30931.0 (:val dp-row)))
      (is (= :dp-components (:rule dp-row))))
    (testing "COGS uses the component DP"
      (is (= 273808.0 (v "COGS (Compustat)"))))
    (testing "falls back to := D&A when no Depreciation component exists"
      (let [result2 (vec (reclass/apply-ruleset rows [(row "D&A" 48663.0)]
                                                reclass/compustat-income-ruleset))
            dp2 (first (filter #(= "DP (Compustat)" (:line-item %)) result2))]
        (is (= 48663.0 (:val dp2)))
        (is (= :dp (:rule dp2)))))))

(deftest compustat-dp-tag-selection-test
  ;; modeled on UPS: a footnote-scope Depreciation fragment and a footnote
  ;; DepreciationDepletionAndAmortization beside the cash-flow
  ;; DepreciationAndAmortization that equals Compustat DP
  (let [dp (fn [aux] (->> (reclass/apply-ruleset [] aux reclass/compustat-income-ruleset)
                          (filter #(= "DP (Compustat)" (:line-item %)))
                          first ((juxt :val :rule))))]
    (testing "a Depreciation tag under half of a combined D&A tag is a fragment"
      (is (= [2360.0 :dp-largest-tag]
             (dp [(row "Depreciation" 212.0) (row "Amortization of Intangibles" 377.0)
                  (row "DepreciationAndAmortization" 2360.0)]))))
    (testing "the largest combined D&A tag wins when several are tagged"
      (is (= [3746.0 :dp-largest-tag]
             (dp [(row "D&A" 3000.0)
                  (row "DepreciationDepletionAndAmortization" 3000.0)
                  (row "DepreciationAndAmortization" 3746.0)]))))
    (testing "components at least half of the combined tag still win"
      (is (= [1050.0 :dp-components]
             (dp [(row "Depreciation" 1000.0) (row "Amortization of Intangibles" 50.0)
                  (row "DepreciationDepletionAndAmortization" 1100.0)]))))))

(deftest compustat-oil-gas-presentation-test
  ;; modeled on CVX FY2023 (verified against a FUNDA extract): COGS =
  ;; purchased crude + operating expenses + taxes other than on income;
  ;; XSGA = SG&A + exploration; no operating-income line, so OIADP comes
  ;; bottom-up once XOPR + DP ties to the reported total costs
  (let [rows [(row "Revenue" 196913.0 {:concept "RevenueFromContractWithCustomerExcludingAssessedTax"})
              (row "Cost of Revenue" 119196.0 {:concept "CostOfGoodsAndServicesSold"})
              (row "SG&A Expense" 4141.0 {:concept "SellingGeneralAndAdministrativeExpense"})
              (row "R&D Expense" 320.0 {:concept "ResearchAndDevelopmentExpense"})
              (row "Total Costs and Expenses" 171365.0 {:concept "CostsAndExpenses"})]
        aux [(row "D&A" 17326.0)
             (row "ExplorationExpense" 914.0)
             (row "OperatingCostsAndExpenses" 24887.0)
             (row "TaxesOther" 4220.0)]
        result (vec (reclass/apply-ruleset rows aux reclass/compustat-income-ruleset))
        v (fn [li] ((juxt :val :rule) (first (filter #(= li (:line-item %)) result))))]
    (testing "COGS and XSGA follow the oil presentation"
      (is (= [148303.0 :cogs-oil-gas] (v "COGS (Compustat)")))
      (is (= [5055.0 :xsga-oil-gas] (v "XSGA (Compustat)"))))
    (testing "OIADP bottom-up when XOPR + DP ties to total costs"
      (is (= [(- 196913.0 153358.0 17326.0) :oiadp-bottom-up] (v "OIADP (Compustat)")))
      (is (= [(- 196913.0 153358.0) :oibdp] (v "OIBDP (Compustat)"))))
    (testing "no bottom-up OIADP when the reconstruction misses costs"
      (let [result2 (reclass/apply-ruleset (assoc-in rows [4 :val] 190000.0) aux
                                           reclass/compustat-income-ruleset)]
        (is (not-any? #(= "OIADP (Compustat)" (:line-item %)) result2))))))

(deftest compustat-total-costs-presentation-test
  ;; modeled on UPS FY2025: expenses by nature, no cost-of-revenue or SG&A
  ;; line; Compustat COGS = XOPR = total costs - DP - special-item charges
  (let [rows [(row "Revenue" 88661.0 {:concept "RevenueFromContractWithCustomerExcludingAssessedTax"})
              (row "Total Costs and Expenses" 80794.0 {:concept "CostsAndExpenses"})
              (row "Operating Income" 7867.0 {:concept "OperatingIncomeLoss"})
              (row "Impairment Charges" 201.0 {:concept "AssetImpairmentCharges"})]
        aux [(row "DepreciationAndAmortization" 3746.0)]
        result (vec (reclass/apply-ruleset rows aux reclass/compustat-income-ruleset))
        v (fn [li] ((juxt :val :rule) (first (filter #(= li (:line-item %)) result))))]
    (testing "COGS and XOPR from the reported total"
      (is (= [76847.0 :cogs-total-costs] (v "COGS (Compustat)")))
      (is (= [76847.0 :xopr-total-costs] (v "XOPR (Compustat)"))))
    (testing "a cost-of-revenue line disables the total-costs rules"
      (let [result2 (reclass/apply-ruleset
                     (conj rows (row "Cost of Revenue" 50000.0 {:concept "CostOfRevenue"}))
                     aux reclass/compustat-income-ruleset)]
        (is (= :cogs-ex-da (:rule (first (filter #(= "COGS (Compustat)" (:line-item %))
                                                 result2)))))))))

(deftest compustat-glp-rdip-test
  ;; GLP: signed gains/losses on asset sales leave OIADP by subtraction;
  ;; RDIP: in-process R&D charges join the neg-sum special items
  (let [base [(row "Operating Income" 100.0 {:concept "OperatingIncomeLoss"})
              (row "In-Process R&D" 5.0 {:concept "ResearchAndDevelopmentInProcess"})]
        v (fn [rows li] (->> (reclass/apply-ruleset rows [] reclass/compustat-income-ruleset)
                             (filter #(= li (:line-item %))) first :val))]
    (testing "a gain (positive) is removed from operating income"
      (let [rows (conj base (row "Gain (Loss) on Sale of Assets" 10.0
                                 {:concept "GainLossOnDispositionOfAssets1"}))]
        (is (= 95.0 (v rows "OIADP (Compustat)")) "OI + 5 RDIP - 10 gain")))
    (testing "a loss (negative) is added back"
      (let [rows (conj base (row "Gain (Loss) on Sale of Assets" -10.0
                                 {:concept "GainLossOnDispositionOfAssets1"}))]
        (is (= 115.0 (v rows "OIADP (Compustat)")) "OI + 5 RDIP + 10 loss")))
    (testing "RDIP joins the special-items charges"
      (is (= -5.0 (v base "Special Items (Compustat)"))))))

(deftest compustat-fsds-guards-test
  ;; the D&A-on-IS marker (injected from FSDS pre.txt) switches the COGS rule
  (let [rows [(row "Cost of Revenue" 600.0 {:concept "CostOfRevenue"})]
        aux [(row "D&A" 40.0)]
        marker (row "D&A Presented on Income Statement" 1.0 {:method :fsds})
        cogs (fn [aux-rows]
               (->> (reclass/apply-ruleset rows aux-rows reclass/compustat-income-ruleset)
                    (filter #(= "COGS (Compustat)" (:line-item %))) first))]
    (testing "without the marker D&A is stripped from COGS"
      (let [c (cogs aux)]
        (is (= 560.0 (:val c)))
        (is (= :cogs-ex-da (:rule c)))))
    (testing "with the marker COGS is copied as reported"
      (let [c (cogs (conj aux marker))]
        (is (= 600.0 (:val c)))
        (is (= :cogs-da-presented (:rule c)))))))

(deftest compustat-xsga-opex-lines-and-xrd-test
  ;; AMZN-shaped filer: no SG&A / S&M tags; fulfillment + technology arrive
  ;; as FSDS extension operand rows
  (let [rows [(row "General and Administrative Expense" 10.0
                   {:concept "GeneralAndAdministrativeExpense"})
              (row "Marketing Expense" 20.0 {:concept "MarketingExpense"})]
        aux [(row "Fulfillment Expense" 90.0 {:method :fsds})
             (row "Technology and Content Expense" 80.0 {:method :fsds})]
        result (vec (reclass/apply-ruleset rows aux reclass/compustat-income-ruleset))
        get-row (fn [li] (first (filter #(= li (:line-item %)) result)))]
    (testing "XSGA sums the operating expense lines incl. FSDS extensions"
      (let [xsga (get-row "XSGA (Compustat)")]
        (is (= 200.0 (:val xsga)))
        (is (= :xsga-opex-lines (:rule xsga)))))
    (testing "XRD falls back to the technology extension line"
      (let [xrd (get-row "XRD (Compustat)")]
        (is (= 80.0 (:val xrd)))
        (is (= :xrd-tech-content (:rule xrd)))))
    (testing "XRD prefers a reported R&D tag when present"
      (let [xrd (->> (reclass/apply-ruleset
                      (conj rows (row "R&D Expense" 42.0
                                      {:concept "ResearchAndDevelopmentExpense"}))
                      aux reclass/compustat-income-ruleset)
                     (filter #(= "XRD (Compustat)" (:line-item %))) first)]
        (is (= 42.0 (:val xrd)))
        (is (= :xrd-reported (:rule xrd)))))))

(deftest compustat-xsga-excludes-other-operating-test
  ;; Validated against Compustat (AMZN FY2018-2024): XSGA leaves the
  ;; other-operating line out whichever concept tags it - adding the line
  ;; overshot and subtracting it undershot by the same amount.
  (let [base [(row "General and Administrative Expense" 100.0
                   {:concept "GeneralAndAdministrativeExpense"})
              (row "Marketing Expense" 200.0 {:concept "MarketingExpense"})]
        xsga (fn [rows]
               (->> (reclass/apply-ruleset rows [] reclass/compustat-income-ruleset)
                    (filter #(= "XSGA (Compustat)" (:line-item %))) first))]
    (testing "income-signed net line is excluded"
      (let [x (xsga (conj base (row "Other Operating Income (Expense), Net" -767.0
                                    {:concept "OtherOperatingIncomeExpenseNet"})))]
        (is (= 300.0 (:val x)))
        (is (= :xsga-opex-lines (:rule x)))
        (is (= ["General and Administrative Expense" "Marketing Expense"]
               (:derived-from x)))))
    (testing "expense-signed line is excluded"
      (is (= 300.0 (:val (xsga (conj base (row "Other Operating Expense" 296.0
                                               {:concept "OtherCostAndExpenseOperating"})))))))))

(deftest compustat-xsga-components-fallback-test
  (let [rows [(row "Selling and Marketing Expense" 60.0 {:concept "SellingAndMarketingExpense"})
              (row "General and Administrative Expense" 30.0 {:concept "GeneralAndAdministrativeExpense"})
              (row "R&D Expense" 10.0 {:concept "ResearchAndDevelopmentExpense"})]
        result (vec (reclass/apply-ruleset rows [] reclass/compustat-income-ruleset))
        xsga (first (filter #(= "XSGA (Compustat)" (:line-item %)) result))]
    (testing "component rule fires when no combined SG&A tag exists"
      (is (= 100.0 (:val xsga)))
      (is (= :xsga-components (:rule xsga))))))

(deftest compustat-excise-guard-test
  (let [base [(row "Excise Taxes" 50.0 {:concept "ExciseAndSalesTaxes"})]
        gross (conj base (row "Revenue" 1000.0 {:concept "Revenues"}))
        net (conj base (row "Revenue" 1000.0
                            {:concept "RevenueFromContractWithCustomerExcludingAssessedTax"}))
        v (fn [rows] (->> (reclass/apply-ruleset rows [] reclass/compustat-income-ruleset)
                          (filter #(= "Revenue (Compustat)" (:line-item %)))
                          first ((juxt :val :rule))))]
    (testing "excise netted when the revenue concept may be gross of excise"
      (is (= [950.0 :revt-ex-excise] (v gross))))
    (testing "not netted when revenue is already net of assessed taxes"
      (is (= [1000.0 :revt] (v net))))))

(deftest compustat-revt-gross-revenue-tags-test
  ;; modeled on CVX: the Revenues total includes equity-affiliate and other
  ;; income; REVT comes from the sales tags, net of excise
  (let [v (fn [rows aux]
            (->> (reclass/apply-ruleset rows aux reclass/compustat-income-ruleset)
                 (filter #(= "Revenue (Compustat)" (:line-item %)))
                 first ((juxt :val :rule))))
        revenues (row "Revenue" 114472.0 {:concept "Revenues"})
        excise (row "Excise Taxes" 6905.0 {:concept "ExciseAndSalesTaxes"})]
    (testing "IncludingAssessedTax revenue less excise beats the Revenues total"
      (is (= [103310.0 :revt-incl-tax-ex-excise]
             (v [revenues excise]
                [(row "RevenueFromContractWithCustomerIncludingAssessedTax" 110215.0)]))))
    (testing "oil and gas revenue less excise, for filers with an exploration line"
      (is (= [159293.0 :revt-oil-gas]
             (v [(assoc revenues :val 171636.0) (assoc excise :val 8109.0)]
                [(row "OilAndGasRevenue" 167402.0) (row "ExplorationExpense" 1342.0)]))))
    (testing "without the exploration line OilAndGasRevenue is not used"
      (is (= :revt-ex-excise
             (second (v [revenues excise] [(row "OilAndGasRevenue" 100000.0)])))))))

(deftest compustat-excise-gp-tieout-test
  ;; modeled on MO FY2016: revenue tagged ExcludingAssessedTax still includes
  ;; excise — the reported GrossProfit ties out to Revenue - COGS - Excise
  (let [mo [(row "Revenue" 25744.0
                 {:concept "RevenueFromContractWithCustomerExcludingAssessedTax"})
            (row "Cost of Revenue" 7746.0 {:concept "CostOfGoodsSold"})
            (row "Excise Taxes" 6407.0 {:concept "ExciseAndSalesTaxes"})
            (row "Gross Profit" 11572.0 {:concept "GrossProfit"})]
        aux [(row "D&A" 204.0)]
        out (fn [rows] (vec (reclass/apply-ruleset rows aux reclass/compustat-income-ruleset)))
        get-row (fn [result li] (first (filter #(= li (:line-item %)) result)))]
    (testing "the tie-out nets excise despite the ExcludingAssessedTax concept name"
      (let [r (get-row (out mo) "Revenue (Compustat)")]
        (is (= 19337.0 (:val r)))
        (is (= :revt-ex-excise-gp-tieout (:rule r)))))
    (testing "Gross Profit (Compustat) uses the excise-netted revenue"
      (let [gp (get-row (out mo) "Gross Profit (Compustat)")]
        (is (= (- 19337.0 (- 7746.0 204.0)) (:val gp)))
        (is (= :gross-profit-net-revenue (:rule gp)))
        (is (= ["Revenue (Compustat)" "COGS (Compustat)"] (:derived-from gp)))))
    (testing "no tie-out (gross profit already excludes only COGS): not netted"
      (let [genuine-net (assoc-in mo [3 :val] 17998.0)
            result (out genuine-net)]
        (is (= [25744.0 :revt] ((juxt :val :rule) (get-row result "Revenue (Compustat)"))))
        (is (= (- 25744.0 (- 7746.0 204.0))
               (:val (get-row result "Gross Profit (Compustat)"))))))
    (testing "a derived (imputed) Gross Profit never licenses the tie-out"
      (let [derived-gp (assoc-in mo [3 :concept] nil)]
        (is (= :revt (:rule (get-row (out derived-gp) "Revenue (Compustat)"))))))))

;;; ---------------------------------------------------------------------------
;;; financials wiring — :view :compustat
;;; ---------------------------------------------------------------------------

(deftest compustat-view-wiring-test
  (let [f #'edgar.financials/normalized-statement
        facts-ds (ds/->dataset
                  [{:concept "SalesRevenueNet" :form "10-K" :val 1000.0
                    :unit "USD" :start "2014-09-28" :end "2015-09-26"
                    :filed "2015-10-28" :frame nil}
                   {:concept "CostOfRevenue" :form "10-K" :val 600.0
                    :unit "USD" :start "2014-09-28" :end "2015-09-26"
                    :filed "2015-10-28" :frame nil}
                   {:concept "DepreciationDepletionAndAmortization" :form "10-K" :val 40.0
                    :unit "USD" :start "2014-09-28" :end "2015-09-26"
                    :filed "2015-10-28" :frame nil}])
        chains [["Revenue" "SalesRevenueNet"]
                ["Cost of Revenue" "CostOfRevenue"]]
        aux-fn (#'edgar.financials/reclass-aux-fn facts-ds nil
                                                  reclass/compustat-income-ruleset)
        result (f facts-ds chains "10-K" :duration nil
                  fin/income-statement-identities
                  {:ruleset reclass/compustat-income-ruleset :aux-fn aux-fn})
        rows (ds/rows result {:nil-missing? true})
        v (fn [li] (:val (first (filter #(= li (:line-item %)) rows))))]
    (testing "cross-statement aux supplies cash-flow D&A to the income reclass"
      (is (= 560.0 (v "COGS (Compustat)")))
      (is (= 40.0 (v "DP (Compustat)"))))
    (testing "the cash-flow D&A row itself is not in the income output"
      (is (nil? (v "D&A"))))
    (testing "identities still run (:view :compustat is a superset of :standardized)"
      (is (= 400.0 (v "Gross Profit")) "imputed Revenue - Cost of Revenue"))
    (testing "reclassified and derived rows coexist with direct rows"
      (is (= #{:direct :derived :reclassified}
             (set (map :method rows)))))))

(deftest new-reclass-line-items-in-chains-test
  (let [labels (set (map :label (:line-items fin/income-statement-concept-map)))]
    (testing "component and special-item chains exist for the reclass rules"
      ;; "Fulfillment Expense" is deliberately NOT a chain entry: companyfacts
      ;; never returns extension tags, so the label only arrives via FSDS aux
      (doseq [li ["Selling and Marketing Expense" "General and Administrative Expense"
                  "Marketing Expense" "Other Operating Expense"
                  "Excise Taxes" "Restructuring Charges" "Impairment Charges"
                  "Goodwill Impairment" "Acquisition-Related Costs" "Litigation Settlement"]]
        (is (contains? labels li) li))
      (is (not (contains? labels "Fulfillment Expense"))
          "extension-tag labels are FSDS-only, not chain entries"))))

(deftest other-operating-chains-sign-separated-test
  ;; a chain must not mix sign conventions: the expense-signed and the
  ;; income-signed other-operating concepts are separate line items
  (let [chain (fn [label] (set (rest (first (filter #(= label (first %))
                                                    fin/income-statement-concepts)))))]
    (is (= #{"OtherCostAndExpenseOperating"} (chain "Other Operating Expense")))
    (is (= #{"OtherOperatingIncomeExpenseNet"}
           (chain "Other Operating Income (Expense), Net")))))

;;; ---------------------------------------------------------------------------
;;; Review fixes (2026-10)
;;; ---------------------------------------------------------------------------

(deftest all-optional-formula-test
  ;; latent: an all-[:opt] formula with none of its operands present
  ;; evaluated to 0.0 and emitted a row without :unit/:start/:end
  (let [f #'edgar.reclass/eval-formula
        g (by-li [{:line-item "A" :val 100}])]
    (testing "nothing to evaluate when no operand is present"
      (is (nil? (f [:+ [:opt "Missing"] [:opt "Absent"]] g)))
      (is (nil? (f [:- [:opt "Missing"]] g))))
    (testing "one present optional operand is enough"
      (is (= {:val 100 :used ["A"]} (f [:+ [:opt "A"] [:opt "Missing"]] g))))
    (testing "an absent optional operand keeps integer arithmetic integral"
      (is (= {:val 100 :used ["A"]} (f [:- "A" [:opt "Missing"]] g)))))
  (testing "apply-ruleset emits no row for such a rule"
    (let [rows [{:line-item "A" :val 1.0 :unit "USD" :start "2023-01-01" :end "2023-12-31"}]
          out (reclass/apply-ruleset rows nil {:rules [{:id :t :target "T"
                                                        :formula [:+ [:opt "X"] [:opt "Y"]]}]})]
      (is (= ["A"] (mapv :line-item out))))))

(deftest reclassified-rows-drop-operand-metadata-test
  (let [base {:unit "USD" :start "2023-01-01" :end "2023-12-31" :form "10-K"
              :taxonomy "us-gaap" :accn "acc" :fy 2023 :fp "FY" :frame "CY2023"
              :label "Revenue from Contract with Customer" :description "d"}
        rows [(assoc base :line-item "Revenue" :val 100.0 :concept "Revenues" :filed "2024-02-01")
              (assoc base :line-item "Cost" :val 60.0 :concept "CostOfRevenue" :filed "2024-02-09")]
        out (reclass/apply-ruleset rows nil {:rules [{:id :gp :target "GP (Test)"
                                                      :formula [:- "Revenue" "Cost"]}]})
        gp (first (filter #(= "GP (Test)" (:line-item %)) out))]
    (is (= 40.0 (:val gp)))
    (is (= [:reclassified :gp ["Revenue" "Cost"]] ((juxt :method :rule :derived-from) gp)))
    (is (every? #(not (contains? gp %)) [:label :description :accn :fy :fp :frame :taxonomy]))
    (is (nil? (:concept gp)))
    (is (= ["USD" "2023-01-01" "2023-12-31" "10-K" "2024-02-09"]
           ((juxt :unit :start :end :form :filed) gp)))))
