(ns edgar.test-runner
  (:require [clojure.test :as t]))

(def test-namespaces
  "Single source of truth: each namespace here is required AND run, so a new
   test file only needs to be added in one place."
  '[edgar.core-test
    edgar.company-test
    edgar.filings-test
    edgar.filing-test
    edgar.financials-test
    edgar.reclass-test
    edgar.extract-test
    edgar.tables-test
    edgar.forms.ownership-test
    edgar.forms.form13f-test
    edgar.forms.schedule13-test
    edgar.xbrl-test
    edgar.schema-test
    edgar.dataset-test
    edgar.download-test
    edgar.validation-test
    edgar.fsds-test
    edgar.api-docstring-test])

(defn -main [& _]
  (doseq [ns-sym test-namespaces]
    (require ns-sym))
  (let [result (apply t/run-tests test-namespaces)]
    (System/exit (if (= 0 (:fail result) (:error result)) 0 1))))
