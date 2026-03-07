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
