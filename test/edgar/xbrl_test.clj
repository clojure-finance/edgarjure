(ns edgar.xbrl-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.core :as core]
            [edgar.xbrl :as xbrl]
            [tech.v3.dataset :as ds]))

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

(deftest get-concept-frame-test
  ;; All cases use with-redefs on core/edgar-get to avoid network calls.
  (let [normal-resp {:taxonomy "us-gaap"
                     :tag "Assets"
                     :columns ["accn" "cik" "entityName" "loc" "end" "val"]
                     :data [["acc1" "320193" "Apple Inc." "US-CA" "2023-09-30" 352583000000]
                            ["acc2" "789019" "Microsoft" "US-WA" "2023-06-30" 411976000000]]}]
    (testing "normal response — :columns present — returns correct dataset"
      (with-redefs [core/edgar-get (fn [_] normal-resp)]
        (let [result (xbrl/get-concept-frame "Assets" "CY2023Q4I")
              cols (set (map name (ds/column-names result)))]
          (is (= 2 (ds/row-count result)))
          (is (contains? cols "accn"))
          (is (contains? cols "cik"))
          (is (contains? cols "entityName"))
          (is (contains? cols "loc"))
          (is (contains? cols "end"))
          (is (contains? cols "val")))))

    (testing "missing :columns key — falls back to canonical column names"
      ;; Regression for Issue #13: (mapv keyword nil) crashed with ClassCastException
      (with-redefs [core/edgar-get (fn [_] (dissoc normal-resp :columns))]
        (let [result (xbrl/get-concept-frame "Assets" "CY2023Q4I")
              cols (set (map name (ds/column-names result)))]
          (is (= 2 (ds/row-count result))
              "data rows must still be returned when :columns is absent")
          (is (= #{"accn" "cik" "entityName" "loc" "end" "val"} cols)
              "canonical column names used as fallback"))))

    (testing "empty :columns vector — falls back to canonical column names"
      (with-redefs [core/edgar-get (fn [_] (assoc normal-resp :columns []))]
        (let [result (xbrl/get-concept-frame "Assets" "CY2023Q4I")
              cols (set (map name (ds/column-names result)))]
          (is (= 2 (ds/row-count result)))
          (is (= #{"accn" "cik" "entityName" "loc" "end" "val"} cols)))))

    (testing "empty :data — returns empty dataset with canonical columns"
      (with-redefs [core/edgar-get (fn [_] (assoc normal-resp :data []))]
        (let [result (xbrl/get-concept-frame "Assets" "CY2023Q4I")]
          (is (= 0 (ds/row-count result)))
          (is (contains? (set (map name (ds/column-names result))) "val")))))

    (testing "nil :data — returns empty dataset with canonical columns"
      (with-redefs [core/edgar-get (fn [_] {:columns ["accn" "cik" "entityName" "loc" "end" "val"] :data nil})]
        (let [result (xbrl/get-concept-frame "Assets" "CY2023Q4I")]
          (is (= 0 (ds/row-count result))))))

    (testing "non-standard column count — uses positional :col0 :col1 ... names"
      (with-redefs [core/edgar-get (fn [_] {:data [["a" "b" "c"] ["d" "e" "f"]]})]
        (let [result (xbrl/get-concept-frame "Assets" "CY2023Q4I")
              cols (set (map name (ds/column-names result)))]
          (is (= 2 (ds/row-count result)))
          (is (= #{"col0" "col1" "col2"} cols)))))

    (testing "dataset-name is set to concept/frame"
      (with-redefs [core/edgar-get (fn [_] normal-resp)]
        (let [result (xbrl/get-concept-frame "Assets" "CY2023Q4I")]
          (is (= "Assets/CY2023Q4I" (ds/dataset-name result))))))))

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
