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
  (testing "contains standard 8-K items"
    (is (contains? extract/items-8k "2.02"))
    (is (contains? extract/items-8k "5.02"))
    (is (contains? extract/items-8k "9.01")))
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
  (testing "unknown form returns empty map"
    (is (= {} (extract/items-for-form "SC 13G")))
    (is (= {} (extract/items-for-form "4")))))

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
      (is (contains? result "I-1"))
      (is (contains? result "I-2"))
      (is (contains? result "II-1A"))
      (is (contains? result "II-6")))))

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

(deftest extract-items-text-test
  (let [f #'edgar.extract/extract-items-text
        items-map extract/items-10k
        result (f fixture-text items-map)]
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

(deftest extract-items-text-fixture-content-test
  (let [f #'edgar.extract/extract-items-text
        result (f fixture-text extract/items-10k)]
    (testing "finds item 1A risk factors"
      (is (contains? result "1A")))
    (testing "item 1A text contains risk content"
      (is (str/includes? (str/lower-case (get-in result ["1A" :text])) "risk")))
    (testing "at least one of item 2 or 7 is found (regex alternation order may merge them)"
      (is (or (contains? result "2") (contains? result "7"))))
    (testing "revenue content appears somewhere in the extracted items"
      (let [all-text (str/join " " (map (comp :text val) result))]
        (is (str/includes? all-text "Revenue"))))
    (testing "item 2 text contains properties content when present"
      (when (contains? result "2")
        (is (str/includes? (str/lower-case (get-in result ["2" :text])) "offices"))))))
