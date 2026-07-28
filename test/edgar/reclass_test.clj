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
      (is (nil? (f [:neg-sum "Missing" "Z"] g))))))

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
    (testing ":gt passes when strictly greater"
      (is (f [:gt "COGS" "D&A"] g)))
    (testing ":concept-not-in fails when the concept is in the set"
      (is (not (f [:concept-not-in "COGS" #{"CostOfRevenue"}] g))))
    (testing ":concept-not-in passes when the concept is not in the set"
      (is (f [:concept-not-in "COGS" #{"SomethingElse"}] g)))
    (testing ":concept-not-in passes for rows without a concept (derived rows)"
      (is (f [:concept-not-in "Derived" #{"CostOfRevenue"}] g)))
    (testing "unknown guard op fails closed"
      (is (not (f [:unknown "A" "B"] g))))))

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
    (testing "cash-flow aux declares the D&A dependency"
      (is (= ["D&A"] (get-in rs [:aux :cash-flow]))))
    (testing "all documented targets are present"
      (is (= #{"DP (Compustat)" "COGS (Compustat)" "XSGA (Compustat)"
               "Gross Profit (Compustat)" "XOPR (Compustat)"
               "Special Items (Compustat)" "OIADP (Compustat)"
               "OIBDP (Compustat)" "Revenue (Compustat)"}
             (set (map :target (:rules rs))))))
    (testing "rule ids are unique"
      (let [ids (map :id (:rules rs))]
        (is (= (count ids) (count (set ids))))))))

(deftest ruleset-for-test
  (testing "income has a ruleset, balance and cash flow do not"
    (is (some? (reclass/ruleset-for :income)))
    (is (nil? (reclass/ruleset-for :balance)))
    (is (nil? (reclass/ruleset-for :cash-flow)))))

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
    (testing "no excise taxes tagged -> no Revenue (Compustat) row"
      (is (nil? (v "Revenue (Compustat)"))))))

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
                          first :val))]
    (testing "excise netted when the revenue concept may be gross of excise"
      (is (= 950.0 (v gross))))
    (testing "not netted when revenue is already net of assessed taxes"
      (is (nil? (v net))))))

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
      (doseq [li ["Selling and Marketing Expense" "General and Administrative Expense"
                  "Marketing Expense" "Fulfillment Expense" "Other Operating Expense"
                  "Excise Taxes" "Restructuring Charges" "Impairment Charges"
                  "Goodwill Impairment" "Acquisition-Related Costs" "Litigation Settlement"]]
        (is (contains? labels li) li)))))
