(ns edgar.reclass
  "Reclassification rule engine.

   Approximates commercial-database item constructions (currently
   Compustat) by reclassifying standardized line items: stripping D&A out of
   COGS, folding R&D into SG&A, adding special-item charges back to
   operating income, netting excise taxes from revenue, etc. The rules are
   an independent empirical approximation computed from public SEC filings
   only.

   Rules are data, loaded from EDN under resources/edgar/reclass/ and applied
   per [unit start end] period group on top of the :standardized row set.
   Reclassified rows are ADDED alongside the original items (never replacing
   them) and carry provenance:
     :method       :reclassified
     :rule         the rule :id that produced the row
     :derived-from labels of the operand line items actually used
   They keep the period fields (:unit :start :end :form) of their operands;
   per-fact metadata that no longer describes the row (:concept :label
   :description :taxonomy :accn :fy :fp :frame) is dropped, and :filed is the
   latest :filed among the operands used.

   Rule format:
     {:id      :cogs-ex-da           ; unique keyword
      :target  \"COGS (Compustat)\"  ; emitted line-item label
      :formula [:- \"Cost of Revenue\" \"DP (Compustat)\"]
      :guards  [[:lt \"DP (Compustat)\" \"Cost of Revenue\"]]
      :compustat \"COGS\"            ; documentation only
      :notes   \"...\"}              ; documentation only

   Formula operators:
     [:= a]          - copy operand a
     [:+ a b ...]    - sum
     [:- a b ...]    - first minus the rest
     [:neg-sum a ..] - negated sum of the operands present with a non-zero
                       value (nil when none are)
     [:max a b ...]  - largest value among the operands present (nil when
                       none are)
   Operands are line-item labels; wrap as [:opt label] to treat a missing
   operand as 0 instead of blocking the rule, and as [:neg label] to use the
   negated value (for items tagged with the opposite sign convention, e.g.
   an income-signed \"other operating income (expense)\" line inside an
   expense sum). The wrappers nest: [:opt [:neg label]]. Targets of earlier
   rules can be operands of later ones (applied iteratively, capped at 4
   passes).

   Guards (all must pass; operands referenced by label):
     [:lt a b]                    - value of a strictly less than value of b
     [:lte a b]                   - value of a less than or equal to value of b
     [:ties formula b tol]        - formula (same syntax as :formula) evaluates
                                    and lies within relative tolerance tol of
                                    b's value (an arithmetic tie-out against a
                                    reported subtotal)
     [:at-least formula b ratio]  - formula evaluates and, when b exists, is
                                    at least ratio times b's value (passes
                                    when b is absent): a component sum that
                                    is not a footnote fragment of a reported
                                    total
     [:concept-not-in a #{c ...}] - a's winning XBRL concept is not in the set
                                    (rows without a concept, e.g. derived rows,
                                    pass)
     [:concept-in a #{c ...}]     - a exists and its winning XBRL concept is
                                    in the set (fails for missing rows and for
                                    derived rows, whose concept is nil — use
                                    this when the guard must positively
                                    identify a reported tag)
     [:present a]                 - a line item named a exists in the period
                                    (marker rows, e.g. FSDS placement signals)
     [:absent a]                  - no line item named a exists in the period

   Rules are tried in order; for a given :target the first rule whose operands
   and guards are satisfied wins (the target is skipped once present)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

;;; ---------------------------------------------------------------------------
;;; Ruleset loading
;;; ---------------------------------------------------------------------------

(defn- load-ruleset-file [resource-path expected-statement]
  (let [rs (with-open [r (java.io.PushbackReader. (io/reader (io/resource resource-path)))]
             (edn/read r))]
    (assert (= expected-statement (:statement rs))
            (str resource-path " declares :statement " (:statement rs)
                 " but is loaded as the " expected-statement " ruleset"))
    rs))

(def compustat-income-ruleset
  "Compustat reclassification ruleset for the income statement.
   Source: resources/edgar/reclass/compustat-income.edn"
  (load-ruleset-file "edgar/reclass/compustat-income.edn" :income))

(def compustat-balance-ruleset
  "Compustat reclassification ruleset for the balance sheet.
   Source: resources/edgar/reclass/compustat-balance.edn"
  (load-ruleset-file "edgar/reclass/compustat-balance.edn" :balance))

(defn ruleset-for
  "Return the active reclassification ruleset for a statement, for inspection.
   statement: :income | :balance | :cash-flow
   Returns the full EDN ruleset map, or nil when the statement has no rules."
  [statement]
  (case statement
    :income compustat-income-ruleset
    :balance compustat-balance-ruleset
    nil))

;;; ---------------------------------------------------------------------------
;;; Formula evaluation
;;; ---------------------------------------------------------------------------

(defn- operand-label
  "The line-item label inside an operand, unwrapping [:opt ..] / [:neg ..]
   (which may nest)."
  [o]
  (if (vector? o) (operand-label (second o)) o))

(defn- negated-operand?
  "True when an operand is wrapped in [:neg ..] at any nesting level."
  [o]
  (and (vector? o)
       (or (= :neg (first o)) (negated-operand? (second o)))))

(defn- optional-operand?
  "True when an operand is wrapped in [:opt ..] at any nesting level."
  [o]
  (and (vector? o)
       (or (= :opt (first o)) (optional-operand? (second o)))))

(defn- resolve-operands
  "Resolve formula operands against the period group's {line-item row} map.
   Returns {:vals [..] :used [labels]} or nil when a required operand is
   missing. Optional operands contribute (long) 0 when absent, so they never
   change the numeric type of the present operands' result; negated operands
   contribute their value's negation."
  [operands by-li]
  (reduce (fn [acc o]
            (let [label (operand-label o)
                  row (get by-li label)
                  v (:val row)]
              (cond
                (some? v) (-> acc
                              (update :vals conj (if (negated-operand? o) (- v) v))
                              (update :used conj label))
                (optional-operand? o) (update acc :vals conj 0)
                :else (reduced nil))))
          {:vals [] :used []}
          operands))

(defn eval-formula
  "Evaluate a formula against a {line-item row} map. Returns
   {:val v :used [labels]} or nil when the formula cannot be evaluated.
   The single formula evaluator — the standardized-view identity engine
   (edgar.financials/apply-identities) evaluates through this too, so ops
   and [:opt] semantics cannot drift between the two layers."
  [[op & operands] by-li]
  (case op
    :neg-sum
    (let [present (filter #(let [v (:val (get by-li (operand-label %)))]
                             (and (some? v) (not (zero? (double v)))))
                          operands)]
      (when (seq present)
        (when-let [{:keys [vals used]} (resolve-operands present by-li)]
          {:val (- (reduce + vals)) :used used})))

    :max
    (let [present (filter #(some? (:val (get by-li (operand-label %)))) operands)]
      (when (seq present)
        (when-let [{:keys [vals used]} (resolve-operands present by-li)]
          (let [[v label] (apply max-key #(double (first %)) (map vector vals used))]
            {:val v :used [label]}))))

    (when-let [{:keys [vals used]} (resolve-operands operands by-li)]
      ;; at least one operand must be present: an all-[:opt] formula with
      ;; none of them in the period has nothing to evaluate (and no operand
      ;; row to take the period fields from)
      (when (seq used)
        {:val (case op
                := (first vals)
                :+ (reduce + vals)
                :- (reduce - vals))
         :used used}))))

(defn derived-row
  "The row for a formula result: the period fields (:unit :start :end :form
   ...) of the first operand used, without the per-fact metadata that no
   longer describes it (:concept :label :description :taxonomy :accn :fy
   :fp :frame :fsds-tag), and with :filed set to the latest :filed among the
   operands used. Callers assoc :line-item, :val and provenance. Shared by
   the reclassification rules and edgar.financials' identities."
  [by-li used]
  (let [rows (keep #(get by-li %) used)
        filed (some->> (keep :filed rows) seq (reduce #(if (pos? (compare (str %1) (str %2))) %1 %2)))]
    (-> (first rows)
        (dissoc ::aux :label :description :taxonomy :accn :fy :fp :frame :fsds-tag)
        (assoc :concept nil)
        (cond-> filed (assoc :filed filed)))))

;;; ---------------------------------------------------------------------------
;;; Guards
;;; ---------------------------------------------------------------------------

(defn- guard-passes? [guard by-li]
  (let [[op a b] guard]
    (case op
      :lt (let [va (:val (get by-li a)) vb (:val (get by-li b))]
            (and (some? va) (some? vb) (< (double va) (double vb))))
      :lte (let [va (:val (get by-li a)) vb (:val (get by-li b))]
             (and (some? va) (some? vb) (<= (double va) (double vb))))
      :ties (let [tol (double (nth guard 3))
                  v (:val (eval-formula a by-li))
                  vb (:val (get by-li b))]
              (and (some? v) (some? vb)
                   (<= (Math/abs (- (double v) (double vb)))
                       (* tol (Math/abs (double vb))))))
      :at-least (let [ratio (double (nth guard 3))
                      v (:val (eval-formula a by-li))
                      vb (:val (get by-li b))]
                  (and (some? v)
                       (or (nil? vb) (>= (double v) (* ratio (double vb))))))
      :concept-not-in (let [c (:concept (get by-li a))]
                        (or (nil? c) (not (contains? b c))))
      :concept-in (let [row (get by-li a)]
                    (and (some? row) (contains? b (:concept row))))
      :present (contains? by-li a)
      :absent (not (contains? by-li a))
      false)))

(defn- guards-pass? [guards by-li]
  (every? #(guard-passes? % by-li) guards))

;;; ---------------------------------------------------------------------------
;;; Rule application
;;; ---------------------------------------------------------------------------

(defn- apply-rules-to-group
  "Apply the ruleset's rules to one period group (vector of rows sharing
   [unit start end]). Aux rows (marked ::aux) participate as operands but are
   removed from the output. Iterates so chained rules resolve (capped at 4
   passes)."
  [grows rules]
  (loop [pass 0
         acc (vec grows)]
    (if (>= pass 4)
      (remove ::aux acc)
      (let [;; rules apply SEQUENTIALLY within a pass: each emitted row is
            ;; visible to later rules immediately, so e.g. :special-items
            ;; lands before :oiadp reads its [:opt ...] operand
            [acc' _ emitted?]
            (reduce (fn [[rows by-li emitted?] {:keys [id target formula guards]}]
                      (if (contains? by-li target)
                        [rows by-li emitted?]
                        (if-let [{:keys [val used]}
                                 (when (guards-pass? (or guards []) by-li)
                                   (eval-formula formula by-li))]
                          (let [row (-> (derived-row by-li used)
                                        (assoc :line-item target
                                               :val val
                                               :method :reclassified
                                               :rule id
                                               :derived-from (vec used)))]
                            [(conj rows row) (assoc by-li target row) true])
                          [rows by-li emitted?])))
                    [acc (into {} (map (juxt :line-item identity)) acc) false]
                    rules)]
        (if emitted?
          (recur (inc pass) acc')
          (remove ::aux acc'))))))

(defn apply-ruleset
  "Apply a reclassification ruleset to statement rows.

   rows     - standardized statement rows (seq of maps with :line-item :val
              :unit :start :end ...)
   aux-rows - rows from other statements made available as operands per period
              (e.g. the cash-flow statement's \"D&A\" for income reclass);
              they are matched into period groups by [unit start end] and are
              not included in the output
   ruleset  - EDN ruleset map with a :rules vector (see namespace docstring)

   Returns rows plus the reclassified rows. When ruleset is nil or has no
   rules, returns rows unchanged."
  [rows aux-rows ruleset]
  (if (empty? (:rules ruleset))
    rows
    (let [marked-aux (map #(assoc % ::aux true) aux-rows)]
      ;; aux rows FIRST: the {line-item row} operand lookup is built with
      ;; later-wins semantics, so a statement row shadows a same-labeled aux
      ;; row (aux only fills gaps — e.g. custom :concepts chains that put a
      ;; "D&A" line on the income statement must win over the cash-flow aux
      ;; "D&A"; the built-in REIT chains do too, but :view :compustat does
      ;; not apply the income rules to REITs)
      (->> (concat marked-aux rows)
           (group-by (juxt :unit :start :end))
           (mapcat (fn [[_ grows]] (apply-rules-to-group grows (:rules ruleset))))))))
