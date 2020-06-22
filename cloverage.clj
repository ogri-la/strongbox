(ns strongbox.cloverage
  (:require
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
      (apply require (map symbol ns-list))
      {:errors (reduce + ((juxt :error :fail)
                          (apply test/run-tests ns-list)))})))