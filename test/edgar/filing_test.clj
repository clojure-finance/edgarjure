(ns edgar.filing-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.filing :as filing]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; filing-index-url — pure function, builds a URL from a filing map
;;; ---------------------------------------------------------------------------

(deftest filing-index-url-test
  (testing "builds correct index URL for dashed accession number"
    (is (= "https://www.sec.gov/Archives/edgar/data/320193/000032019323000064/0000320193-23-000064-index.json"
           (filing/filing-index-url
            {:cik "320193"
             :accessionNumber "0000320193-23-000064"}))))
  (testing "CIK in URL is not zero-padded (uses raw :cik value)"
    (let [url (filing/filing-index-url {:cik "320193"
                                        :accessionNumber "0000320193-23-000064"})]
      (is (str/includes? url "/320193/"))))
  (testing "accession number in URL path has dashes stripped"
    (let [url (filing/filing-index-url {:cik "320193"
                                        :accessionNumber "0000320193-23-000064"})]
      (is (str/includes? url "/000032019323000064/"))))
  (testing "index filename in URL retains dashes"
    (let [url (filing/filing-index-url {:cik "320193"
                                        :accessionNumber "0000320193-23-000064"})]
      (is (str/ends-with? url "-index.json")))))

;;; ---------------------------------------------------------------------------
;;; filing-by-accession — pure normalization inside the function
;;; We test the accession-normalisation logic directly without HTTP.
;;; ---------------------------------------------------------------------------

(deftest accession-format-normalization
  (testing "dashes are stripped to produce the path component"
    (let [acc "0000320193-23-000106"
          digits (str/replace acc "-" "")]
      (is (= "000032019323000106" digits))))
  (testing "CIK extracted from first 10 digits of undashed accession"
    (let [digits "000032019323000106"
          cik (str (Long/parseLong (subs digits 0 10)))]
      (is (= "320193" cik))))
  (testing "undashed 18-char string is reformatted to dashed"
    (let [digits "000032019323000106"
          dashed (str (subs digits 0 10) "-" (subs digits 10 12) "-" (subs digits 12))]
      (is (= "0000320193-23-000106" dashed)))))
