(ns edgar.extract
  (:require [edgar.filing :as filing]
            [clojure.string :as str]
            [hickory.core :as hickory]
            [hickory.select :as sel]
            [babashka.fs :as fs])
  (:import [java.io File]))

;;; ---------------------------------------------------------------------------
;;; Item definitions per form type
;;; Maps item-id -> human-readable label
;;; ---------------------------------------------------------------------------

(def items-10k
  {"1" "Business"
   "1A" "Risk Factors"
   "1B" "Unresolved Staff Comments"
   "2" "Properties"
   "3" "Legal Proceedings"
   "4" "Mine Safety Disclosures"
   "5" "Market for Registrant Common Equity"
   "6" "Selected Financial Data"
   "7" "Management's Discussion and Analysis"
   "7A" "Quantitative and Qualitative Disclosures About Market Risk"
   "8" "Financial Statements and Supplementary Data"
   "9" "Changes in and Disagreements with Accountants"
   "9A" "Controls and Procedures"
   "9B" "Other Information"
   "10" "Directors, Executive Officers and Corporate Governance"
   "11" "Executive Compensation"
   "12" "Security Ownership of Certain Beneficial Owners"
   "13" "Certain Relationships and Related Transactions"
   "14" "Principal Accountant Fees and Services"
   "15" "Exhibits and Financial Statement Schedules"})

(def items-10q
  {"I-1" "Financial Statements"
   "I-2" "Management's Discussion and Analysis"
   "I-3" "Quantitative and Qualitative Disclosures About Market Risk"
   "I-4" "Controls and Procedures"
   "II-1" "Legal Proceedings"
   "II-1A" "Risk Factors"
   "II-2" "Unregistered Sales of Equity Securities"
   "II-3" "Defaults Upon Senior Securities"
   "II-4" "Mine Safety Disclosures"
   "II-5" "Other Information"
   "II-6" "Exhibits"})

(def items-8k
  {"1.01" "Entry into a Material Definitive Agreement"
   "1.02" "Termination of a Material Definitive Agreement"
   "1.03" "Bankruptcy or Receivership"
   "2.01" "Completion of Acquisition or Disposition of Assets"
   "2.02" "Results of Operations and Financial Condition"
   "2.03" "Creation of a Direct Financial Obligation"
   "3.01" "Notice of Delisting or Failure to Satisfy Listing Rule"
   "4.01" "Changes in Registrant's Certifying Accountant"
   "5.02" "Departure/Election of Directors or Officers"
   "7.01" "Regulation FD Disclosure"
   "8.01" "Other Events"
   "9.01" "Financial Statements and Exhibits"})

