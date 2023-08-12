(ns strongbox.user
  (:refer-clojure :rename {test clj-test})
  (:require
   [clojure.test :refer [report with-test-out inc-report-counter testing-vars-str testing-contexts-str *testing-contexts* *stack-trace-depth*]]
   [clojure.stacktrace :as stack]
   [taoensso.timbre :as timbre :refer [spy info warn error]]
   [clojure.tools.namespace.repl :as tn :refer [refresh]]
   [strongbox
    [logging :as logging]
    [addon :as addon]
    [constants :as constants]
    [main :as main :refer [restart stop]]
    [catalogue :as catalogue]
    [http :as http]
    [core :as core]
    [utils :as utils :refer [in?]]]
   [gui.diff :refer [with-gui-diff]]))

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

;; ---

(defn test
  [& [ns-kw fn-kw]]
  (main/stop)
  (clojure.tools.namespace.repl/refresh) ;; reloads all namespaces, including strongbox.whatever-test ones
  (utils/instrument true) ;; always test with spec checking ON
  
  (try
    (let [testing-problems (atom {:fail [] :error []})]
      ;; note! remember to update `cloverage.clj` with any new bindings
      (with-redefs [core/*testing?* true
                    http/*default-pause* 1 ;; ms
                    http/*default-attempts* 1
                    ;; don't pause while testing. nothing should depend on that pause happening.
                    ;; note! this is different to `joblib/tick-delay` not delaying when `joblib/*tick*` is unbound.
                    ;; tests still bind `joblib/*tick*` and run things in parallel.
                    ;;joblib/tick-delay joblib/*tick*
                    ;;main/*spec?* true
                    ;;cli/install-update-these-in-parallel cli/install-update-these-serially
                    ;;core/check-for-updates core/check-for-updates-serially
                    ;; for testing purposes, no addon host is disabled
                    utils/folder-size-bytes (constantly 0)
                    constants/max-user-catalogue-age 9999
                    *testing-problems* testing-problems
                    
                    ]
        (core/reset-logging!)

        (if ns-kw
          (if (some #{ns-kw} [:main :utils :http
                              :core :toc :nfo :zip :config :catalogue :addon :logging :joblib
                              :cli :gui :jfx
                              :curseforge-api :wowinterface-api :gitlab-api :github-api :tukui-api
                              :release-json])
            (do (with-gui-diff
                  (if fn-kw
                    ;; `test-vars` will run the test but not give feedback if test passes OR test not found
                    ;; slightly better than nothing
                    (clojure.test/test-vars [(resolve (symbol (str "strongbox." (name ns-kw) "-test") (name fn-kw)))])
                    (clojure.test/run-all-tests (re-pattern (str "strongbox." (name ns-kw) "-test")))))
                (report-testing-problems))
            (error "unknown test file:" ns-kw))
          (clojure.test/run-all-tests #"strongbox\..*-test"))))
    (finally
      ;; use case: we run the tests from the repl and afterwards we call `restart` to start the app.
      ;; `stop` inside `restart` will be outside of `with-redefs` and still have logging `:min-level` set to `:debug`
      ;; it will dump a file and yadda yadda.
      (core/reset-logging!))))
