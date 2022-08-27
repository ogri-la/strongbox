(ns strongbox.main
  (:refer-clojure :rename {test clj-test})
  (:require
   [taoensso.timbre :as timbre :refer [spy info warn error report]]
   [clojure.test]
   [clojure.tools.cli]
   [clojure.tools.namespace.repl :as tn :refer [refresh]]
   [clojure.string :refer [lower-case]]
   [me.raynes.fs :as fs]
   [strongbox
    [catalogue :as catalogue]
    [http :as http]
    [joblib :as joblib]
    [core :as core]
    [utils :as utils :refer [in?]]]
   [gui.diff :refer [with-gui-diff]]
   [strongbox.ui
    ;; warning! requiring cljfx starts the javafx application thread.
    ;; this is a pita for exiting a non-javafx UI (cli) and aot as it just 'hangs'.
    ;; hanging aot is handled in project.clj, but dynamic inclusion of jfx is handled here.
    ;;[jfx :as jfx] 
    [cli :as cli]])
  (:gen-class))

(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ thread ex]
     (error ex (format "unexpected error on thread %s: %s" (.getName thread) (.getMessage ex))))))

;; spec checking is enabled during repl development and *any* testing unless explicitly turned off.
;; spec checking is disabled upon release
(def spec? (utils/in-repl?))

