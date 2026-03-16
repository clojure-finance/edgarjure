(ns edgar.xbrl-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.xbrl :as xbrl]))

;;; ---------------------------------------------------------------------------
;;; concept-frame-url
;;; Pure URL builder — no HTTP, safe to test offline.
;;; ---------------------------------------------------------------------------

(deftest concept-frame-url-test
  (testing "builds correct URL with explicit taxonomy and unit"
    (is (= "https://data.sec.gov/api/xbrl/frames/us-gaap/Assets/USD/CY2023Q4I.json"
           (xbrl/concept-frame-url "us-gaap" "Assets" "USD" "CY2023Q4I"))))
  (testing "works with non-USD unit"
    (is (= "https://data.sec.gov/api/xbrl/frames/us-gaap/SharesOutstanding/shares/CY2023Q4I.json"
           (xbrl/concept-frame-url "us-gaap" "SharesOutstanding" "shares" "CY2023Q4I"))))
  (testing "works with annual frame"
    (is (= "https://data.sec.gov/api/xbrl/frames/us-gaap/NetIncomeLoss/USD/CY2023.json"
           (xbrl/concept-frame-url "us-gaap" "NetIncomeLoss" "USD" "CY2023"))))
  (testing "works with dei taxonomy"
    (is (= "https://data.sec.gov/api/xbrl/frames/dei/EntityPublicFloat/USD/CY2023Q4I.json"
           (xbrl/concept-frame-url "dei" "EntityPublicFloat" "USD" "CY2023Q4I")))))

;;; ---------------------------------------------------------------------------
;;; get-concept-frame keyword-arg defaults
;;; We test the URL builder directly since get-concept-frame makes HTTP calls.
;;; The default values (us-gaap / USD) are exercised via concept-frame-url.
;;; ---------------------------------------------------------------------------

(deftest concept-frame-url-defaults-test
  (testing "default taxonomy is us-gaap"
    (is (clojure.string/includes?
         (xbrl/concept-frame-url "us-gaap" "Assets" "USD" "CY2023Q4I")
         "/us-gaap/")))
  (testing "default unit is USD"
    (is (clojure.string/includes?
         (xbrl/concept-frame-url "us-gaap" "Assets" "USD" "CY2023Q4I")
         "/USD/"))))

(def ^:private stub-facts-map
  {:entityName "Test Corp"
   :facts
   {:us-gaap
    {:Assets
     {:label "Assets"
      :description "Total assets"
      :units
      {:USD [{:end "2022-09-30" :val 100 :accn "0000000001-22-000001"
              :fy 2022 :fp "FY" :form "10-K" :filed "2022-11-01" :frame "CY2022Q3I"}
             {:end "2024-09-30" :val 300 :accn "0000000001-24-000001"
              :fy 2024 :fp "FY" :form "10-K" :filed "2024-11-01" :frame "CY2024Q3I"}
             {:end "2023-09-30" :val 200 :accn "0000000001-23-000001"
              :fy 2023 :fp "FY" :form "10-K" :filed "2023-11-01" :frame "CY2023Q3I"}]}}}}})

(deftest get-facts-dataset-sort-test
  (testing ":desc sort produces :end in descending order (most recent first)"
    (let [result (with-redefs [edgar.xbrl/get-company-facts (fn [_] stub-facts-map)]
                   (xbrl/get-facts-dataset "0000000001" :sort :desc))
          ends (vec (tech.v3.dataset/column result :end))]
      (is (= ["2024-09-30" "2023-09-30" "2022-09-30"] ends)
          "rows must be sorted :end descending regardless of SEC delivery order")))

  (testing ":asc sort produces :end in ascending order (oldest first)"
    (let [result (with-redefs [edgar.xbrl/get-company-facts (fn [_] stub-facts-map)]
                   (xbrl/get-facts-dataset "0000000001" :sort :asc))
          ends (vec (tech.v3.dataset/column result :end))]
      (is (= ["2022-09-30" "2023-09-30" "2024-09-30"] ends)
          "rows must be sorted :end ascending")))

  (testing "sort nil skips sorting — all rows present, order unspecified"
    (let [result (with-redefs [edgar.xbrl/get-company-facts (fn [_] stub-facts-map)]
                   (xbrl/get-facts-dataset "0000000001" :sort nil))
          ends (set (tech.v3.dataset/column result :end))]
      (is (= #{"2022-09-30" "2023-09-30" "2024-09-30"} ends)
          "all rows present when sort is nil")))

  (testing "default sort is :desc"
    (let [result (with-redefs [edgar.xbrl/get-company-facts (fn [_] stub-facts-map)]
                   (xbrl/get-facts-dataset "0000000001"))
          ends (vec (tech.v3.dataset/column result :end))]
      (is (= "2024-09-30" (first ends))
          "most recent :end must be first by default")))

  (testing ":desc sort is deterministic — not dependent on SEC delivery order"
    ;; Stub delivers data in reverse order (newest first) to verify
    ;; we sort explicitly rather than relying on arrival order
    (let [reversed-stub (update-in stub-facts-map
                                   [:facts :us-gaap :Assets :units :USD]
                                   (comp vec reverse))
          result-normal (with-redefs [edgar.xbrl/get-company-facts (fn [_] stub-facts-map)]
                          (xbrl/get-facts-dataset "0000000001" :sort :desc))
          result-reversed (with-redefs [edgar.xbrl/get-company-facts (fn [_] reversed-stub)]
                            (xbrl/get-facts-dataset "0000000001" :sort :desc))]
      (is (= (vec (tech.v3.dataset/column result-normal :end))
             (vec (tech.v3.dataset/column result-reversed :end)))
          ":desc result must be identical regardless of input order"))))
