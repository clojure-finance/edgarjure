(ns edgar.extract-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.extract :as extract]
            [clojure.string :as str]
            [hickory.core :as hickory]))

;;; ---------------------------------------------------------------------------
;;; Item map completeness
;;; ---------------------------------------------------------------------------

(deftest items-10k-test
  (testing "contains standard 10-K items"
    (is (contains? extract/items-10k "1"))
    (is (contains? extract/items-10k "1A"))
    (is (contains? extract/items-10k "7"))
    (is (contains? extract/items-10k "7A"))
    (is (contains? extract/items-10k "8")))
  (testing "contains the newer items 1C, 9C and 16"
    (is (= "Cybersecurity" (get extract/items-10k "1C")))
    (is (contains? extract/items-10k "9C"))
    (is (= "Form 10-K Summary" (get extract/items-10k "16"))))
  (testing "all values are non-blank strings"
    (is (every? #(and (string? %) (seq %)) (vals extract/items-10k)))))

(deftest items-10q-test
  (testing "contains standard 10-Q items"
    (is (contains? extract/items-10q "I-1"))
    (is (contains? extract/items-10q "I-2"))
    (is (contains? extract/items-10q "II-1")))
  (testing "all values are non-blank strings"
    (is (every? #(and (string? %) (seq %)) (vals extract/items-10q)))))

(deftest items-8k-test
  (testing "contains every current 8-K item"
    (is (= #{"1.01" "1.02" "1.03" "1.04" "1.05"
             "2.01" "2.02" "2.03" "2.04" "2.05" "2.06"
             "3.01" "3.02" "3.03"
             "4.01" "4.02"
             "5.01" "5.02" "5.03" "5.04" "5.05" "5.06" "5.07" "5.08"
             "6.01" "6.02" "6.03" "6.04" "6.05" "6.06" "6.10"
             "7.01" "8.01" "9.01"}
           (set (keys extract/items-8k)))))
  (testing "all values are non-blank strings"
    (is (every? #(and (string? %) (seq %)) (vals extract/items-8k)))))

;;; ---------------------------------------------------------------------------
;;; items-for-form dispatch
;;; ---------------------------------------------------------------------------

(deftest items-for-form-test
  (testing "10-K returns items-10k"
    (is (= extract/items-10k (extract/items-for-form "10-K"))))
  (testing "10-Q returns items-10q"
    (is (= extract/items-10q (extract/items-for-form "10-Q"))))
  (testing "8-K returns items-8k"
    (is (= extract/items-8k (extract/items-for-form "8-K"))))
  (testing "amended forms return same map as their base form"
    (is (= extract/items-10k (extract/items-for-form "10-K/A")))
    (is (= extract/items-10q (extract/items-for-form "10-Q/A")))
    (is (= extract/items-8k (extract/items-for-form "8-K/A"))))
  (testing "unknown form returns empty map"
    (is (= {} (extract/items-for-form "SC 13G")))
    (is (= {} (extract/items-for-form "4"))))
  (testing "nil form returns empty map"
    (is (= {} (extract/items-for-form nil)))))

;;; ---------------------------------------------------------------------------
;;; item-pattern regex
;;; ---------------------------------------------------------------------------

(deftest item-pattern-test
  (testing "matches standard 10-K item headings"
    (is (re-find extract/item-pattern "Item 1. Business"))
    (is (re-find extract/item-pattern "ITEM 1A. Risk Factors"))
    (is (re-find extract/item-pattern "Item 7  Management's Discussion"))
    (is (re-find extract/item-pattern "item 2.02 Results of Operations")))
  (testing "matches 10-Q Roman-numeral-prefixed item headings"
    (is (re-find extract/item-pattern "Item I-1. Financial Statements"))
    (is (re-find extract/item-pattern "ITEM II-1A. Risk Factors"))
    (is (re-find extract/item-pattern "Item I-2. Management's Discussion"))
    (is (re-find extract/item-pattern "Item II-6. Exhibits")))
  (testing "10-Q pattern captures correct item-id group"
    (is (= "I-1" (-> (re-find extract/item-pattern "Item I-1. Financial Statements") second
                     str/trim
                     (str/replace #"\s*[-\s]\s*(?=\d)" "-")
                     str/upper-case)))
    (is (= "II-1A" (-> (re-find extract/item-pattern "Item II-1A. Risk Factors") second
                       str/trim
                       (str/replace #"\s*[-\s]\s*(?=\d)" "-")
                       str/upper-case))))
  (testing "does not match non-item text"
    (is (nil? (re-find extract/item-pattern "The following table shows")))
    (is (nil? (re-find extract/item-pattern "Total revenues 1,234")))))

(deftest item-pattern-letter-suffix-test
  (let [id+title (fn [s] (when-let [m (re-find extract/item-pattern s)]
                           [(str/upper-case (nth m 1)) (str/trim (nth m 2))]))]
    (testing "1C and 9C are their own items, not item 1/9 with a \"C.\" title"
      (is (= ["1C" "Cybersecurity"] (id+title "Item 1C. Cybersecurity")))
      (is (= ["9C" "Disclosure Regarding Foreign Jurisdictions that Prevent Inspections"]
             (id+title "Item 9C.    Disclosure Regarding Foreign Jurisdictions that Prevent Inspections")))
      (is (= ["16" "Form 10-K Summary"] (id+title "Item 16. Form 10-K Summary"))))
    (testing "8-K dotted ids"
      (is (= ["5.07" "Submission of Matters to a Vote of Security Holders"]
             (id+title "Item 5.07 Submission of Matters to a Vote of Security Holders"))))
    (testing "a title glued to the id is not read as a letter suffix"
      (is (= ["1" "Business"] (id+title "Item 1Business")))
      (is (= ["1A" "Risk Factors"] (id+title "Item 1ARisk Factors"))))))

;;; ---------------------------------------------------------------------------
;;; Inline HTML fixture — simple
;;; ---------------------------------------------------------------------------

(def ^:private simple-html
  "<html><body>
     <p>Table of Contents</p>
     <p>Item 1. Business</p>
     <p>Some business text here.</p>
     <p>Item 1A. Risk Factors</p>
     <p>Risk discussion here.</p>
     <p>Item 7. Management's Discussion and Analysis</p>
     <p>MD&amp;A text goes here.</p>
   </body></html>")

(deftest find-item-boundaries-test
  (let [f #'edgar.extract/find-item-boundaries
        flat-fn #'edgar.extract/flatten-nodes
        tree (-> simple-html hickory/parse hickory/as-hickory)
        flat (flat-fn tree)
        boundaries (f flat)]
    (testing "returns a non-empty seq for a document with item headings"
      (is (seq boundaries)))
    (testing "each boundary has :item-id :title :node-index"
      (is (every? #(and (contains? % :item-id)
                        (contains? % :title)
                        (contains? % :node-index))
                  boundaries)))
    (testing "boundaries are sorted by :node-index"
      (let [idxs (map :node-index boundaries)]
        (is (= idxs (sort idxs)))))
    (testing "finds item 1A"
      (is (some #(= "1A" (:item-id %)) boundaries)))
    (testing "finds item 7"
      (is (some #(= "7" (:item-id %)) boundaries))))

  (testing "u00A0 between Item and number is normalised — heading is found"
    (let [f #'edgar.extract/find-item-boundaries
          flat-fn #'edgar.extract/flatten-nodes
          nbsp-html (str "<html><body>"
                         "<p>Item\u00A07. Management\u2019s Discussion</p>"
                         "<p>Some MD&A content here.</p>"
                         "</body></html>")
          tree (-> nbsp-html hickory/parse hickory/as-hickory)
          flat (flat-fn tree)
          boundaries (f flat)]
      (is (some #(= "7" (:item-id %)) boundaries)
          "Item 7 heading with nbsp must be detected"))))

(deftest extract-items-1c-9c-do-not-overwrite-1-9-test
  ;; Regression: "Item 1C." used to parse as item "1" with title "C. ...",
  ;; and the last match per id won, so it replaced the real Item 1.
  (let [html "<html><body>
               <p>Item 1. Business</p><p>Business body.</p>
               <p>Item 1A. Risk Factors</p><p>Risk body.</p>
               <p>Item 1B. Unresolved Staff Comments</p><p>None.</p>
               <p>Item 1C. Cybersecurity</p><p>Cyber body.</p>
               <p>Item 9. Changes in and Disagreements with Accountants</p><p>None 9.</p>
               <p>Item 9C. Disclosure Regarding Foreign Jurisdictions that Prevent Inspections</p><p>Not applicable 9C.</p>
               <p>Item 16. Form 10-K Summary</p><p>Summary body.</p>
              </body></html>"
        result (with-redefs [edgar.filing/filing-html (fn [_] html)]
                 (extract/extract-items {:form "10-K"}))]
    (is (= #{"1" "1A" "1B" "1C" "9" "9C" "16"} (set (keys result))))
    (is (= "Business" (get-in result ["1" :title])))
    (is (= "Business body." (get-in result ["1" :text])))
    (is (= "Cybersecurity" (get-in result ["1C" :title])))
    (is (= "Cyber body." (get-in result ["1C" :text])))
    (is (= "Changes in and Disagreements with Accountants" (get-in result ["9" :title])))
    (is (= "None 9." (get-in result ["9" :text])))
    (is (= "Not applicable 9C." (get-in result ["9C" :text])))
    (is (= "Summary body." (get-in result ["16" :text])))))

(deftest find-item-boundaries-10q-test
  (let [f #'edgar.extract/find-item-boundaries
        flat-fn #'edgar.extract/flatten-nodes
        html "<html><body>
               <h2>Item I-1. Financial Statements</h2>
               <p>Balance sheet data here.</p>
               <h2>Item I-2. Management's Discussion and Analysis</h2>
               <p>Revenue increased this quarter.</p>
               <h2>ITEM II-1A. Risk Factors</h2>
               <p>Market risk disclosures.</p>
               <h2>Item II-6. Exhibits</h2>
               <p>Exhibit list.</p>
              </body></html>"
        tree (-> html hickory/parse hickory/as-hickory)
        flat (flat-fn tree)
        boundaries (f flat)
        ids (set (map :item-id boundaries))]
    (testing "finds all four 10-Q items with normalized ids"
      (is (= #{"I-1" "I-2" "II-1A" "II-6"} ids)))
    (testing "ids match items-10q keys exactly"
      (is (every? #(contains? @(resolve 'edgar.extract/items-10q) %) ids)))
    (testing "boundaries sorted by node-index"
      (is (= (map :node-index boundaries) (sort (map :node-index boundaries)))))
    (testing "titles extracted correctly"
      (let [bm (into {} (map (juxt :item-id :title) boundaries))]
        (is (str/includes? (get bm "I-1") "Financial"))
        (is (str/includes? (get bm "II-1A") "Risk"))))))

(deftest text-from-node-slice-test
  (let [f #'edgar.extract/text-from-node-slice]
    (testing "extracts non-blank strings from a mixed node sequence"
      (is (= "hello world" (f ["hello" "world"]))))
    (testing "blank strings are ignored"
      (is (= "hello" (f ["hello" "   " ""]))))
    (testing "empty seq returns empty string"
      (is (= "" (f []))))))

(deftest extract-items-html-test
  (let [f #'edgar.extract/extract-items-html
        tree (-> simple-html hickory/parse hickory/as-hickory)
        result (f tree #{"1A" "7"})]
    (testing "returns a map"
      (is (map? result)))
    (testing "keys are the requested item ids"
      (is (= #{"1A" "7"} (set (keys result)))))
    (testing "each value has :title :text :method"
      (is (every? #(and (contains? % :title)
                        (contains? % :text)
                        (contains? % :method))
                  (vals result))))
    (testing "method is :html-heading-boundaries"
      (is (every? #(= :html-heading-boundaries (:method %)) (vals result))))
    (testing "item 1A title contains Risk"
      (is (str/includes? (get-in result ["1A" :title]) "Risk")))
    (testing "item 7 text contains MD&A text"
      (is (str/includes? (get-in result ["7" :text]) "MD")))))

(deftest extract-items-html-boundary-slicing-test
  (testing "item text ends at next boundary even when intermediate items are not requested"
    (let [html "<html><body>
                  <h2>Item 1. Business</h2>
                  <p>Business description here.</p>
                  <h2>Item 1A. Risk Factors</h2>
                  <p>Risk content here.</p>
                  <h2>Item 7. Management Discussion</h2>
                  <p>MD&amp;A content here.</p>
                  <h2>Item 7A. Quantitative Disclosures</h2>
                  <p>Market risk content here.</p>
                  <h2>Item 8. Financial Statements</h2>
                  <p>Financial statements here.</p>
                </body></html>"
          f #'edgar.extract/extract-items-html
          tree (-> html hickory/parse hickory/as-hickory)
          result (f tree #{"1A" "7"})]
      (testing "returns only requested items"
        (is (= #{"1A" "7"} (set (keys result)))))
      (testing "item 1A text does not bleed into item 7 content"
        (let [text-1a (get-in result ["1A" :text])]
          (is (str/includes? text-1a "Risk content"))
          (is (not (str/includes? text-1a "MD&A content"))
              "1A text must stop at Item 7 boundary, not bleed into Item 7 or beyond")))
      (testing "item 7 text does not bleed into item 7A content"
        (let [text-7 (get-in result ["7" :text])]
          (is (str/includes? text-7 "MD"))
          (is (not (str/includes? text-7 "Market risk"))
              "Item 7 text must stop at Item 7A boundary")))))
  (testing "single requested item gets correct end boundary"
    (let [html "<html><body>
                  <h2>Item 1A. Risk Factors</h2>
                  <p>Risk content only.</p>
                  <h2>Item 2. Properties</h2>
                  <p>Properties content.</p>
                  <h2>Item 3. Legal Proceedings</h2>
                  <p>Legal content.</p>
                </body></html>"
          f #'edgar.extract/extract-items-html
          tree (-> html hickory/parse hickory/as-hickory)
          result (f tree #{"1A"})]
      (is (= #{"1A"} (set (keys result))))
      (let [text (get-in result ["1A" :text])]
        (is (str/includes? text "Risk content"))
        (is (not (str/includes? text "Properties"))
            "1A text must stop at Item 2 boundary even though Item 2 was not requested")))))

;;; ---------------------------------------------------------------------------
;;; Fixture HTML loaded from file — more complete 10-K
;;; ---------------------------------------------------------------------------

(def ^:private fixture-html
  (slurp (clojure.java.io/resource "fixtures/10k_simple.html")))

(deftest fixture-html-find-boundaries-test
  (let [tree (-> fixture-html hickory/parse hickory/as-hickory)
        flat (#'edgar.extract/flatten-nodes tree)
        boundaries (#'edgar.extract/find-item-boundaries flat)
        ids (set (map :item-id boundaries))]
    (testing "finds Item 1 from fixture"
      (is (contains? ids "1")))
    (testing "finds Item 1A from fixture"
      (is (contains? ids "1A")))
    (testing "finds Item 7 from fixture"
      (is (contains? ids "7")))
    (testing "finds Item 8 from fixture"
      (is (contains? ids "8")))
    (testing "item ids are upper-case strings"
      (is (every? string? ids)))))

(deftest fixture-html-extract-items-html-test
  (let [tree (-> fixture-html hickory/parse hickory/as-hickory)
        result (#'edgar.extract/extract-items-html tree #{"1A" "7"})]
    (testing "extracts item 1A from fixture"
      (is (contains? result "1A")))
    (testing "item 1A text contains risk content"
      (is (str/includes? (str/lower-case (get-in result ["1A" :text])) "risk")))
    (testing "extracts item 7 from fixture"
      (is (contains? result "7")))
    (testing "item 7 text contains financial/MD&A content"
      (let [text (str/lower-case (get-in result ["7" :text]))]
        (is (or (str/includes? text "revenue")
                (str/includes? text "operating")
                (str/includes? text "income")))))
    (testing "method is :html-heading-boundaries for all"
      (is (every? #(= :html-heading-boundaries (:method %)) (vals result))))))

;;; ---------------------------------------------------------------------------
;;; remove-tables
;;; ---------------------------------------------------------------------------

(def ^:private html-with-tables
  "<html><body>
     <p>Some text before.</p>
     <table><tr><td>Revenue</td><td>1000</td></tr></table>
     <p>Some text after.</p>
     <table><tr><td>Costs</td><td>800</td></tr></table>
   </body></html>")

(deftest remove-tables-test
  (let [f #'edgar.extract/remove-tables
        tree (-> html-with-tables hickory/parse hickory/as-hickory)
        cleaned (f tree)
        cleaned-text (str/join " " (filter string? (#'edgar.extract/flatten-nodes cleaned)))]
    (testing "no :table tags remain in cleaned tree"
      (is (not (some #(and (map? %) (= :table (:tag %)))
                     (#'edgar.extract/flatten-nodes cleaned)))))
    (testing "non-table text is preserved"
      (is (str/includes? cleaned-text "Some text before"))
      (is (str/includes? cleaned-text "Some text after")))
    (testing "table cell content is removed"
      (is (not (str/includes? cleaned-text "Revenue")))
      (is (not (str/includes? cleaned-text "Costs"))))))

;;; ---------------------------------------------------------------------------
;;; extract-items with remove-tables? option (mock filing)
;;; ---------------------------------------------------------------------------

(def ^:private mock-filing-html
  "<html><body>
     <h2>Item 1A. Risk Factors</h2>
     <p>Competition is intense.</p>
     <table><tr><th>Risk</th><th>Impact</th></tr><tr><td>Currency</td><td>High</td></tr></table>
     <p>We face regulatory risk.</p>
     <h2>Item 7. Management's Discussion and Analysis</h2>
     <p>Revenue grew 10% to $394 billion.</p>
   </body></html>")

(deftest extract-items-via-mock-filing-test
  (let [mock-filing {:form "10-K" :_html mock-filing-html}
        ;; Patch filing-html to use our fixture
        result (with-redefs [edgar.filing/filing-html (fn [_] mock-filing-html)]
                 (extract/extract-items mock-filing :items #{"1A" "7"}))]
    (testing "returns a map with both items"
      (is (= #{"1A" "7"} (set (keys result)))))
    (testing "item 1A text contains risk content"
      (let [text (get-in result ["1A" :text])]
        (is (str/includes? text "Competition"))))
    (testing "item 7 text contains financial content"
      (let [text (get-in result ["7" :text])]
        (is (str/includes? text "Revenue"))))
    (testing "method is :html-heading-boundaries"
      (is (every? #(= :html-heading-boundaries (:method %)) (vals result))))))

(def mock-10q-html
  "<html><body>
   <h2>Item I-1. Financial Statements</h2>
   <p>Quarterly balance sheet and income statement.</p>
   <h2>Item I-2. Management's Discussion and Analysis</h2>
   <p>Revenue increased 12% year-over-year.</p>
   <h2>ITEM II-1A. Risk Factors</h2>
   <p>We face significant market risks.</p>
   <h2>Item II-6. Exhibits</h2>
   <p>See exhibit index.</p>
  </body></html>")

(deftest extract-items-10q-normalized-ids-test
  (testing "explicit correct 10-Q ids"
    (let [result (with-redefs [edgar.filing/filing-html (fn [_] mock-10q-html)]
                   (extract/extract-items {:form "10-Q"} :items #{"I-1" "II-1A"}))]
      (is (= #{"I-1" "II-1A"} (set (keys result))))
      (is (str/includes? (get-in result ["I-1" :text]) "balance sheet"))
      (is (str/includes? (get-in result ["II-1A" :text]) "risk"))))
  (testing "lower-case user-supplied 10-Q ids are normalized"
    (let [result (with-redefs [edgar.filing/filing-html (fn [_] mock-10q-html)]
                   (extract/extract-items {:form "10-Q"} :items #{"i-1" "ii-1a"}))]
      (is (= #{"I-1" "II-1A"} (set (keys result))))))
  (testing "no :items extracts all 10-Q sections"
    (let [result (with-redefs [edgar.filing/filing-html (fn [_] mock-10q-html)]
                   (extract/extract-items {:form "10-Q"}))]
      (is (= #{"I-1" "I-2" "II-1A" "II-6"} (set (keys result)))))))

;; Real 10-Q layout: "Item 1." appears in both parts, distinguished only by
;; the preceding PART heading; a table of contents repeats the headings.
(def ^:private real-10q-html
  "<html><body>
   <table>
    <tr><td>Part I</td></tr>
    <tr><td>Item 1.</td><td>Financial Statements</td><td>1</td></tr>
    <tr><td>Item 2.</td><td>Management's Discussion and Analysis</td><td>10</td></tr>
    <tr><td>Item 3.</td><td>Quantitative and Qualitative Disclosures About Market Risk</td><td>15</td></tr>
    <tr><td>Item 4.</td><td>Controls and Procedures</td><td>16</td></tr>
    <tr><td>Part II</td></tr>
    <tr><td>Item 1.</td><td>Legal Proceedings</td><td>17</td></tr>
    <tr><td>Item 1A.</td><td>Risk Factors</td><td>17</td></tr>
    <tr><td>Item 6.</td><td>Exhibits</td><td>20</td></tr>
   </table>
   <p>PART I — FINANCIAL INFORMATION</p>
   <p>Item 1. Financial Statements</p>
   <p>Condensed consolidated balance sheets.</p>
   <p>Item 2. Management's Discussion and Analysis of Financial Condition and Results of Operations</p>
   <p>Net sales increased during the quarter.</p>
   <p>Item 3. Quantitative and Qualitative Disclosures About Market Risk</p>
   <p>No material changes.</p>
   <p>Item 4. Controls and Procedures</p>
   <p>Controls are effective.</p>
   <p>PART II — OTHER INFORMATION</p>
   <p>Item 1. Legal Proceedings</p>
   <p>Litigation matters.</p>
   <p>Item 1A. Risk Factors</p>
   <p>Updated risk factors.</p>
   <p>Item 6. Exhibits</p>
   <p>Exhibit index.</p>
  </body></html>")

(deftest extract-items-10q-part-tracking-test
  (let [extract (fn [& opts]
                  (with-redefs [edgar.filing/filing-html (fn [_] real-10q-html)]
                    (apply extract/extract-items {:form "10-Q"} opts)))
        result (extract)]
    (testing "Part I and Part II items with the same number both survive"
      (is (= #{"I-1" "I-2" "I-3" "I-4" "II-1" "II-1A" "II-6"} (set (keys result)))))
    (testing "bodies come from the body headings, not the table of contents"
      (is (= "Condensed consolidated balance sheets." (get-in result ["I-1" :text])))
      ;; PART headings are not section boundaries, so the Part II heading
      ;; trails the last Part I item (as "PART II" trails 10-K Item 4)
      (is (str/starts-with? (get-in result ["I-4" :text]) "Controls are effective."))
      (is (= "Litigation matters." (get-in result ["II-1" :text])))
      (is (= "Updated risk factors." (get-in result ["II-1A" :text])))
      (is (= "Exhibit index." (get-in result ["II-6" :text]))))
    (testing "I-2 is MD&A"
      (let [mdna (with-redefs [edgar.filing/filing-html (fn [_] real-10q-html)]
                   (extract/extract-item {:form "10-Q"} "I-2"))]
        (is (str/starts-with? (:title mdna) "Management's Discussion"))
        (is (= "Net sales increased during the quarter." (:text mdna)))))
    (testing "user-supplied ids are normalized"
      (is (= "Net sales increased during the quarter."
             (:text (with-redefs [edgar.filing/filing-html (fn [_] real-10q-html)]
                      (extract/extract-item {:form "10-Q"} "i 2")))))
      (is (= #{"II-1A"} (set (keys (extract :items #{"ii-1a"}))))))
    (testing "10-Q/A uses the same part-prefixed ids"
      (is (contains? (with-redefs [edgar.filing/filing-html (fn [_] real-10q-html)]
                       (extract/extract-items {:form "10-Q/A"}))
                     "II-1A")))
    (testing "10-K extraction does not prefix ids with the part"
      (is (= #{"1" "2" "3" "4" "1A" "6"}
             (set (keys (with-redefs [edgar.filing/filing-html (fn [_] real-10q-html)]
                          (extract/extract-items {:form "10-K"})))))))))

(def ^:private mock-8k-html
  "<html><body>
   <p>Item 5.07</p><p>Submission of Matters to a Vote of Security Holders.</p>
   <p>The annual meeting was held.</p>
   <p>Item 9.01</p><p>Financial Statements and Exhibits.</p>
   <p>Exhibit 104.</p>
  </body></html>")

(deftest extract-items-8k-test
  (let [result (with-redefs [edgar.filing/filing-html (fn [_] mock-8k-html)]
                 (extract/extract-items {:form "8-K"}))]
    (is (= #{"5.07" "9.01"} (set (keys result))))
    (is (str/includes? (get-in result ["5.07" :text]) "annual meeting"))
    (is (not (str/includes? (get-in result ["5.07" :text]) "Exhibit 104")))))

(deftest extract-items-remove-tables-mock-test
  (let [result (with-redefs [edgar.filing/filing-html (fn [_] mock-filing-html)]
                 (extract/extract-items {:form "10-K"} :items #{"1A"} :remove-tables? true))]
    (testing "table content is absent when remove-tables? true"
      (let [text (get-in result ["1A" :text])]
        (is (not (str/includes? text "Currency")))
        (is (not (str/includes? text "Impact")))))
    (testing "paragraph text remains with remove-tables? true"
      (let [text (get-in result ["1A" :text])]
        (is (str/includes? text "Competition"))))))

(deftest extract-item-single-mock-test
  (let [result (with-redefs [edgar.filing/filing-html (fn [_] mock-filing-html)]
                 (extract/extract-item {:form "10-K"} "7"))]
    (testing "returns a single item map"
      (is (map? result)))
    (testing "has :title :text :method keys"
      (is (contains? result :title))
      (is (contains? result :text))
      (is (contains? result :method)))
    (testing "item 7 text contains revenue content"
      (is (str/includes? (get result :text "") "Revenue")))))

;;; ---------------------------------------------------------------------------
;;; Plain-text fixture loaded from file
;;; ---------------------------------------------------------------------------

(def ^:private fixture-text
  (slurp (clojure.java.io/resource "fixtures/10k_plaintext.txt")))

(def ^:private all-10k-ids (set (keys extract/items-10k)))

(deftest extract-items-text-test
  (let [f #'edgar.extract/extract-items-text
        result (f fixture-text all-10k-ids)]
    (testing "returns a map"
      (is (map? result)))
    (testing "finds item 1"
      (is (contains? result "1")))
    (testing "item 1 method is :plain-text-regex"
      (is (= :plain-text-regex (get-in result ["1" :method]))))
    (testing "item 1 text contains business content"
      (is (str/includes? (get-in result ["1" :text]) "widgets")))
    (testing "all found items have :title :text :method"
      (is (every? #(and (contains? % :title)
                        (contains? % :text)
                        (contains? % :method))
                  (vals result))))))

(deftest extract-items-text-heading-exclusion-test
  ;; Regression test for Issue #12:
  ;; Previously :text started at (:start match) — the match start position —
  ;; so the "ITEM X. TITLE" heading line was included in :text.
  ;; Fixed: :text starts at (:end match) — after the matched heading —
  ;; consistent with the HTML path where heading nodes are excluded.
  (let [f #'edgar.extract/extract-items-text
        text "ITEM 1. BUSINESS\nAcme Corp makes widgets.\n\nITEM 1A. RISK FACTORS\nCompetition is intense.\n\nITEM 7. MANAGEMENT'S DISCUSSION AND ANALYSIS OF FINANCIAL CONDITION AND RESULTS OF OPERATIONS\nRevenue grew 10%."
        result (f text all-10k-ids)]
    (testing "item 1 :text does NOT start with the heading line"
      (let [body (get-in result ["1" :text])]
        (is (some? body))
        (is (not (str/starts-with? (str/triml body) "ITEM"))
            ":text must not begin with 'ITEM 1. BUSINESS'")
        (is (not (re-find #"(?i)^item\s+1" (str/triml body)))
            "heading text must be absent from body")))
    (testing "item 1 :text contains only body content"
      (is (= "Acme Corp makes widgets." (get-in result ["1" :text]))))
    (testing "item 1A :text does NOT include its heading"
      (is (= "Competition is intense." (get-in result ["1A" :text]))))
    (testing "item 7 :text does NOT include its heading"
      (is (= "Revenue grew 10%." (get-in result ["7" :text]))))
    (testing ":title is populated from the whole heading line, punctuation included"
      (is (= "BUSINESS" (get-in result ["1" :title])))
      (is (= "RISK FACTORS" (get-in result ["1A" :title])))
      (is (= "MANAGEMENT'S DISCUSSION AND ANALYSIS OF FINANCIAL CONDITION AND RESULTS OF OPERATIONS"
             (get-in result ["7" :title]))))))

(deftest extract-items-text-fixture-content-test
  (let [f #'edgar.extract/extract-items-text
        result (f fixture-text all-10k-ids)]
    (testing "finds exactly the items present in the fixture"
      (is (= #{"1" "1A" "2" "7" "7A" "8"} (set (keys result)))))
    (testing "item 1A text contains risk content"
      (is (str/includes? (str/lower-case (get-in result ["1A" :text])) "risk")))
    (testing "item 2 text is the properties section only"
      (is (= "The company owns offices in New York and San Francisco."
             (get-in result ["2" :text]))))
    (testing "item 7 (heading with an apostrophe) is found with its full title"
      (is (= "MANAGEMENT'S DISCUSSION AND ANALYSIS" (get-in result ["7" :title])))
      (is (str/starts-with? (get-in result ["7" :text]) "Revenue for fiscal year 2023"))
      (is (not (str/includes? (get-in result ["7" :text]) "interest rate"))
          "item 7 must stop at the item 7A heading"))
    (testing "only the requested ids are returned, with full-document boundaries"
      (let [only-7 (f fixture-text #{"7"})]
        (is (= #{"7"} (set (keys only-7))))
        (is (= (get result "7") (get only-7 "7")))))))

(deftest extract-items-text-prose-lines-test
  (testing "a wrapped prose line starting with \"Item 7 of ...\" is not a heading"
    (let [f #'edgar.extract/extract-items-text
          text (str "ITEM 1. BUSINESS\nApple designs computers. See Part II,\n"
                    "Item 7 of this Form 10-K under the heading \"Net Sales,\" for details.\n"
                    "More business text.\n\n"
                    "ITEM 7. MANAGEMENT'S DISCUSSION AND ANALYSIS\nNet sales rose.")
          result (f text all-10k-ids)]
      (is (= #{"1" "7"} (set (keys result))))
      (is (str/includes? (get-in result ["1" :text]) "More business text."))
      (is (= "Net sales rose." (get-in result ["7" :text]))))))

(deftest extract-items-text-10q-parts-test
  (let [f #'edgar.extract/extract-items-text
        text (str "PART I. FINANCIAL INFORMATION\n\n"
                  "ITEM 1. FINANCIAL STATEMENTS\nBalance sheet.\n\n"
                  "ITEM 2. MANAGEMENT'S DISCUSSION AND ANALYSIS\nSales grew.\n\n"
                  "PART II. OTHER INFORMATION\n\n"
                  "ITEM 1. LEGAL PROCEEDINGS\nLawsuits.\n\n"
                  "ITEM 6. EXHIBITS AND REPORTS ON FORM 8-K\nExhibit list.")
        result (f text (set (keys extract/items-10q)) {:parts? true})]
    (is (= #{"I-1" "I-2" "II-1" "II-6"} (set (keys result))))
    (is (str/starts-with? (get-in result ["I-1" :text]) "Balance sheet."))
    (is (str/starts-with? (get-in result ["I-2" :text]) "Sales grew."))
    (is (= "Lawsuits." (get-in result ["II-1" :text])))))

(deftest html-content-predicate-test
  (let [f #'edgar.extract/html-content?]
    (testing "recognises HTML DOCTYPE"
      (is (f "<!DOCTYPE html><html><body></body></html>")))
    (testing "recognises lowercase html tag"
      (is (f "<html><body>text</body></html>")))
    (testing "recognises mixed-case"
      (is (f "<HTML><BODY></BODY></HTML>")))
    (testing "rejects plain text"
      (is (not (f "ITEM 1. Business\nSome business text."))))
    (testing "rejects blank string"
      (is (not (f "   "))))
    (testing "rejects nil-like empty"
      (is (not (f ""))))))

(def ^:private plain-text-filing-content
  "ANNUAL REPORT ON FORM 10-K

ITEM 1. BUSINESS
Acme Corp manufactures widgets for industrial use.
Revenue was $100 million last year.

ITEM 1A. RISK FACTORS
Competition is intense in the widget market.
Raw material costs present a risk.

ITEM 7. MANAGEMENT'S DISCUSSION AND ANALYSIS
Revenue increased 10% driven by volume growth.
Operating income improved year over year.")

(deftest extract-items-plain-text-fallback-test
  (testing "plain-text filing routes to extract-items-text, not HTML path"
    (let [result (with-redefs [edgar.filing/filing-html (fn [_] plain-text-filing-content)]
                   (extract/extract-items {:form "10-K"} :items #{"1" "1A" "7"}))]
      (testing "all three items extracted"
        (is (= #{"1" "1A" "7"} (set (keys result)))))
      (testing "method is :plain-text-regex, not :html-heading-boundaries"
        (is (every? #(= :plain-text-regex (:method %)) (vals result))))
      (testing "item 1 contains business text"
        (is (str/includes? (get-in result ["1" :text]) "widgets")))
      (testing "item 1A contains risk text"
        (is (str/includes? (str/lower-case (get-in result ["1A" :text])) "risk")))
      (testing "item 7 contains MD&A text"
        (is (str/includes? (get-in result ["7" :text]) "Revenue")))
      (testing "heading lines are excluded from :text (Issue #12 regression)"
        (is (not (re-find #"(?i)ITEM\s+1\b" (get-in result ["1" :text])))
            "item 1 body must not contain its own heading")
        (is (not (re-find #"(?i)ITEM\s+1A\b" (get-in result ["1A" :text])))
            "item 1A body must not contain its own heading"))))
  (testing "nil html falls back to filing-text"
    (let [result (with-redefs [edgar.filing/filing-html (fn [_] nil)
                               edgar.filing/filing-text (fn [_] plain-text-filing-content)]
                   (extract/extract-items {:form "10-K"} :items #{"1"}))]
      (is (contains? result "1"))
      (is (= :plain-text-regex (get-in result ["1" :method])))))
  (testing "blank html falls back to filing-text"
    (let [result (with-redefs [edgar.filing/filing-html (fn [_] "   ")
                               edgar.filing/filing-text (fn [_] plain-text-filing-content)]
                   (extract/extract-items {:form "10-K"} :items #{"1"}))]
      (is (contains? result "1"))
      (is (= :plain-text-regex (get-in result ["1" :method])))))
  (testing "HTML content is NOT routed to plain-text path"
    (let [result (with-redefs [edgar.filing/filing-html (fn [_] mock-filing-html)]
                   (extract/extract-items {:form "10-K"} :items #{"1A"}))]
      (is (= :html-heading-boundaries (get-in result ["1A" :method]))))))

(deftest extract-items-plain-text-only-test
  ;; Regression: the plain-text path ignored :items and returned every item.
  (let [extract (fn [& opts]
                  (with-redefs [edgar.filing/filing-html (fn [_] plain-text-filing-content)]
                    (apply extract/extract-items {:form "10-K"} opts)))]
    (testing ":items restricts the result to the requested ids"
      (is (= #{"7"} (set (keys (extract :items #{"7"})))))
      (is (= #{"1A"} (set (keys (extract :items #{"1a"}))))))
    (testing "a requested item still ends at the next (unrequested) heading"
      (is (= "Competition is intense in the widget market.\nRaw material costs present a risk."
             (get-in (extract :items #{"1A"}) ["1A" :text]))))
    (testing "without :items all items are returned"
      (is (= #{"1" "1A" "7"} (set (keys (extract))))))
    (testing "extract-item works on plain text"
      (is (= "MANAGEMENT'S DISCUSSION AND ANALYSIS"
             (:title (with-redefs [edgar.filing/filing-html (fn [_] plain-text-filing-content)]
                       (extract/extract-item {:form "10-K"} "7"))))))))
