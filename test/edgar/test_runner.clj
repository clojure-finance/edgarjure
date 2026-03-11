(ns edgar.test-runner
  (:require [clojure.test :as t]
            edgar.core-test
            edgar.company-test
            edgar.filings-test
            edgar.filing-test
            edgar.financials-test
            edgar.extract-test
            edgar.tables-test
            edgar.forms.form4-test
            edgar.forms.form13f-test
            edgar.xbrl-test
            edgar.schema-test
            edgar.dataset-test))

(defn -main [& _]
  (let [result (t/run-tests 'edgar.core-test
                            'edgar.company-test
                            'edgar.filings-test
                            'edgar.filing-test
                            'edgar.financials-test
                            'edgar.extract-test
                            'edgar.tables-test
                            'edgar.forms.form4-test
                            'edgar.forms.form13f-test
                            'edgar.xbrl-test
                            'edgar.schema-test
                            'edgar.dataset-test)]
    (System/exit (if (= 0 (:fail result) (:error result)) 0 1))))
