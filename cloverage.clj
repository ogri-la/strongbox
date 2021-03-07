(ns strongbox.cloverage
  (:require
   [taoensso.timbre :as timbre]
   [strongbox.main :as main]
   [clojure.test :as test]
   [cloverage.coverage :as c]))

(comment
  "Cloverage hook. runs all of the application tests with profiling and spec checks enabled.
  These checks are disabled by default and only available if passed in from the REPL on app start.")

(defmethod c/runner-fn :strongbox
  [_]
  (fn [ns-list]
    (with-redefs [main/profile? true
                  main/spec? true]
      (timbre/with-merged-config {:level :debug, :testing? true
                                  ;; ensure we're not writing logs to files
                                  :appenders {:spit nil}}
        (apply require (map symbol ns-list))
        {:errors (reduce + ((juxt :error :fail)
                            (apply test/run-tests ns-list)))}))))