(defn items-for-form [form]
  (case (str/replace (or form "") #"/A$" "")
    "10-K" items-10k
    "10-Q" items-10q
    "8-K" items-8k
    {}))

;;; ---------------------------------------------------------------------------
;;; Item pattern
;;; ---------------------------------------------------------------------------

(def item-pattern
  #"(?i)^\s*item\s+((?:[IVXivx]+\s*[-\s]\s*)?\d{1,2}[AB]?(?:\.\d{2})?)\s*[.:\-\u2014]?\s*(.{0,80})")

;;; ---------------------------------------------------------------------------
;;; Hickory tree utilities
;;; ---------------------------------------------------------------------------

(defn- node-text
  "Recursively extract all text strings from a hickory node.
   Normalizes non-breaking spaces (\\u00A0) to regular spaces."
  [node]
  (cond
    (string? node) (str/replace node "\u00A0" " ")
    (map? node) (str/join "" (map node-text (:content node)))
    :else ""))

(defn- flatten-nodes
  "Return a flat indexed vector of all hickory nodes in document order."
  [tree]
  (let [result (transient [])]
    (letfn [(walk [node]
              (conj! result node)
              (when (map? node)
                (doseq [child (:content node)]
                  (walk child))))]
      (walk tree))
    (persistent! result)))

;;; ---------------------------------------------------------------------------
;;; Table removal (for NLP mode)
;;; ---------------------------------------------------------------------------

(defn- remove-tables
  "Remove all <table> elements from a hickory tree."
  [tree]
  (clojure.walk/postwalk
   (fn [node]
     (if (and (map? node) (= :table (:tag node)))
       nil
       (if (map? node)
         (update node :content (fn [c] (when c (remove nil? c))))
         node)))
   tree))

;;; ---------------------------------------------------------------------------
;;; HTML heading boundary detection
;;;
;;; Strategy:
;;; 1. Flatten the full hickory tree into a document-order node sequence
;;; 2. Walk each node; when its text matches item-pattern, record the
;;;    node index as a boundary
;;; 3. Deduplicate: keep only the *last* match per item-id (avoids
;;;    table-of-contents entries which come before the real heading)
;;; 4. Slice the flat-nodes vector between consecutive boundary indices
;;;    and extract text from each slice
;;; ---------------------------------------------------------------------------

(def ^:private heading-tags #{:h1 :h2 :h3 :h4 :p :div :span :td})

(defn- find-item-boundaries
  "Returns a seq of {:item-id :title :node-index} sorted by :node-index.
   Deduplicates by taking the *last* match per item-id to skip TOC entries."
  [flat-nodes]
  (let [candidates
        (keep-indexed
         (fn [idx node]
           (when (and (map? node) (heading-tags (:tag node)))
             (let [text (str/trim (node-text node))]
               (when-let [m (re-find item-pattern text)]
                 {:item-id (-> (nth m 1)
                               str/trim
                               (str/replace #"\s*[-\s]\s*(?=\d)" "-")
                               str/upper-case)
                  :title (str/trim (nth m 2))
                  :node-index idx}))))
         flat-nodes)]
    ;; keep last occurrence of each item-id (body heading, not TOC)
    (->> candidates
         (reduce (fn [acc x] (assoc acc (:item-id x) x)) {})
         vals
         (sort-by :node-index))))

(defn- text-from-node-slice
  "Extract plain text from a sub-sequence of flat nodes, deduplicating
   string content that appears at multiple levels in the tree."
  [nodes]
  (let [strings (->> nodes
                     (filter string?)
                     (map str/trim)
                     (remove str/blank?))]
    (str/trim (str/join " " strings))))

(defn- extract-items-html
  "Main HTML extraction path. Returns a map of item-id ->
   {:title \"...\" :text \"...\" :method :html-heading-boundaries}.
   Uses ALL boundaries for end-position calculation so that requesting only
   a subset of items does not cause a requested item's text to bleed into
   the next requested item (skipping unrequested boundaries in between)."
  [tree target-ids]
  (let [flat (flatten-nodes tree)
        all-boundaries (vec (find-item-boundaries flat))
        all-count (count all-boundaries)]
    (into {}
          (keep-indexed
           (fn [i {:keys [item-id title node-index]}]
             (when (target-ids item-id)
               (let [next-idx (when (< (inc i) all-count)
                                (:node-index (nth all-boundaries (inc i))))
                     end-idx (or next-idx (count flat))
                     body-nodes (subvec (vec flat) (inc node-index) end-idx)
                     text (text-from-node-slice body-nodes)]
                 [item-id {:title title
                           :text text
                           :method :html-heading-boundaries}])))
           all-boundaries))))

;;; ---------------------------------------------------------------------------
;;; Plain-text item boundary detection (pre-2000 filings)
;;; ---------------------------------------------------------------------------

(defn- html-content?
  "Returns true if the string looks like an HTML document."
  [s]
  (boolean (re-find #"(?i)<(!DOCTYPE|html)" s)))

(defn- extract-items-text
  "Extract item sections from plain-text filing content using regex boundaries.
   Returns a map of item-id ->
   {:title \"\" :text \"...\" :method :plain-text-regex}."
  [text items-map]
  (let [ids-pattern (str/join "|"
                              (map #(str/replace % "." "\\.") (keys items-map)))
        pattern (re-pattern
                 (str "(?im)^\\s*ITEM\\s+(" ids-pattern
                      ")\\s*[.:\\-\u2014]?\\s*([\\w\\s]{0,80})$"))
        matcher (re-matcher pattern text)
        matches (loop [acc []]
                  (if (.find matcher)
                    (recur (conj acc {:item-id (str/upper-case (.group matcher 1))
                                      :title (str/trim (.group matcher 2))
                                      :start (.start matcher)}))
                    acc))]
    (into {}
          (for [[{:keys [item-id title start]} next-m]
                (partition-all 2 1 matches)]
            (let [end (if next-m (:start next-m) (count text))
                  body (str/trim (subs text start end))]
              [item-id {:title title
                        :text body
                        :method :plain-text-regex}])))))

;;; ---------------------------------------------------------------------------
;;; Main extraction API
;;; ---------------------------------------------------------------------------

(defn extract-items
  "Extract item sections from a filing.
   Returns a map of item-id -> {:title \"...\" :text \"...\" :method ...}.

   filing  : filing metadata map (from edgar.filings/get-filings)
   Options:
     :items          - set of item ids to extract e.g. #{\"7\" \"1A\"} (default: all)
     :remove-tables? - strip <table> elements before text extraction (default: false)

   Example:
     (extract-items f :items #{\"7\" \"1A\"} :remove-tables? true)
     ;=> {\"7\"  {:title \"Management's Discussion...\" :text \"...20k chars...\"
     ;            :method :html-heading-boundaries}
     ;    \"1A\" {:title \"Risk Factors\" :text \"...\" :method :html-heading-boundaries}}"
  [filing & {:keys [items remove-tables?] :or {remove-tables? false}}]
  (let [form (:form filing)
        items-map (items-for-form form)
        target-ids (if items
                     (set (map #(-> % str/trim
                                    (str/replace #"\s*[-\s]\s*(?=\d)" "-")
                                    str/upper-case)
                               items))
                     (set (keys items-map)))
        html (filing/filing-html filing)]
    (cond
      ;; modern HTML path
      (and html (not (str/blank? html)) (html-content? html))
      (let [tree (-> html hickory/parse hickory/as-hickory)
            clean-tree (if remove-tables? (remove-tables tree) tree)]
        (extract-items-html clean-tree target-ids))

      ;; plain-text path — either filing-html returned raw text, or no html at all
      :else
      (let [text (if (and html (not (str/blank? html)))
                   html
                   (filing/filing-text filing))]
        (if (str/blank? text)
          {}
          (extract-items-text text items-map))))))

(defn extract-item
  "Extract a single item section from a filing.
   Returns {:title \"...\" :text \"...\" :method ...} or nil."
  [filing item-id]
  (get (extract-items filing :items #{item-id}) (str/upper-case item-id)))

;;; ---------------------------------------------------------------------------
;;; Batch extraction (edgar-crawler analog)
;;; ---------------------------------------------------------------------------

(defn batch-extract!
  "Extract item sections from a seq of filings and write {accession-no}.edn to output-dir.
   Options:
     :items          - set of item ids to extract (default: all)
     :remove-tables? - strip numeric tables (default: false)
     :skip-existing? - skip if output file already exists (default: true)"
  [filing-seq output-dir & {:keys [items remove-tables? skip-existing?]
                            :or {remove-tables? false skip-existing? true}}]
  (fs/create-dirs output-dir)
  (doall
   (for [f filing-seq
         :let [acc (:accessionNumber f)
               out-file (fs/path output-dir (str acc ".edn"))]
         :when (or (not skip-existing?) (not (fs/exists? out-file)))]
     (try
       (let [extracted (extract-items f :items items :remove-tables? remove-tables?)]
         (spit (str out-file) (pr-str extracted))
         {:accession-no acc :status :ok :items (keys extracted)})
       (catch Exception e
         {:accession-no acc :status :error :message (.getMessage e)})))))
