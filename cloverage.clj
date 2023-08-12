(ns strongbox.cloverage
  (:require
   [clojure.test :as test :refer [report with-test-out inc-report-counter testing-vars-str testing-contexts-str *testing-contexts* *stack-trace-depth*]]
   [clojure.stacktrace :as stack]
   [strongbox
    [main :as main]
    [constants :as constants]
    [utils :as utils]
    [http :as http]
    [joblib :as joblib]
    [logging :as logging]
    [core :as core]]
   [cloverage.coverage :as c]))

(comment
  "Cloverage hook. runs all of the application tests with spec checks enabled.
  These checks are disabled by default and only available if passed in from the REPL on app start.")

(def ^:dynamic *testing-problems* nil)

(defn report-testing-problems
  []
  (when-not (empty? (:fail @*testing-problems*))
    (println)
    (println "--- FAILURES ---")
    (doseq [f (:fail @*testing-problems*)]
      (println f)))
  (when-not (empty? (:error @*testing-problems*))
    (println)
    (println "--- ERRORS ---")
    (doseq [e (:error @*testing-problems*)]
      (println e))))

;; --- copied from clojure.test

(defmethod report :fail [m]
  (swap! *testing-problems* update-in [:fail] conj m)
  (with-test-out
    (inc-report-counter :fail)
    (println "\nFAIL in" (testing-vars-str m))
    (when (seq *testing-contexts*) (println (testing-contexts-str)))
    (when-let [message (:message m)] (println message))
    (println "expected:" (pr-str (:expected m)))
    (println "  actual:" (pr-str (:actual m)))))

(defmethod report :error [m]
  (swap! *testing-problems* update-in [:error] conj m)
  (with-test-out
   (inc-report-counter :error)
   (println "\nERROR in" (testing-vars-str m))
   (when (seq *testing-contexts*) (println (testing-contexts-str)))
   (when-let [message (:message m)] (println message))
   (println "expected:" (pr-str (:expected m)))
   (print "  actual: ")
   (let [actual (:actual m)]
     (if (instance? Throwable actual)
       (stack/print-cause-trace actual *stack-trace-depth*)
       (prn actual)))))

(defmethod c/runner-fn :strongbox
  [_]
  (fn [ns-list]
    (let [testing-problems (atom {:fail [] :error []})]
      (with-redefs [core/*testing?* true
                    main/*spec?* true
                    http/*default-pause* 1 ;; ms
                    http/*default-attempts* 1
                    ;;joblib/tick-delay joblib/*tick*
                    utils/folder-size-bytes (constantly 0)
                    constants/max-user-catalogue-age 9999
                    *testing-problems* testing-problems]
        (core/reset-logging!)
        (apply require (map symbol ns-list))
        (let [retval {:errors (reduce + ((juxt :error :fail)
                                         (apply test/run-tests ns-list)))}]
          (report-testing-problems)
          retval)))))
