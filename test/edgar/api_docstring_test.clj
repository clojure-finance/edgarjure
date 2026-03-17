(ns edgar.api-docstring-test
  "Tests that verify the actual return shapes of edgar.api functions
   match what their docstrings describe. Catches docstring/implementation
   mismatches early."
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.api :as e]
            [edgar.extract :as extract]
            [edgar.filing :as filing]))

;;; ---------------------------------------------------------------------------
;;; items — docstring says "item-id → {:title :text :method}"
;;; ---------------------------------------------------------------------------

(def ^:private mock-html
  "<html><body>
     <h2>Item 1A. Risk Factors</h2>
     <p>Competition is intense.</p>
     <h2>Item 7. Management Discussion</h2>
     <p>Revenue grew significantly.</p>
   </body></html>")

(deftest items-return-shape-test
  (testing "items returns map of item-id → map with :title :text :method keys"
    (let [result (with-redefs [filing/filing-html (fn [_] mock-html)]
                   (e/items {:form "10-K"} :only #{"1A" "7"}))]
      (is (map? result) "result is a map")
      (is (= #{"1A" "7"} (set (keys result))) "keys are item ids")
      (doseq [[id entry] result]
        (testing (str "item " id " has :title key")
          (is (contains? entry :title)))
        (testing (str "item " id " has :text key")
          (is (contains? entry :text)))
        (testing (str "item " id " has :method key")
          (is (contains? entry :method)))
        (testing (str "item " id " :text is a string, not a bare string at top level")
          (is (string? (:text entry)))))))
  (testing "items does NOT return bare strings at top level (old wrong shape)"
    (let [result (with-redefs [filing/filing-html (fn [_] mock-html)]
                   (e/items {:form "10-K"} :only #{"1A"}))]
      (is (not (string? (get result "1A")))
          "value must be a map, not a plain string"))))

;;; ---------------------------------------------------------------------------
;;; item — docstring says "Returns {:title :text :method} or nil"
;;; ---------------------------------------------------------------------------

(deftest item-return-shape-test
  (testing "item returns a map with :title :text :method, not a bare string"
    (let [result (with-redefs [filing/filing-html (fn [_] mock-html)]
                   (e/item {:form "10-K"} "1A"))]
      (is (map? result) "result is a map")
      (is (contains? result :title))
      (is (contains? result :text))
      (is (contains? result :method))
      (is (string? (:text result)) ":text is a string")
      (is (keyword? (:method result)) ":method is a keyword")))
  (testing "item returns nil for a non-existent item id"
    (let [result (with-redefs [filing/filing-html (fn [_] mock-html)]
                   (e/item {:form "10-K"} "99"))]
      (is (nil? result))))
  (testing "item :method is :html-heading-boundaries for HTML filing"
    (let [result (with-redefs [filing/filing-html (fn [_] mock-html)]
                   (e/item {:form "10-K"} "7"))]
      (is (= :html-heading-boundaries (:method result))))))

;;; ---------------------------------------------------------------------------
;;; exhibits — docstring says each map has :name :type :description :sequence
;;;            (not :document — that was the wrong field name)
;;; ---------------------------------------------------------------------------

(deftest exhibits-return-shape-test
  (let [mock-index {:files [{:sequence "2" :name "ex21.htm"
                             :type "EX-21" :description "Subsidiaries"
                             :size "5000"}
                            {:sequence "3" :name "ex311.htm"
                             :type "EX-31.1" :description "CEO Certification"
                             :size "3000"}
                            {:sequence "1" :name "report.htm"
                             :type "10-K" :description "Annual Report"
                             :size "900000"}]
                    :formType "10-K"}]
    (testing "exhibits returns only EX- typed entries"
      (let [result (with-redefs [filing/filing-index (fn [_] mock-index)]
                     (e/exhibits {:cik "320193" :accessionNumber "0000320193-24-000001"
                                  :form "10-K"}))]
        (is (= 2 (count result)) "non-exhibit entries excluded")
        (is (every? #(clojure.string/starts-with? (:type %) "EX-") result))))
    (testing "each exhibit map has :name key (not :document)"
      (let [result (with-redefs [filing/filing-index (fn [_] mock-index)]
                     (e/exhibits {:cik "320193" :accessionNumber "0000320193-24-000001"
                                  :form "10-K"}))]
        (is (every? #(contains? % :name) result)
            "each entry has :name")
        (is (not (some #(contains? % :document) result))
            "entries do NOT have :document — docstring was wrong, :name is correct")))
    (testing "each exhibit map has :type :description :sequence keys"
      (let [result (with-redefs [filing/filing-index (fn [_] mock-index)]
                     (e/exhibits {:cik "320193" :accessionNumber "0000320193-24-000001"
                                  :form "10-K"}))]
        (is (every? #(contains? % :type) result))
        (is (every? #(contains? % :description) result))
        (is (every? #(contains? % :sequence) result))))
    (testing ":name value is usable with e/doc-url (a string filename)"
      (let [result (with-redefs [filing/filing-index (fn [_] mock-index)]
                     (e/exhibits {:cik "320193" :accessionNumber "0000320193-24-000001"
                                  :form "10-K"}))
            ex21 (first (filter #(= "EX-21" (:type %)) result))]
        (is (= "ex21.htm" (:name ex21)))))))

(deftest e-filing-limit-passthrough-test
  (testing "e/filing passes :limit (inc n) to filings/get-filings — avoids eager pagination"
    (let [captured-opts (atom nil)
          fake-filings [{:form "10-K" :filingDate "2023-11-03" :accessionNumber "A"}
                        {:form "10-K" :filingDate "2022-10-28" :accessionNumber "B"}
                        {:form "10-K" :filingDate "2021-10-29" :accessionNumber "C"}]]
      (with-redefs [edgar.filings/get-filings
                    (fn [_ & opts]
                      (reset! captured-opts (apply hash-map opts))
                      fake-filings)]
        (testing "n=0 (default) → :limit 1"
          (e/filing "AAPL" :form "10-K")
          (is (= 1 (:limit @captured-opts))))
        (testing "n=1 → :limit 2"
          (e/filing "AAPL" :form "10-K" :n 1)
          (is (= 2 (:limit @captured-opts))))
        (testing "n=2 → :limit 3"
          (e/filing "AAPL" :form "10-K" :n 2)
          (is (= 3 (:limit @captured-opts))))
        (testing "correct filing is returned for n=1"
          (let [result (e/filing "AAPL" :form "10-K" :n 1)]
            (is (= "B" (:accessionNumber result)))))))))