(defn jfx
  "dynamically resolve the `strongbox.ui.jfx` ns and call the requisite `action`.
  `action` is either `:start` or `:stop`.
  this is done because including the cljfx directly will start will the JavaFX application
  thread and cause hanging behaviour when running tests or using the non-gui CLI"
  [action]
  (require 'strongbox.ui.jfx)
  (let [jfx-ns (find-ns 'strongbox.ui.jfx)]
    (case action
      :start ((ns-resolve jfx-ns 'start))
      :stop ((ns-resolve jfx-ns 'stop)))))

(defn stop
  []
  (let [opts (:cli-opts @core/state)]
    (case (:ui opts)
      :cli (cli/stop)
      :gui (jfx :stop)

      ;; allows us to start the app without starting a UI during testing.
      (when (not core/testing?)
        (jfx :stop)))
    (core/stop core/state)))

(defn shutdown-hook
  []
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread. ^Runnable stop)))

(defn start
  [& [cli-opts]]
  (core/start (merge {:spec? spec?} cli-opts))
  (case (:ui cli-opts)
    :cli (cli/start cli-opts)
    :gui (jfx :start)

    ;; allows us to start the app without starting a UI during testing.
    (when (not core/testing?)
      (jfx :start)))
  nil)

(defn restart
  [& [cli-opts]]
  (stop)
  (Thread/sleep 750) ;; gives me time to switch panes
  (start cli-opts))

(defn test
  [& [ns-kw fn-kw]]
  (stop)
  (clojure.tools.namespace.repl/refresh) ;; reloads all namespaces, including strongbox.whatever-test ones
  (utils/instrument true) ;; always test with spec checking ON

  (try
    ;; note! remember to update `cloverage.clj` with any new bindings
    (with-redefs [core/testing? true
                  http/*default-pause* 1 ;; ms
                  http/*default-attempts* 1
                  ;; don't pause while testing. nothing should depend on that pause happening.
                  ;; note! this is different to `joblib/tick-delay` not delaying when `joblib/*tick*` is unbound.
                  ;; tests still bind `joblib/*tick*` and run things in parallel.
                  joblib/tick-delay joblib/*tick*
                  ;;main/spec? true
                  ;;cli/install-update-these-in-parallel cli/install-update-these-serially
                  ;;core/check-for-updates core/check-for-updates-serially
                  ;; for testing purposes, no addon host is disabled
                  catalogue/host-disabled? (constantly false)]
      (core/reset-logging!)

      (if ns-kw
        (if (some #{ns-kw} [:main :utils :http :tags
                            :core :toc :nfo :zip :config :catalogue :db :addon :logging :joblib
                            :cli :gui :jfx
                            :curseforge-api :wowinterface-api :gitlab-api :github-api :tukui-api
                            :release-json])
          (with-gui-diff
            (if fn-kw
              ;; `test-vars` will run the test but not give feedback if test passes OR test not found
              ;; slightly better than nothing
              (clojure.test/test-vars [(resolve (symbol (str "strongbox." (name ns-kw) "-test") (name fn-kw)))])
              (clojure.test/run-all-tests (re-pattern (str "strongbox." (name ns-kw) "-test")))))
          (error "unknown test file:" ns-kw))
        (clojure.test/run-all-tests #"strongbox\..*-test")))
    (finally
      ;; use case: we run the tests from the repl and afterwards we call `restart` to start the app.
      ;; `stop` inside `restart` will be outside of `with-redefs` and still have logging `:min-level` set to `:debug`
      ;; it will dump a file and yadda yadda.
      (core/reset-logging!))))

;;

(defn usage
  [parsed-opts]
  (str "Usage: ./strongbox [--action] [--addons-dir]\n\n" (:summary parsed-opts)))

;;

(def cli-options
  [["-h" "--help"]

   [nil "--version" "print current version of strongbox"
    :id :version-string
    :default false]

   ["-d" "--addons-dir DIR" "location of addon directory"
    :id :install-dir
    :parse-fn #(-> % fs/expand-home fs/normalized str)
    :validate [#(fs/directory? %) "must be a directory that exists"]]

   ["-H" "--headless" "headless mode will never prompt you for input and always choose the most sensible default. headless mode uses the CLI rather than the GUI."
    :id :headless?
    :parse-fn #(-> % lower-case (= "true"))
    :validate [boolean?]]

   ["-v" "--verbosity LEVEL" "level is one of 'debug', 'info', 'warn', 'error', 'fatal'. default is 'info'"
    :parse-fn #(-> % lower-case keyword)
    :validate [(in? [:debug :info :warn :error :fatal])]]

   [nil "--debug" "debug mode. verbosity level is highest, profiling is enabled and log output is written to a file"
    :id :debug-mode?
    :default false]

   ["-u" "--ui UI" "ui is either 'gui' (graphical user interface, default) or 'cli' (command line interface)"
    ;;:default :gui ;; set after determining if --headless also set
    :parse-fn #(-> % lower-case keyword)
    :validate [(in? [:cli :gui])]]

   ["-a" "--action ACTION" "perform action and exit. action is one of: 'list', 'list-updates', 'update-all'"
    :id :action
    :parse-fn #(-> % lower-case keyword)
    :validate [(in? [:list :list-updates :update-all])]]])

(defn validate
  [parsed]
  (let [{:keys [options errors]} parsed]
    (cond
      (= "root" (System/getProperty "user.name")) {:ok? false, :exit-message "strongbox should not be run as the 'root' user"}

      (:help options) {:ok? true, :exit-message (usage parsed)}

      (:version-string options) {:ok? true :exit-message (str "strongbox " (core/strongbox-version))}

      errors {:ok? false, :exit-message (str "The following errors occurred while parsing your command:\n\n"
                                             (clojure.string/join \newline errors))}
      :else parsed)))

(defn parse
  [args]
  (let [args (clojure.tools.cli/parse-opts args cli-options)]
    (cond
      ;; problems with user args, no further processing
      (:errors args) args

      :else

      ;; post-processing
      (let [{:keys [options]} args

            ;; force verbosity to `:debug` when `--debug` is given
            args (if (:debug-mode? options)
                   (-> args (assoc-in [:options :verbosity] :debug) (update-in [:options] dissoc :debug-mode?))
                   args)

            ;; switch default ui to `:cli` if `--headless` given without explicit `--ui`
            args (if (not (contains? options :ui))
                   (assoc-in args [:options :ui] (if (:headless? options) :cli :gui))
                   args)

            ;; force `:cli` for certain actions
            args (if (contains? #{} (:action options))
                   (assoc-in args [:options :ui] :cli)
                   args)]
        args))))

(defn exit
  [status & [msg]]
  (when (core/started?)
    (stop))
  (when msg
    (println msg))
  (System/exit status))

(defn -main
  [& args]
  (let [{:keys [options exit-message ok?]} (-> args parse validate)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (do (shutdown-hook)
          (start options)))))
