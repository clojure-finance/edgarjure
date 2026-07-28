(ns edgar.fsds-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.fsds :as fsds]
            [babashka.fs :as fs]
            [tech.v3.dataset :as ds])
  (:import [java.util.zip ZipOutputStream ZipEntry]
           [java.io FileOutputStream]))

(deftest quarter-url-test
  (testing "builds the DERA financial-statement-data-sets URL"
    (is (= "https://www.sec.gov/files/dera/data/financial-statement-data-sets/2024q1.zip"
           (fsds/quarter-url 2024 1)))
    (is (= "https://www.sec.gov/files/dera/data/financial-statement-data-sets/2019q4.zip"
           (fsds/quarter-url 2019 4)))))

(defn- write-fake-fsds-zip! [path]
  (with-open [zos (ZipOutputStream. (FileOutputStream. (str path)))]
    (.putNextEntry zos (ZipEntry. "sub.txt"))
    (.write zos (.getBytes (str "adsh\tcik\tname\tform\tperiod\n"
                                "0000320193-24-000006\t320193\tAPPLE INC\t10-K\t20230930\n"
                                "0000789019-24-000012\t789019\tMICROSOFT CORP\t10-K\t20230630\n")
                           "UTF-8"))
    (.closeEntry zos)
    (.putNextEntry zos (ZipEntry. "pre.txt"))
    (.write zos (.getBytes (str "adsh\treport\tline\tstmt\ttag\n"
                                "0000320193-24-000006\t2\t1\tIS\tRevenueFromContractWithCustomerExcludingAssessedTax\n"
                                "0000320193-24-000006\t2\t9\tIS\tNetIncomeLoss\n")
                           "UTF-8"))
    (.closeEntry zos)))

