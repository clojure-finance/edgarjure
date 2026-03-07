(ns edgar.extract
  (:require [edgar.filing :as filing]
            [clojure.string :as str]
            [hickory.core :as hickory]
            [hickory.select :as sel]
            [hickory.zip :as hzip]
            [clojure.zip :as zip]
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
  (case form
    "10-K" items-10k
    "10-Q" items-10q
    "8-K" items-8k
    {}))

;;; ---------------------------------------------------------------------------
;;; HTML item boundary detection
;;; Strategy: look for heading elements whose text matches "Item N" patterns
;;; ---------------------------------------------------------------------------

(def item-pattern
  #"(?i)^\s*item\s+(\d{1,2}[AB]?(?:\.\d{2})?)\s*[.:\-—]?\s*(.{0,80})")

(defn- heading-text
  "Extract normalised text from a hickory node."
  [node]
  (when (map? node)
    (let [children (:content node)]
      (str/trim
       (str/join " "
                 (filter string?
                         (tree-seq #(and (map? %) (:content %))
                                   :content
                                   node)))))))

(defn- dedupe-by [k coll]
  (vals (reduce (fn [acc x] (if (acc (k x)) acc (assoc acc (k x) x))) {} coll)))

(defn- item-headings
  "Extract all (position, item-id, text) tuples from hickory HTML tree."
  [tree]
  (let [headings (sel/select (sel/or (sel/tag :h1)
                                     (sel/tag :h2)
                                     (sel/tag :h3)
                                     (sel/tag :h4)
                                     (sel/tag :p)
                                     (sel/tag :div))
                             tree)]
    (->> headings
         (keep (fn [node]
                 (let [text (heading-text node)]
                   (when-let [m (re-find item-pattern (or text ""))]
                     {:item-id (str/upper-case (nth m 1))
                      :label (str/trim (nth m 2))
                      :node node}))))
         (dedupe-by :item-id))))

;;; ---------------------------------------------------------------------------
;;; Plain-text item boundary detection (pre-2000 filings)
;;; ---------------------------------------------------------------------------

(defn- extract-items-text
  "Extract item sections from plain-text filing content using regex boundaries."
  [text items-map]
  (let [pattern (re-pattern
                 (str "(?im)^\\s*ITEM\\s+("
                      (str/join "|" (map #(str/replace % "." "\\.") (keys items-map)))
                      ")\\s*[.:\\-—]?\\s*[\\w\\s]{0,80}$"))]
    (let [matches (vec (re-seq pattern text))
          positions (vec (map #(.start (re-matcher pattern (first %))) matches))]
      (into {}
            (for [i (range (count matches))]
              (let [item-id (str/upper-case (second (nth matches i)))
                    start (nth positions i)
                    end (if (< (inc i) (count positions))
                          (nth positions (inc i))
                          (count text))]
                [item-id (str/trim (subs text start end))]))))))

;;; ---------------------------------------------------------------------------
;;; Table removal (for NLP mode)
;;; Strips <table> elements from HTML before text extraction
;;; ---------------------------------------------------------------------------

(defn- remove-tables
  "Remove all <table> elements from a hickory tree."
  [tree]
  (clojure.walk/postwalk
   (fn [node]
     (if (and (map? node) (= :table (:tag node)))
       nil
       node))
   tree))

;;; ---------------------------------------------------------------------------
;;; Main extraction API
;;; ---------------------------------------------------------------------------

(defn extract-items
  "Extract item sections from a filing as a map of item-id → text.
   filing     : filing metadata map (from edgar.filings/get-filings)
   Options:
     :items         - set of item ids to extract e.g. #{\"7\" \"1A\"} (default all)
     :remove-tables? - strip numerical tables before extraction (default false, set
                       true for NLP/text-only workflows)
   Returns a map like:
     {\"1A\" \"Risk factors text...\"
      \"7\"  \"MD&A text...\"}"
  [filing & {:keys [items remove-tables?] :or {remove-tables? false}}]
  (let [html (filing/filing-html filing)
        form (:form filing)
        items-map (items-for-form form)]
    (if (str/blank? html)
      {}
      (let [tree (-> html hickory/parse hickory/as-hickory)
            clean-tree (if remove-tables? (remove-tables tree) tree)
            headings (item-headings clean-tree)
            target-ids (if items (set (map str/upper-case items)) (set (keys items-map)))]
        (into {}
              (for [{:keys [item-id]} (filter #(target-ids (:item-id %)) headings)]
                [item-id (heading-text (some #(when (= item-id (:item-id %)) (:node %))
                                             headings))]))))))

(defn extract-item
  "Extract a single item section from a filing. Returns text string or nil."
  [filing item-id]
  (get (extract-items filing :items #{item-id}) (str/upper-case item-id)))

;;; ---------------------------------------------------------------------------
;;; Batch extraction (edgar-crawler analog)
;;; Process a directory of saved HTML filings → write .edn output files
;;; ---------------------------------------------------------------------------

(defn batch-extract!
  "Extract item sections from a seq of filings and write {accession-no}.edn files to output-dir.
   Options:
     :items          - set of item ids to extract (default all)
     :remove-tables? - strip numeric tables (default false)
     :skip-existing? - skip filings whose output file already exists (default true)"
  [filing-seq output-dir & {:keys [items remove-tables? skip-existing?]
                            :or {remove-tables? false skip-existing? true}}]
  (fs/create-dirs output-dir)
  (doall
   (for [f filing-seq
         :let [acc (:accessionNumber f)
               out-file (fs/path output-dir (str acc ".edn"))]
         :when (or (not skip-existing?) (not (fs/exists? out-file)))]
     (try
       (let [extracted (extract-items f
                                      :items items
                                      :remove-tables? remove-tables?)]
         (spit (str out-file) (pr-str extracted))
         {:accession-no acc :status :ok :items (keys extracted)})
       (catch Exception e
         {:accession-no acc :status :error :message (.getMessage e)})))))
