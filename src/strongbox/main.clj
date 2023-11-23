(ns strongbox.main
  (:require
   [taoensso.timbre :as timbre :refer [spy info warn error report]]
   [clojure.tools.cli]
   [clojure.string :refer [lower-case]]
   [me.raynes.fs :as fs]
   [strongbox
    [core :as core]
    [utils :as utils :refer [in?]]
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
(def ^:dynamic *spec?* (utils/in-repl?))

(defn jfx
  "dynamically resolve the `strongbox.jfx` ns and call the requisite `action`.
  `action` is either `:start` or `:stop`.
  this is done because including the cljfx directly will start will the JavaFX application
  thread and cause hanging behaviour when running tests or using the non-gui CLI"
  [action]
  (require '[strongbox.jfx])
  (let [jfx-ns (find-ns 'strongbox.jfx)]
    (case action
      :start ((ns-resolve jfx-ns 'start))
      :stop ((ns-resolve jfx-ns 'stop)))))

(defn stop
  []
  (let [opts (:cli-opts @core/state)]
    (case (:ui opts)
      :noui (cli/stop)
      :cli (cli/stop)
      :gui (jfx :stop)

      ;; allows us to start the app without starting a UI during testing.
      (when (not core/*testing?*)
        (jfx :stop)))
    (core/stop core/state)))

(defn shutdown-hook
  []
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread. ^Runnable stop)))

(defn start
  [& [cli-opts]]
  (core/start (merge {:spec? *spec?*} cli-opts))
  (case (:ui cli-opts)
    :noui (cli/action cli-opts)
    :cli (cli/start cli-opts)
    :gui (jfx :start)

    ;; allows us to start the app without starting a UI during testing.
    (when (not core/*testing?*)
      (jfx :start)))
  nil)

(defn restart
  [& [cli-opts]]
  (stop)
  (Thread/sleep 750) ;; gives me time to switch panes
  (start cli-opts))

;;

(defn usage
  [parsed-opts]
  (str "Usage: ./strongbox [--action] [--addons-dir]\n\n" (:summary parsed-opts)))

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
    :validate [(in? [:print-config :list :list-updates :update-all])]]])

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
                   args)

            ;; force `:noui` for certain actions
            args (if (contains? #{:print-config} (:action options))
                   (assoc-in args [:options :ui] :noui)
                   args)
            ]
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
