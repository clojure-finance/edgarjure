(ns edgar.extract
  (:require [edgar.filing :as filing]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [hickory.core :as hickory]
            [babashka.fs :as fs]))

;;; ---------------------------------------------------------------------------
;;; Item definitions per form type
;;; Maps item-id -> human-readable label
;;; ---------------------------------------------------------------------------

(def items-10k
  {"1" "Business"
   "1A" "Risk Factors"
   "1B" "Unresolved Staff Comments"
   "1C" "Cybersecurity"
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
   "9C" "Disclosure Regarding Foreign Jurisdictions that Prevent Inspections"
   "10" "Directors, Executive Officers and Corporate Governance"
   "11" "Executive Compensation"
   "12" "Security Ownership of Certain Beneficial Owners"
   "13" "Certain Relationships and Related Transactions"
   "14" "Principal Accountant Fees and Services"
   "15" "Exhibits and Financial Statement Schedules"
   "16" "Form 10-K Summary"})

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
   "1.04" "Mine Safety - Reporting of Shutdowns and Patterns of Violations"
   "1.05" "Material Cybersecurity Incidents"
   "2.01" "Completion of Acquisition or Disposition of Assets"
   "2.02" "Results of Operations and Financial Condition"
   "2.03" "Creation of a Direct Financial Obligation or an Obligation under an Off-Balance Sheet Arrangement"
   "2.04" "Triggering Events That Accelerate or Increase a Direct Financial Obligation or an Obligation under an Off-Balance Sheet Arrangement"
   "2.05" "Costs Associated with Exit or Disposal Activities"
   "2.06" "Material Impairments"
   "3.01" "Notice of Delisting or Failure to Satisfy a Continued Listing Rule or Standard; Transfer of Listing"
   "3.02" "Unregistered Sales of Equity Securities"
   "3.03" "Material Modification to Rights of Security Holders"
   "4.01" "Changes in Registrant's Certifying Accountant"
   "4.02" "Non-Reliance on Previously Issued Financial Statements or a Related Audit Report or Completed Interim Review"
   "5.01" "Changes in Control of Registrant"
   "5.02" "Departure of Directors or Certain Officers; Election of Directors; Appointment of Certain Officers; Compensatory Arrangements of Certain Officers"
   "5.03" "Amendments to Articles of Incorporation or Bylaws; Change in Fiscal Year"
   "5.04" "Temporary Suspension of Trading Under Registrant's Employee Benefit Plans"
   "5.05" "Amendments to the Registrant's Code of Ethics, or Waiver of a Provision of the Code of Ethics"
   "5.06" "Change in Shell Company Status"
   "5.07" "Submission of Matters to a Vote of Security Holders"
   "5.08" "Shareholder Director Nominations"
   "6.01" "ABS Informational and Computational Material"
   "6.02" "Change of Servicer or Trustee"
   "6.03" "Change in Credit Enhancement or Other External Support"
   "6.04" "Failure to Make a Required Distribution"
   "6.05" "Securities Act Updating Disclosure"
   "6.06" "Static Pool"
   "6.10" "Alternative Filings of Asset-Backed Issuers"
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
  "Matches an item heading at the start of a text. Group 1 is the item id
   (\"1\", \"1C\", \"9A\", \"5.07\", \"II-1A\"), group 2 the title. A letter
   suffix is only taken when it is not followed by a lower-case letter, so
   \"Item 1Business\" (adjacent spans) reads as item 1, not 1B."
  #"(?i)^\s*item\s+((?:[IVX]+\s*[-\s]\s*)?\d{1,2}(?:[A-Z](?!(?-i:[a-z])))?(?:\.\d{2})?)\s*[.:\-—]?\s*(.{0,160})")

(def ^:private part-pattern
  "Matches a PART I / PART II heading at the start of a short text."
  #"(?i)^\s*part\s+(I{1,3}|IV)\b")

(defn- clean-title
  "Trim a captured heading title; when the regex capture hit its length cap,
   drop the trailing partial word so titles never end mid-word."
  [s]
  (let [t (str/trim (str s))]
    (if (>= (count t) 160)
      (str/trim (str/replace t #"\s+\S*$" ""))
      t)))

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
  (walk/postwalk
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

(defn- subtree-node-count
  "Number of nodes (elements and strings) a node contributes to the
   flattened document-order sequence — itself plus all descendants."
  [node]
  (if (map? node)
    (reduce + 1 (map subtree-node-count (:content node)))
    1))

(defn- normalize-item-id
  "Canonical form of an item id, used both for ids captured from headings
   and for user-supplied ids: trimmed, separators before a digit collapsed
   to a dash, upper-cased (\"i 1\" -> \"I-1\", \"1a\" -> \"1A\")."
  [s]
  (-> s str/trim (str/replace #"\s*[-\s]\s*(?=\d)" "-") str/upper-case))

(defn- with-part
  "Prefix an item id with its PART (\"1A\" in Part II -> \"II-1A\"), as used by
   the 10-Q item ids. Ids that already carry a part, or that appear before any
   PART heading, are returned unchanged."
  [item-id part]
  (if (and part (not (str/includes? item-id "-")))
    (str part "-" item-id)
    item-id))

(defn- find-item-boundaries
  "Returns a seq of {:item-id :title :node-index :body-start} sorted by
   :node-index. Deduplicates by taking the *last* match per item-id to skip
   TOC entries.

   :body-start is where the section body begins in the flat node sequence.
   For a genuine heading node (short text) the heading's own subtree is
   skipped so its text is not repeated at the start of the body; when the
   matching node is a large wrapper that contains the whole section, only
   the node itself is skipped.

   With :parts? true (10-Q), the most recent \"PART I\"/\"PART II\" heading
   is tracked and item ids are prefixed with it (\"I-2\", \"II-1A\"), so the
   Part I and Part II items that share a number both survive."
  ([flat-nodes] (find-item-boundaries flat-nodes {}))
  ([flat-nodes {:keys [parts?]}]
   (let [candidates
         (:out
          (reduce
           (fn [{:keys [part] :as acc} [idx node]]
             (if-not (and (map? node) (heading-tags (:tag node)))
               acc
               (let [text (str/trim (node-text node))
                     part-m (when (and parts? (< (count text) 100))
                              (re-find part-pattern text))
                     m (re-find item-pattern text)]
                 (cond
                   part-m (assoc acc :part (str/upper-case (nth part-m 1)))
                   m (update acc :out conj
                             {:item-id (cond-> (normalize-item-id (nth m 1))
                                         parts? (with-part part))
                              :title (clean-title (nth m 2))
                              :node-index idx
                              :body-start (if (< (count text) 200)
                                            (+ idx (subtree-node-count node))
                                            (inc idx))})
                   :else acc))))
           {:part nil :out []}
           (map-indexed vector flat-nodes)))]
     ;; keep last occurrence of each item-id (body heading, not TOC)
     (->> candidates
          (reduce (fn [acc x] (assoc acc (:item-id x) x)) {})
          vals
          (sort-by :node-index)))))

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
   the next requested item (skipping unrequested boundaries in between).
   opts are passed to find-item-boundaries (:parts? for 10-Q)."
  ([tree target-ids] (extract-items-html tree target-ids {}))
  ([tree target-ids opts]
   (let [flat (flatten-nodes tree)
         all-boundaries (vec (find-item-boundaries flat opts))
         all-count (count all-boundaries)]
     (into {}
           (keep-indexed
            (fn [i {:keys [item-id title body-start]}]
              (when (target-ids item-id)
                (let [next-idx (when (< (inc i) all-count)
                                 (:node-index (nth all-boundaries (inc i))))
                      end-idx (or next-idx (count flat))
                      body-nodes (subvec flat
                                         (min body-start end-idx)
                                         end-idx)
                      text (text-from-node-slice body-nodes)]
                  [item-id {:title title
                            :text text
                            :method :html-heading-boundaries}])))
            all-boundaries)))))

;;; ---------------------------------------------------------------------------
;;; Plain-text item boundary detection (pre-2000 filings)
;;; ---------------------------------------------------------------------------

(defn- html-content?
  "Returns true if the string looks like an HTML document."
  [s]
  (boolean (re-find #"(?i)<(!DOCTYPE|html)" s)))

(def ^:private text-item-pattern
  "Plain-text item heading: a whole line \"ITEM <id>[.:-] <title>\".
   The id is matched atomically and the title must not start with a
   lower-case letter, so wrapped prose lines such as
   \"Item 7 of this Form 10-K under the heading ...\" are not headings."
  #"(?im)^[ \t]*ITEM[ \t]+((?>(?:[IVX]+[ \t]*-[ \t]*)?\d{1,2}(?:[A-Z](?!(?-i:[a-z])))?(?:\.\d{2})?))[ \t]*+[.:\-—]?+[ \t]*+(?!(?-i:[a-z]))([^\r\n]{0,200})$")

(def ^:private text-part-pattern
  "Plain-text PART heading at the start of a line."
  #"(?im)^[ \t]*PART[ \t]+(I{1,3}|IV)\b")

(defn- regex-matches
  "All matches of pattern in text as {:groups [g0 g1 ...] :start :end}."
  [pattern ^String text]
  (let [^java.util.regex.Matcher m (re-matcher pattern text)]
    (loop [acc []]
      (if (.find m)
        (recur (conj acc {:groups (mapv #(.group m (int %)) (range (inc (.groupCount m))))
                          :start (.start m)
                          :end (.end m)}))
        acc))))

(defn- extract-items-text
  "Extract item sections from plain-text filing content using regex boundaries.
   Returns a map of item-id ->
   {:title \"...\" :text \"...\" :method :plain-text-regex} for the ids in
   target-ids.

   Every item heading line delimits a section, whether requested or not, so
   a requested item's text never runs into the next item. With :parts? true
   (10-Q), ids are prefixed with the most recent PART heading (\"I-2\").

   The heading line itself (\"ITEM 7. MD&A\") is excluded from :text — only
   the body content between consecutive headings is returned, consistent with
   the HTML path where heading nodes are not included in :text."
  ([text target-ids] (extract-items-text text target-ids {}))
  ([text target-ids {:keys [parts?]}]
   (let [parts (when parts? (regex-matches text-part-pattern text))
         part-at (fn [pos]
                   (some->> parts
                            (take-while #(< (:start %) pos))
                            last :groups second str/upper-case))
         matches (for [{:keys [groups start end]} (regex-matches text-item-pattern text)]
                   {:item-id (cond-> (normalize-item-id (nth groups 1))
                               parts? (with-part (part-at start)))
                    :title (str/trim (nth groups 2))
                    :start start
                    :end end})]
     (into {}
           (for [[{:keys [item-id title end]} next-m] (partition-all 2 1 matches)
                 :when (target-ids item-id)]
             (let [body-end (if next-m (:start next-m) (count text))
                   body (str/trim (subs text end body-end))]
               [item-id {:title title
                         :text body
                         :method :plain-text-regex}]))))))

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

   10-Q item ids carry their part: \"I-1\" ... \"I-4\", \"II-1\" ... \"II-6\"
   (\"I-2\" is MD&A, \"II-1A\" Risk Factors). User-supplied ids are
   normalized, so \"i 2\" and \"ii-1a\" work too.

   Example:
     (extract-items f :items #{\"7\" \"1A\"} :remove-tables? true)
     ;=> {\"7\"  {:title \"Management's Discussion...\" :text \"...20k chars...\"
     ;            :method :html-heading-boundaries}
     ;    \"1A\" {:title \"Risk Factors\" :text \"...\" :method :html-heading-boundaries}}"
  [filing & {:keys [items remove-tables?] :or {remove-tables? false}}]
  (let [form (:form filing)
        items-map (items-for-form form)
        opts {:parts? (= "10-Q" (str/replace (or form "") #"/A$" ""))}
        target-ids (if items
                     (set (map normalize-item-id items))
                     (set (keys items-map)))
        html (filing/filing-html filing)]
    (cond
      ;; modern HTML path
      (and html (not (str/blank? html)) (html-content? html))
      (let [tree (-> html hickory/parse hickory/as-hickory)
            clean-tree (if remove-tables? (remove-tables tree) tree)]
        (extract-items-html clean-tree target-ids opts))

      ;; plain-text path — either filing-html returned raw text, or no html at all
      :else
      (let [text (if (and html (not (str/blank? html)))
                   html
                   (filing/filing-text filing))]
        (if (str/blank? text)
          {}
          (extract-items-text text target-ids opts))))))

(defn extract-item
  "Extract a single item section from a filing.
   Returns {:title \"...\" :text \"...\" :method ...} or nil."
  [filing item-id]
  (get (extract-items filing :items #{item-id}) (normalize-item-id item-id)))

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
