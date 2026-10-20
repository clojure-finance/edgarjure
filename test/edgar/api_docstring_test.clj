(ns edgar.api-docstring-test
  "Tests that verify the actual return shapes of edgar.api functions
   match what their docstrings describe. Catches docstring/implementation
   mismatches early."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [edgar.api :as e]
            [edgar.company]
            [edgar.filing :as filing]
            [edgar.filings]
            [edgar.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; items / item — "item-id → {:title :text :method}"
;;; ---------------------------------------------------------------------------

(def ^:private mock-html
  "<html><body>
     <h2>Item 1A. Risk Factors</h2>
     <p>Competition is intense.</p>
     <h2>Item 7. Management Discussion</h2>
     <p>Revenue grew significantly.</p>
   </body></html>")

(def ^:private ten-k {:cik "0000320193" :accessionNumber "0000320193-25-000079" :form "10-K"})

(deftest items-return-shape-test
  (testing "items returns map of item-id → map with string :text, :title, :method"
    (let [result (with-redefs [filing/filing-html (fn [_] mock-html)]
                   (e/items ten-k :only #{"1A" "7"}))]
      (is (= #{"1A" "7"} (set (keys result))))
      (doseq [[id entry] result]
        (testing (str "item " id)
          (is (= #{:title :text :method} (set (keys entry))))
          (is (string? (:text entry))))))))

(deftest item-return-shape-test
  (with-redefs [filing/filing-html (fn [_] mock-html)]
    (testing "item returns {:title :text :method} with :html-heading-boundaries for HTML"
      (let [result (e/item ten-k "7")]
        (is (string? (:text result)))
        (is (= :html-heading-boundaries (:method result)))))
    (testing "item returns nil for a non-existent item id"
      (is (nil? (e/item ten-k "99"))))))

;;; ---------------------------------------------------------------------------
;;; exhibits / exhibit / filing-document / doc-url
;;; ---------------------------------------------------------------------------

(def ^:private mock-index
  {:files [{:sequence "1" :name "aapl-20250927.htm" :type "10-K" :description "10-K" :size "900000"}
           {:sequence "2" :name "a10-kexhibit21109272025.htm" :type "EX-21.1"
            :description "EX-21.1" :size "5000"}
           {:sequence "3" :name "a10-kexhibit31109272025.htm" :type "EX-31.1"
            :description "EX-31.1" :size "3000"}]
   :formType "10-K"})

(def ^:private mock-filing {:cik "320193" :accessionNumber "0000320193-25-000079" :form "10-K"})

(deftest exhibits-return-shape-test
  (testing "exhibits returns only EX- entries, each {:name :type :description :sequence :size}"
    (let [result (with-redefs [filing/filing-index (fn [_] mock-index)]
                   (e/exhibits mock-filing))]
      (is (= ["EX-21.1" "EX-31.1"] (map :type result)))
      (is (every? #(= #{:name :type :description :sequence :size} (set (keys %))) result)))))

(deftest exhibit-and-document-workflow-test
  (with-redefs [filing/filing-index (fn [_] mock-index)
                filing/filing-document (fn [_ doc-name & _] (str "content-of-" doc-name))]
    (testing "e/exhibit matches :type exactly"
      (is (nil? (e/exhibit mock-filing "EX-21")))
      (is (= "a10-kexhibit21109272025.htm" (:name (e/exhibit mock-filing "EX-21.1")))))
    (testing "e/filing-document and e/doc-url take the exhibit's :name"
      (let [ex (e/exhibit mock-filing "EX-21.1")]
        (is (= "content-of-a10-kexhibit21109272025.htm" (e/filing-document mock-filing (:name ex))))
        (is (= "https://www.sec.gov/Archives/edgar/data/320193/000032019325000079/a10-kexhibit21109272025.htm"
               (e/doc-url mock-filing (:name ex))))))))

;;; ---------------------------------------------------------------------------
;;; filings / filing / quarterly-index
;;; ---------------------------------------------------------------------------

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
        (testing "n=2 → :limit 3"
          (e/filing "AAPL" :form "10-K" :n 2)
          (is (= 3 (:limit @captured-opts))))
        (testing "correct filing is returned for n=1"
          (is (= "B" (:accessionNumber (e/filing "AAPL" :form "10-K" :n 1)))))))))

(deftest filing-nth-out-of-range-nil-test
  (testing "e/filing with :n past the available filings returns nil, never throws"
    (with-redefs [edgar.filings/get-filings
                  (fn [_ & _] [{:form "10-K" :accessionNumber "0000000000-24-000001"}])]
      (is (nil? (e/filing "AAPL" :form "10-K" :n 5))))))

(deftest quarterly-index-passthrough-test
  (with-redefs [edgar.filings/get-quarterly-index
                (fn [year quarter & {:keys [form]}] [{:year year :quarter quarter :form form}])]
    (is (= [{:year 2025 :quarter 4 :form "10-K"}] (e/quarterly-index 2025 4 :form "10-K")))))

;;; ---------------------------------------------------------------------------
;;; obj — the form parsers are loaded by edgar.api itself
;;; ---------------------------------------------------------------------------

(deftest obj-form-parsers-loaded-test
  (testing "requiring edgar.api registers the Forms 3/4/5, 13F and Schedule 13D/G parsers"
    (let [registered (set (keys (methods filing/filing-obj)))]
      (is (every? registered ["3" "4" "5" "4/A" "13F-HR" "SCHEDULE 13D" "SCHEDULE 13G"])
          (str "registered: " (sort (remove keyword? registered)))))))

;;; ---------------------------------------------------------------------------
;;; Company lookups validate their argument
;;; ---------------------------------------------------------------------------

(deftest company-functions-malli-validation-test
  (with-redefs [edgar.company/company-cik (fn [_] "0000320193")
                edgar.company/cik->ticker (fn [_] "AAPL")
                edgar.company/get-company (fn [_] {:name "Apple Inc."})
                edgar.company/company-name (fn [_] "Apple Inc.")
                edgar.company/company-metadata (fn [_] {:name "Apple Inc."})]
    (doseq [[fn-name f valid expected] [["e/cik" e/cik "AAPL" "0000320193"]
                                        ["e/ticker" e/ticker 320193 "AAPL"]
                                        ["e/company" e/company "AAPL" {:name "Apple Inc."}]
                                        ["e/company-name" e/company-name "AAPL" "Apple Inc."]
                                        ["e/company-metadata" e/company-metadata "AAPL" {:name "Apple Inc."}]]]
      (testing (str fn-name " rejects blank and nil input with ::schema/invalid-args")
        (doseq [bad ["" nil]]
          (let [ex (try (f bad) nil (catch clojure.lang.ExceptionInfo ex ex))]
            (is (= ::schema/invalid-args (:type (ex-data ex))) (pr-str bad)))))
      (testing (str fn-name " passes valid input through")
        (is (= expected (f valid)))))))

;;; ---------------------------------------------------------------------------
;;; Aliases and options-map calls
;;; ---------------------------------------------------------------------------

(deftest long-name-aliases-test
  (testing "long-name aliases are the same functions"
    (is (identical? e/income e/income-statement))
    (is (identical? e/balance e/balance-sheet))
    (is (identical? e/cashflow e/cash-flow))
    (is (identical? e/search e/search-companies))))

(deftest opts-map-passing-test
  (testing "a single options map works in place of keyword args (Clojure 1.11+)"
    (with-redefs [edgar.filings/get-filings
                  (fn [_ & {:keys [form limit]}] [{:form form :limit limit}])]
      (is (= [{:form "10-K" :limit 3}]
             (e/filings "AAPL" {:form "10-K" :limit 3}))))))

(deftest docstrings-present-test
  (testing "every public edgar.api var has a docstring"
    (is (empty? (->> (ns-publics 'edgar.api)
                     (remove (fn [[_ v]] (not (str/blank? (:doc (meta v))))))
                     (map key))))))

(deftest filing-map-validation-test
  (testing "filing-content functions reject maps without :cik/:accessionNumber"
    (doseq [[fn-name f] [["e/html" e/html] ["e/text" e/text] ["e/items" e/items]
                         ["e/obj" e/obj] ["e/filing-index" e/filing-index]
                         ["e/exhibits" e/exhibits] ["e/xbrl-docs" e/xbrl-docs]]]
      (let [ex (try (f {:form "10-K"}) nil (catch clojure.lang.ExceptionInfo ex ex))]
        (is (= ::schema/invalid-args (:type (ex-data ex))) fn-name))))
  (testing "index functions validate dates and quarters"
    (doseq [thunk [#(e/daily-filings "2026/10/13")
                   #(e/quarterly-index 2025 5)
                   #(e/quarterly-index 1990 1)
                   #(e/latest-effective-filing "AAPL" :form "")]]
      (let [ex (try (thunk) nil (catch clojure.lang.ExceptionInfo ex ex))]
        (is (= ::schema/invalid-args (:type (ex-data ex))))))))

(deftest boundary-validation-test
  (testing "invalid arguments fail at the API boundary, before any request"
    (doseq [thunk [#(e/init! "")
                   #(e/filings "AAPL" :form "10-K" :start-date "not-a-date")
                   #(e/facts "AAPL" :concept 42)
                   #(e/filing-by-accession "not-an-accession")]]
      (let [ex (try (thunk) nil (catch clojure.lang.ExceptionInfo ex ex))]
        (is (= ::schema/invalid-args (:type (ex-data ex))))))))
