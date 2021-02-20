(ns strongbox.main
  (:refer-clojure :rename {test clj-test})
  (:require
   [taoensso.timbre :as timbre :refer [spy info warn error]]
   [clojure.test]
   [clojure.tools.cli]
   [clojure.tools.namespace.repl :as tn :refer [refresh]]
   [clojure.string :refer [lower-case]]
   [me.raynes.fs :as fs]
   [strongbox
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
     (error ex "Uncaught exception on" (.getName thread)))))

;; profiling is disabled by default unless explicitly turned on.
;; profiling is enabled during testing via Cloverage via `with-redef`, else the forms are not counted properly.
(def profile? false)

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
      (jfx :stop))
    (core/stop core/state)))

(defn shutdown-hook
  []
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread. ^Runnable stop)))

(defn start
  [& [cli-opts]]
  (core/start (merge {:profile? profile?, :spec? spec?} cli-opts))
  (case (:ui cli-opts)
    :cli (cli/start cli-opts)
    :gui (jfx :start)
    (jfx :start))
  nil)

(defn restart
  [& [cli-opts]]
  (stop)
  (Thread/sleep 750) ;; gives me time to switch panes
  (start cli-opts))

(defn profile
  "runs the app the same as `start`, but enables profiling output"
  [& [cli-ops]]
  (let [default-opts {:verbosity :error, :ui :cli, :spec? false}]
    (restart (merge default-opts cli-ops {:profile? true}))))

(defn test
  [& [ns-kw fn-kw]]
  (clojure.tools.namespace.repl/refresh) ;; reloads all namespaces, including strongbox.whatever-test ones
  (utils/instrument true) ;; always test with spec checking ON
  (timbre/with-merged-config {:level :debug, :testing? true
                              ;; ensure we're not writing logs to files
                              :appenders {:spit nil}}
    (if ns-kw
      (if (some #{ns-kw} [:main :utils :http :tags
                          :core :toc :nfo :zip :config :catalogue :db :addon
                          :cli :gui :jfx
                          :curseforge-api :wowinterface :wowinterface-api :github-api :tukui-api])
        (with-gui-diff
          (if fn-kw
            ;; `test-vars` will run the test but not give feedback if test passes OR test not found
            ;; slightly better than nothing
            (clojure.test/test-vars [(resolve (symbol (str "strongbox." (name ns-kw) "-test") (name fn-kw)))])
            (clojure.test/run-all-tests (re-pattern (str "strongbox." (name ns-kw) "-test")))))
        (error "unknown test file:" ns-kw))
      (clojure.test/run-all-tests #"strongbox\..*-test"))))

;;

(defn usage
  [parsed-opts]
  (str "Usage: ./strongbox [--action] [--addons-dir]\n\n" (:summary parsed-opts)))

;;

(def catalogue-actions
  #{:scrape-catalogue :write-catalogue
    :scrape-curseforge-catalogue :scrape-wowinterface-catalogue :scrape-tukui-catalogue})

(def catalogue-action-str (clojure.string/join ", " (mapv #(format "'%s'" (name %)) (sort catalogue-actions))))

(def cli-options
  [["-h" "--help"]

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

   ["-a" "--action ACTION" (str "perform action and exit. action is one of: 'list', 'list-updates', 'update-all'," catalogue-action-str)
    :id :action
    :parse-fn #(-> % lower-case keyword)
    :validate [(in? (concat [:list :list-updates :update-all] catalogue-actions))]]])

(defn validate
  [parsed]
  (let [{:keys [options errors]} parsed]
    (cond
      (= "root" (System/getProperty "user.name")) {:ok? false, :exit-message "strongbox should not be run as the 'root' user"}

      (:help options) {:ok? true, :exit-message (usage parsed)}

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

            ;; force verbosity to :debug when `--debug` is given
            ;; `--debug` is a shortcut right now but has potential beyond just throttling output
            args (if (:debug-mode? options)
                   (-> args (assoc-in [:options :verbosity] :debug) (update-in [:options] dissoc :debug-mode?))
                   args)

            ;; switch default ui to :cli if --headless given without explicit --ui
            args (if (not (contains? options :ui))
                   (assoc-in args [:options :ui] (if (:headless? options) :cli :gui))
                   args)

            ;; force :cli for certain actions
            args (if (contains? catalogue-actions (:action options))
                   (assoc-in args [:options :ui] :cli)
                   args)]
        args))))

(defn exit
  [status & [msg]]
  (stop)
  (when msg (println msg))
  (System/exit status))

(defn -main
  [& args]
  (let [{:keys [options exit-message ok?]} (-> args parse validate)]
    (shutdown-hook)
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (start options))))
