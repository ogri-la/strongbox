(ns strongbox.cloverage
  (:require
   [strongbox
    [main :as main]
    [http :as http]
    [joblib :as joblib]
    [logging :as logging]
    [core :as core]
    [catalogue :as catalogue]]
   [clojure.test :as test]
   [cloverage.coverage :as c]))

(comment
  "Cloverage hook. runs all of the application tests with spec checks enabled.
  These checks are disabled by default and only available if passed in from the REPL on app start.")

(defmethod c/runner-fn :strongbox
  [_]
  (fn [ns-list]
    (with-redefs [core/testing? true
                  main/spec? true
                  http/*default-pause* 1 ;; ms
                  http/*default-attempts* 1
                  joblib/tick-delay joblib/tick
                  catalogue/host-disabled? (constantly false)]
      (core/reset-logging!)
      (apply require (map symbol ns-list))
      {:errors (reduce + ((juxt :error :fail)
                          (apply test/run-tests ns-list)))})))
