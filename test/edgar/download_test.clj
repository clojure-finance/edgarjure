(ns edgar.download-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.download :as download]
            [edgar.core :as core]
            [edgar.filing :as filing]
            [edgar.filings :as filings]))

;;; ---------------------------------------------------------------------------
;;; Result envelope shape — the core of issue 6
;;; ---------------------------------------------------------------------------

(def ^:private mock-filing
  {:cik "0000320193"
   :accessionNumber "0000320193-24-000001"
   :form "10-K"
   :primaryDocument "report.htm"})

(deftest download-filings-primary-doc-shape-test
  (testing "primary-doc download returns a single map with :path, not a vector"
    (let [result (with-redefs [filings/get-filings (fn [& _] [mock-filing])
                               filing/filing-save! (fn [_ _] "/data/10-K/320193/acc/report.htm")]
                   (download/download-filings! "AAPL" "/data" :form "10-K"))]
      (is (= 1 (count result)) "one result per filing")
      (let [r (first result)]
        (is (map? r) "result is a map, not a vector")
        (is (= :ok (:status r)))
        (is (= "/data/10-K/320193/acc/report.htm" (:path r)))
        (is (not (contains? r :paths)) ":path key used for primary-doc download"))))

  (testing "download-all? returns a single map with :paths (vector), not a vector of maps"
    (let [saved ["/data/10-K/320193/acc/report.htm"
                 "/data/10-K/320193/acc/ex21.htm"]
          result (with-redefs [filings/get-filings (fn [& _] [mock-filing])
                               filing/filing-save-all! (fn [_ _] saved)]
                   (download/download-filings! "AAPL" "/data" :form "10-K" :download-all? true))]
      (is (= 1 (count result)) "one result per filing even with download-all?")
      (let [r (first result)]
        (is (map? r) "result is a map, not a vector of maps")
        (is (= :ok (:status r)))
        (is (= saved (:paths r)) ":paths key holds the list of saved file paths")
        (is (not (contains? r :path)) ":path key not used for download-all? download"))))

  (testing "every result is a plain map regardless of download-all? flag"
    (let [result-single (with-redefs [filings/get-filings (fn [& _] [mock-filing mock-filing])
                                      filing/filing-save! (fn [_ _] "/a/file.htm")]
                          (download/download-filings! "AAPL" "/data" :form "10-K"))
          result-all (with-redefs [filings/get-filings (fn [& _] [mock-filing mock-filing])
                                   filing/filing-save-all! (fn [_ _] ["/a/1.htm" "/a/2.htm"])]
                       (download/download-filings! "AAPL" "/data" :form "10-K" :download-all? true))]
      (is (every? map? result-single) "all results are maps (primary-doc mode)")
      (is (every? map? result-all) "all results are maps (download-all? mode)"))))

(deftest download-filings-skipped-shape-test
  (testing "skipped filing returns {:status :skipped :accession-number ...}"
    (let [result (with-redefs [filings/get-filings (fn [& _] [mock-filing])
                               edgar.filing/filing-index
                               (fn [_] {:files [{:sequence "1" :name "report.htm"
                                                 :type "10-K" :description "Annual Report"
                                                 :size "100"}]
                                        :formType "10-K"})
                               edgar.filing/primary-doc
                               (fn [_] {:sequence "1" :name "report.htm"})
                               babashka.fs/exists? (fn [_] true)]
                   (download/download-filings! "AAPL" "/data"
                                               :form "10-K"
                                               :skip-existing? true))]
      (is (= 1 (count result)))
      (let [r (first result)]
        (is (= :skipped (:status r)))
        (is (= "0000320193-24-000001" (:accession-number r)))))))

(deftest download-filings-error-shape-test
  (testing "exception during download produces {:status :error ...} envelope"
    (let [result (with-redefs [filings/get-filings (fn [& _] [mock-filing])
                               filing/filing-save!
                               (fn [_ _] (throw (ex-info "HTTP 404"
                                                         {:type ::core/http-error
                                                          :status 404})))]
                   (download/download-filings! "AAPL" "/data" :form "10-K"))]
      (is (= 1 (count result)))
      (let [r (first result)]
        (is (= :error (:status r)))
        (is (= "0000320193-24-000001" (:accession-number r)))
        (is (= "HTTP 404" (:message r)))))))

(deftest download-filings-multi-filing-shape-test
  (testing "multiple filings all return plain maps"
    (let [filings-seq [mock-filing
                       (assoc mock-filing :accessionNumber "0000320193-24-000002")]
          paths ["/data/10-K/320193/acc1/report.htm"
                 "/data/10-K/320193/acc2/report.htm"]
          call-count (atom 0)
          result (with-redefs [filings/get-filings (fn [& _] filings-seq)
                               filing/filing-save!
                               (fn [_ _]
                                 (nth paths (min @call-count (dec (count paths)))
                                      (do (swap! call-count inc) nil)))]
                   (download/download-filings! "AAPL" "/data" :form "10-K"))]
      (is (= 2 (count result)))
      (is (every? map? result))
      (is (every? #(= :ok (:status %)) result)))))

(deftest download-filings-nil-primary-doc-test
  (testing "filing with no primary document yields :skipped, not {:status :ok :path nil}"
    (let [result (with-redefs [filings/get-filings (fn [& _] [mock-filing])
                               filing/filing-save! (fn [_ _] nil)]
                   (download/download-filings! "AAPL" "/data" :form "10-K"))]
      (is (= 1 (count result)))
      (let [r (first result)]
        (is (= :skipped (:status r)))
        (is (= "0000320193-24-000001" (:accession-number r)))
        (is (= :no-primary-doc (:reason r)))
        (is (not (contains? r :path)) "no :path key on a skipped result"))))
  (testing "nil path does not produce {:status :ok :path nil}"
    (let [result (with-redefs [filings/get-filings (fn [& _] [mock-filing])
                               filing/filing-save! (fn [_ _] nil)]
                   (download/download-filings! "AAPL" "/data" :form "10-K"))
          r (first result)]
      (is (not= :ok (:status r))))))
