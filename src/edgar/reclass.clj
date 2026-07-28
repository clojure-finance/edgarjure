(ns edgar.reclass
  "Reclassification rule engine (roadmap 4.1c).

   Approximates commercial-database item definitions (currently Compustat)
   by reclassifying standardized line items: stripping D&A out of COGS,
   folding R&D into SG&A, adding special-item charges back to operating
   income, netting excise taxes from revenue, etc.

   Rules are data, loaded from EDN under resources/edgar/reclass/ and applied
   per [unit start end] period group on top of the :standardized row set.
   Reclassified rows are ADDED alongside the original items (never replacing
   them) and carry provenance:
     :method       :reclassified
     :rule         the rule :id that produced the row
     :derived-from labels of the operand line items actually used

   Rule format:
     {:id      :cogs-ex-da           ; unique keyword
      :target  \"COGS (Compustat)\"  ; emitted line-item label
      :formula [:- \"Cost of Revenue\" \"D&A\"]
      :guards  [[:lt \"D&A\" \"Cost of Revenue\"]]
      :compustat \"COGS\"            ; documentation only
      :notes   \"...\"}              ; documentation only

   Formula operators:
     [:= a]          - copy operand a
     [:+ a b ...]    - sum
     [:- a b ...]    - first minus the rest
     [:neg-sum a ..] - negated sum of the operands present with a non-zero
                       value (nil when none are)
   Operands are line-item labels; wrap as [:opt label] to treat a missing
   operand as 0 instead of blocking the rule. Targets of earlier rules can be
   operands of later ones (applied iteratively, capped at 4 passes).

   Guards (all must pass; operands referenced by label):
     [:lt a b]                    - value of a strictly less than value of b
     [:gt a b]                    - value of a strictly greater than value of b
     [:concept-not-in a #{c ...}] - a's winning XBRL concept is not in the set
                                    (rows without a concept, e.g. derived rows,
                                    pass)

   Rules are tried in order; for a given :target the first rule whose operands
   and guards are satisfied wins (the target is skipped once present)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

;;; ---------------------------------------------------------------------------
;;; Ruleset loading
;;; ---------------------------------------------------------------------------

(defn- load-ruleset-file [resource-path]
  (with-open [r (java.io.PushbackReader. (io/reader (io/resource resource-path)))]
    (edn/read r)))

(def compustat-income-ruleset
  "Compustat reclassification ruleset for the income statement.
   Source: resources/edgar/reclass/compustat-income.edn"
  (load-ruleset-file "edgar/reclass/compustat-income.edn"))

(defn ruleset-for
  "Return the active reclassification ruleset for a statement, for inspection.
   statement: :income | :balance | :cash-flow
   Returns the full EDN ruleset map, or nil when the statement has no rules."
  [statement]
  (case statement
    :income compustat-income-ruleset
    nil))

;;; ---------------------------------------------------------------------------
;;; Formula evaluation
;;; ---------------------------------------------------------------------------

(defn- operand-label [o]
  (if (vector? o) (second o) o))

(defn- optional-operand? [o]
  (and (vector? o) (= :opt (first o))))

(defn- resolve-operands
  "Resolve formula operands against the period group's {line-item row} map.
   Returns {:vals [..] :used [labels]} or nil when a required operand is
   missing. Optional operands contribute 0.0 when absent."
  [operands by-li]
  (reduce (fn [acc o]
            (let [label (operand-label o)
                  row (get by-li label)
                  v (:val row)]
              (cond
                (some? v) (-> acc (update :vals conj v) (update :used conj label))
                (optional-operand? o) (update acc :vals conj 0.0)
                :else (reduced nil))))
          {:vals [] :used []}
          operands))

(defn- eval-formula
  "Evaluate a rule formula against the period group. Returns
   {:val v :used [labels]} or nil when the formula cannot be evaluated."
  [[op & operands] by-li]
  (if (= :neg-sum op)
    (let [present (filter #(let [v (:val (get by-li (operand-label %)))]
                             (and (some? v) (not (zero? (double v)))))
                          operands)]
      (when (seq present)
        (when-let [{:keys [vals used]} (resolve-operands present by-li)]
          {:val (- (reduce + vals)) :used used})))
    (when-let [{:keys [vals used]} (resolve-operands operands by-li)]
      (when (seq vals)
        {:val (case op
                := (first vals)
                :+ (reduce + vals)
                :- (reduce - vals))
         :used used}))))

;;; ---------------------------------------------------------------------------
;;; Guards
;;; ---------------------------------------------------------------------------

(defn- guard-passes? [guard by-li]
  (let [[op a b] guard]
    (case op
      :lt (let [va (:val (get by-li a)) vb (:val (get by-li b))]
            (and (some? va) (some? vb) (< (double va) (double vb))))
      :gt (let [va (:val (get by-li a)) vb (:val (get by-li b))]
            (and (some? va) (some? vb) (> (double va) (double vb))))
      :concept-not-in (let [c (:concept (get by-li a))]
                        (or (nil? c) (not (contains? b c))))
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
            [acc' by-li' emitted?]
            (reduce (fn [[rows by-li emitted?] {:keys [id target formula guards]}]
                      (if (contains? by-li target)
                        [rows by-li emitted?]
                        (if-let [{:keys [val used]}
                                 (when (guards-pass? (or guards []) by-li)
                                   (eval-formula formula by-li))]
                          (let [row (-> (get by-li (first used))
                                        (dissoc ::aux)
                                        (assoc :line-item target
                                               :val val
                                               :concept nil
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
      (->> (concat rows marked-aux)
           (group-by (juxt :unit :start :end))
           (mapcat (fn [[_ grows]] (apply-rules-to-group grows (:rules ruleset))))))))
