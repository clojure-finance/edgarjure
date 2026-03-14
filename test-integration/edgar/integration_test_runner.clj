(ns edgar.integration-test-runner
  (:require [clojure.test :as t]
            edgar.integration-test))

(defn -main [& _]
  (let [result (t/run-tests 'edgar.integration-test)]
    (System/exit (if (= 0 (:fail result) (:error result)) 0 1))))
