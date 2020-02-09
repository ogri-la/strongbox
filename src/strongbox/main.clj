(ns strongbox.main
  (:refer-clojure :rename {test clj-test})
  (:require
   [taoensso.timbre :as timbre :refer [spy info error]]
   [clojure.test]
   [clojure.tools.cli]
   [clojure.tools.namespace.repl :as tn :refer [refresh]]
   [clojure.string :refer [lower-case]]
   [me.raynes.fs :as fs]
   [strongbox
    [logging :as logging]
    [core :as core]
    [utils :as utils :refer [in?]]]
   [strongbox.ui
    [cli :as cli]
    [gui :as gui]])
  (:gen-class))

(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ thread ex]
     (error ex "Uncaught exception on" (.getName thread)))))

(defn watch-for-gui-restart
  "monitors application state for requests to restart the gui.
  logic lives here rather than `core.clj` because `core.clj` is a dependency of `gui.clj` and
  having the gui restart *itself* requires `declare` statements"
  []
  (let [callback (fn [{:keys [cli-opts gui gui-restart-flag]}]
                   (when (and (not= :cli (:ui cli-opts))    ;; using a gui
                              (not (nil? gui-restart-flag)) ;; and the restart flag is set
                              (not (nil? gui)))             ;; and we actually have a gui to restart
                     (gui/stop)
                     (gui/start)
                     ;; reset flag. will trigger watch again but the checks will prevent infinite recursion
                     (swap! core/state assoc :gui-restart-flag nil)))]
    (core/state-bind [:gui-restart-flag] callback)))

(defn stop
  []
  (let [opts (:cli-opts @core/state)]
    (if (= :cli (:ui opts))
      (cli/stop)
      (gui/stop))
    (core/stop core/state)))

(defn start
  [& [cli-opts]]
  (core/start (or cli-opts {}))
  (if (= :cli (:ui cli-opts))
    (cli/start cli-opts)
    (gui/start))

  (watch-for-gui-restart)

  nil)

(defn restart
  [& [cli-opts]]
  (stop)
  (Thread/sleep 750) ;; gives me time to switch panes
  (start cli-opts))

(defn restart-cli
  "convenience while developing. starts app, does not start gui"
  []
  (restart {:ui :cli}))

(defn test
  [& [ns-kw fn-kw]]
  (clojure.tools.namespace.repl/refresh) ;; reloads all namespaces, including strongbox.whatever-test ones
  (try
    (logging/change-log-level :debug)
    (if ns-kw
      (if (some #{ns-kw} [:main :utils :http
                          :core :toc :nfo :zip :config :catalogue
                          :cli :gui
                          :curseforge-api :wowinterface :wowinterface-api :github-api :tukui-api])
        (if fn-kw
          ;; `test-vars` will run the test but not give feedback if test passes OR test not found
          ;; slightly better than nothing
          (clojure.test/test-vars [(resolve (symbol (str "strongbox." (name ns-kw) "-test") (name fn-kw)))])
          (clojure.test/run-all-tests (re-pattern (str "strongbox." (name ns-kw) "-test"))))
        (error "unknown test file:" ns-kw))
      (clojure.test/run-all-tests #"strongbox\..*-test"))
    (finally
      (logging/change-log-level (:level logging/default-logging-config)))))

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
  [status msg]
  (println msg)
  (System/exit status))

(defn -main
  [& args]
  (let [{:keys [options exit-message ok?]} (-> args parse validate)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (start options))))