(deftest load-table-test
  (let [tmp-dir (fs/create-temp-dir)
        zip-path (fs/path tmp-dir "2024q1.zip")]
    (try
      (write-fake-fsds-zip! zip-path)
      (testing "loads sub.txt as a keyword-columned dataset"
        (let [sub (fsds/load-table zip-path :sub)]
          (is (= 2 (ds/row-count sub)))
          (is (= #{:adsh :cik :name :form :period} (set (ds/column-names sub))))
          (is (= "APPLE INC" (:name (first (ds/rows sub)))))))
      (testing "loads pre.txt (statement placement)"
        (let [pre (fsds/load-table zip-path :pre)]
          (is (= 2 (ds/row-count pre)))
          (is (every? #(= "IS" %) (ds/column pre :stmt)))))
      (testing "unknown table keyword throws"
        (is (thrown? clojure.lang.ExceptionInfo (fsds/load-table zip-path :bogus))))
      (testing "missing entry throws with informative ex-data"
        (let [empty-zip (fs/path tmp-dir "empty.zip")]
          (with-open [zos (ZipOutputStream. (FileOutputStream. (str empty-zip)))]
            (.putNextEntry zos (ZipEntry. "readme.htm"))
            (.write zos (.getBytes "x" "UTF-8"))
            (.closeEntry zos))
          (is (thrown? clojure.lang.ExceptionInfo (fsds/load-table empty-zip :num)))))
      (finally
        (fs/delete-tree tmp-dir)))))

;;; ---------------------------------------------------------------------------
;;; Placement and extension-tag queries — synthetic tables modeled on the
;;; real 2026q1 layout (AMZN FY2025 10-K: FulfillmentExpense and
;;; TechnologyAndInfrastructureExpense are extension tags whose :version is
;;; the filing's own adsh; D&A appears on the cash flow statement only)
;;; ---------------------------------------------------------------------------

(def ^:private sub-fixture
  (ds/->dataset [{:adsh "0001018724-26-000004" :cik 1018724 :name "AMAZON COM INC"
                  :form "10-K" :period 20251231 :filed 20260206}
                 {:adsh "0001018724-25-000100" :cik 1018724 :name "AMAZON COM INC"
                  :form "10-Q" :period 20250930 :filed 20251030}
                 {:adsh "0000320193-26-000001" :cik 320193 :name "APPLE INC"
                  :form "10-K" :period 20250927 :filed 20251031}]))

(def ^:private pre-fixture
  (ds/->dataset [{:adsh "0001018724-26-000004" :report 2 :line 9 :stmt "IS"
                  :tag "CostOfGoodsAndServicesSold" :version "us-gaap/2025" :plabel "Cost of sales"}
                 {:adsh "0001018724-26-000004" :report 2 :line 10 :stmt "IS"
                  :tag "FulfillmentExpense" :version "0001018724-26-000004" :plabel "Fulfillment"}
                 {:adsh "0001018724-26-000004" :report 4 :line 3 :stmt "CF"
                  :tag "DepreciationDepletionAndAmortization" :version "us-gaap/2025"
                  :plabel "Depreciation and amortization"}
                 {:adsh "0000320193-26-000001" :report 2 :line 1 :stmt "IS"
                  :tag "RevenueFromContractWithCustomerExcludingAssessedTax"
                  :version "us-gaap/2025" :plabel "Net sales"}]))

(def ^:private num-fixture
  (ds/->dataset [{:adsh "0001018724-26-000004" :tag "FulfillmentExpense"
                  :version "0001018724-26-000004" :ddate 20251231 :qtrs 4
                  :uom "USD" :value 1.09074E11}
                 {:adsh "0001018724-26-000004" :tag "FulfillmentExpense"
                  :version "0001018724-26-000004" :ddate 20241231 :qtrs 4
                  :uom "USD" :value 9.8505E10}
                 {:adsh "0001018724-26-000004" :tag "Assets"
                  :version "us-gaap/2025" :ddate 20251231 :qtrs 0
                  :uom "USD" :value 6.0E11}]))

(deftest find-submissions-test
  (testing "filters by cik and form, most recent first"
    (let [r (fsds/find-submissions sub-fixture :cik "1018724" :form "10-K")]
      (is (= 1 (ds/row-count r)))
      (is (= "0001018724-26-000004" (first (ds/column r :adsh))))))
  (testing "cik accepts padded strings (FSDS stores unpadded numbers)"
    (let [r (fsds/find-submissions sub-fixture :cik "0000320193")]
      (is (= 1 (ds/row-count r)))))
  (testing "cik-only filter returns all of a company's submissions"
    (is (= 2 (ds/row-count (fsds/find-submissions sub-fixture :cik 1018724))))))

(deftest presentation-test
  (testing "restricts to one adsh and sorts by report/line"
    (let [r (fsds/presentation pre-fixture "0001018724-26-000004")]
      (is (= 3 (ds/row-count r)))
      (is (= ["CostOfGoodsAndServicesSold" "FulfillmentExpense"
              "DepreciationDepletionAndAmortization"]
             (vec (ds/column r :tag))))))
  (testing ":stmt option narrows to one statement"
    (let [r (fsds/presentation pre-fixture "0001018724-26-000004" :stmt "IS")]
      (is (= 2 (ds/row-count r)))
      (is (every? #(= "IS" %) (ds/column r :stmt)))))
  (testing "extension tags are recognizable by their filing-adsh :version"
    (let [r (fsds/presentation pre-fixture "0001018724-26-000004" :stmt "IS")
          fulfillment (first (filter #(= "FulfillmentExpense" (:tag %))
                                     (ds/rows r {:nil-missing? true})))]
      (is (= "0001018724-26-000004" (:version fulfillment))))))

(deftest statement-placement-test
  (let [p (fsds/statement-placement pre-fixture "0001018724-26-000004")]
    (testing "maps each tag to the set of statements it appears on"
      (is (= #{"IS"} (get p "FulfillmentExpense")))
      (is (= #{"CF"} (get p "DepreciationDepletionAndAmortization"))))
    (testing "other filings' rows are excluded"
      (is (not (contains? p "RevenueFromContractWithCustomerExcludingAssessedTax"))))))

(deftest facts-for-test
  (testing "restricts to one adsh; :qtrs and :tag narrow further"
    (is (= 3 (ds/row-count (fsds/facts-for num-fixture "0001018724-26-000004"))))
    (is (= 2 (ds/row-count (fsds/facts-for num-fixture "0001018724-26-000004" :qtrs 4))))
    (let [r (fsds/facts-for num-fixture "0001018724-26-000004"
                            :qtrs 4 :tag "FulfillmentExpense")]
      (is (= 2 (ds/row-count r)))
      (is (= #{1.09074E11 9.8505E10} (set (ds/column r :value)))))))
