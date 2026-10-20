(ns edgar.forms.xml-test
  (:require [clojure.test :refer [deftest is testing]]
            [edgar.forms.xml :as x]))

(deftest parse-str-test
  (testing "parses a plain document"
    (is (= {:tag :r :attrs nil :content [{:tag :a :attrs nil :content ["1"]}]}
           (x/parse-str "<r><a>1</a></r>"))))
  (testing "external entities are not resolved (XXE)"
    (let [doc (str "<?xml version=\"1.0\"?>"
                   "<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/hostname\">]>"
                   "<r><a>&x;</a></r>")]
      (is (nil? (x/tag-text (x/parse-str doc) :a))))))

(deftest tag-matching-test
  (let [doc (x/parse-str "<r><ns1:infoTable><v>1</v></ns1:infoTable><infoTable><v>2</v></infoTable></r>")]
    (testing "find-tags ignores namespace prefixes"
      (is (= 2 (count (x/find-tags doc :infoTable)))))
    (testing "find-tag returns the first match in document order"
      (is (= "1" (x/tag-text (x/find-tag doc :infoTable) :v))))))

(deftest texts-test
  (let [doc (x/parse-str "<r><p><t>IA</t><t>PN</t></p><p><t> OO </t><t></t></p></r>")]
    (testing "every matching element's text, in document order, blanks dropped"
      (is (= ["IA" "PN" "OO"] (x/texts doc :t))))
    (testing "[] when the tag is absent"
      (is (= [] (x/texts doc :missing))))))

(deftest parse-number-safe-test
  (testing "parse-long-safe"
    (is (= 1234567 (x/parse-long-safe "1,234,567")))
    (is (= 5 (x/parse-long-safe " 5 ")))
    (is (nil? (x/parse-long-safe "1.5")))
    (is (nil? (x/parse-long-safe "")))
    (is (nil? (x/parse-long-safe nil)))
    (is (nil? (x/parse-long-safe "n/a"))))
  (testing "parse-double-safe"
    (is (= 1234.5 (x/parse-double-safe "1,234.5")))
    (is (= 7.0 (x/parse-double-safe "7")))
    (is (nil? (x/parse-double-safe "  ")))
    (is (nil? (x/parse-double-safe nil)))
    (is (nil? (x/parse-double-safe "abc")))))

(deftest xml-documents-test
  (is (= ["primary_doc.xml" "56757.xml" "ownership.XML"]
         (mapv :name (x/xml-documents
                      [{:name "primary_doc.xml" :type "13F-HR"}
                       {:name "56757.xml" :type "INFORMATION TABLE"}
                       {:name "aapl-20240115_htm.xml" :type "EX-101.INS"}
                       {:name "aapl-20240115_cal.xml" :type "EX-101.CAL"}
                       {:name "instance.xml" :type "XBRL INSTANCE"}
                       {:name "ownership.XML" :type "4"}
                       {:name "primary.htm" :type "4"}
                       {:name nil :type nil}])))))
