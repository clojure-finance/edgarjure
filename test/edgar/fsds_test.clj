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
