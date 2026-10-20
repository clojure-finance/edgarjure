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
;;; get-concept-frame keyword-arg defaults — core/edgar-get is stubbed to
;;; capture the URL get-concept-frame actually requests.
;;; ---------------------------------------------------------------------------

(deftest concept-frame-url-defaults-test
  (let [url (atom nil)]
    (with-redefs [core/edgar-get (fn [u & _] (reset! url u) {:data []})]
      (testing "get-concept-frame defaults to taxonomy us-gaap and unit USD"
        (xbrl/get-concept-frame "Assets" "CY2023Q4I")
        (is (= "https://data.sec.gov/api/xbrl/frames/us-gaap/Assets/USD/CY2023Q4I.json" @url)))
      (testing "explicit :taxonomy and :unit override the defaults"
        (xbrl/get-concept-frame "EntityCommonStockSharesOutstanding" "CY2023Q4I"
                                :taxonomy "dei" :unit "shares")
        (is (= "https://data.sec.gov/api/xbrl/frames/dei/EntityCommonStockSharesOutstanding/shares/CY2023Q4I.json"
               @url))))))

(deftest get-concept-frame-empty-test
  ;; the real frames shape: :data is a list of maps (no :columns key)
  (let [base {:taxonomy "us-gaap" :tag "Assets" :ccp "CY2023Q4I" :uom "USD" :pts 0}]
    (testing "empty :data — returns empty dataset with canonical columns"
      (with-redefs [core/edgar-get (fn [_] (assoc base :data []))]
        (let [result (xbrl/get-concept-frame "Assets" "CY2023Q4I")]
          (is (= 0 (ds/row-count result)))
          (is (= #{"accn" "cik" "entityName" "loc" "end" "val"}
                 (set (map name (ds/column-names result))))))))
    (testing "nil / missing :data — returns empty dataset"
      (with-redefs [core/edgar-get (fn [_] (assoc base :data nil))]
        (is (= 0 (ds/row-count (xbrl/get-concept-frame "Assets" "CY2023Q4I")))))
      (with-redefs [core/edgar-get (fn [_] base)]
        (is (= 0 (ds/row-count (xbrl/get-concept-frame "Assets" "CY2023Q4I"))))))
    (testing "dataset-name is set to concept/frame"
      (with-redefs [core/edgar-get (fn [_] (assoc base :data [{:accn "a" :cik 1 :entityName "X"
                                                               :loc "US-CA" :end "2023-12-31" :val 5}]))]
        (is (= "Assets/CY2023Q4I" (ds/dataset-name (xbrl/get-concept-frame "Assets" "CY2023Q4I"))))))))

(deftest get-concept-frame-real-format-test
  ;; The REAL SEC frames response: no :columns key, :data is a seq of MAPS
  ;; with a numeric :cik. The old vector/zipmap parser produced garbage rows
  ;; (every cell a misaligned MapEntry) against this format.
  (let [real-resp {:taxonomy "us-gaap"
                   :tag "Assets"
                   :ccp "CY2023Q4I"
                   :uom "USD"
                   :pts 2
                   :data [{:accn "0001104659-23-128321" :cik 1750
                           :entityName "AAR CORP" :loc "US-IL"
                           :end "2023-11-30" :val 1965600000}
                          {:accn "0000320193-24-000006" :cik 320193
                           :entityName "Apple Inc." :loc "US-CA"
                           :end "2023-12-30" :val 353514000000}]}]
    (with-redefs [core/edgar-get (fn [_] real-resp)]
      (let [result (xbrl/get-concept-frame "Assets" "CY2023Q4I")
            rows (vec (ds/rows result {:nil-missing? true}))]
        (testing "rows parse into properly keyed columns (not MapEntries)"
          (is (= 2 (ds/row-count result)))
          (is (every? number? (map :val rows)))
          (is (every? string? (map :entityName rows))))
        (testing ":cik is zero-padded to the 10-digit string used library-wide"
          (is (= #{"0000001750" "0000320193"} (set (map :cik rows)))))
        (testing "sorted by :val descending"
          (is (= "Apple Inc." (:entityName (first rows))))
          (is (= 353514000000 (:val (first rows)))))))))

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

(deftest facts->dataset-units-and-column-order-test
  ;; regression (AAPL live): unit keys parse as keywords, and "USD/shares"
  ;; became the namespaced keyword :USD/shares whose (name ...) is "shares" -
  ;; every EPS row carried :unit "shares"
  (let [facts {:entityName "Test Corp"
               :facts
               {:us-gaap
                {:EarningsPerShareDiluted
                 {:label "EPS" :description "Diluted EPS"
                  :units {(keyword "USD/shares")
                          [{:start "2023-01-01" :end "2023-12-31" :val 6.1 :accn "a"
                            :fy 2023 :fp "FY" :form "10-K" :filed "2024-02-01"
                            :frame "CY2023"}]}}
                 :Assets
                 {:label "Assets" :description "Total assets"
                  :units {:USD [{:end "2023-12-31" :val 100 :accn "a" :fy 2023
                                 :fp "FY" :form "10-K" :filed "2024-02-01"}]}}}}}
        result (xbrl/facts->dataset facts)
        units (into {} (map (juxt :concept :unit)) (ds/rows result {:nil-missing? true}))]
    (testing "the full unit string survives"
      (is (= {"EarningsPerShareDiluted" "USD/shares" "Assets" "USD"} units)))
    (testing "columns come in the documented order"
      (is (= [:taxonomy :concept :label :description :unit :start :end :val
              :accn :fy :fp :form :filed :frame]
             (vec (ds/column-names result)))))))
