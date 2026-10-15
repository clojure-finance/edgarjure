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
