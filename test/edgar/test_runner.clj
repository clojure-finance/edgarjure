(ns edgar.test-runner
  (:require [clojure.test :as t]
            edgar.core-test
            edgar.filings-test
            edgar.extract-test
            edgar.xbrl-test))

(defn -main [& _]
  (let [result (t/run-tests 'edgar.core-test
                            'edgar.filings-test
                            'edgar.extract-test
                            'edgar.xbrl-test)]
    (System/exit (if (= 0 (:fail result) (:error result)) 0 1))))
