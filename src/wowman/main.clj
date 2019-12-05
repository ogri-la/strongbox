(ns wowman.main
  (:refer-clojure :rename {test clj-test})
  (:require
   [taoensso.timbre :as timbre :refer [spy info error]]
   [clojure.test]
   [clojure.tools.cli]
   [clojure.tools.namespace.repl :as tn :refer [refresh]]
   [clojure.string :refer [lower-case]]
   [me.raynes.fs :as fs]
   [wowman
    [logging :as logging]
    [core :as core]
    [utils :as utils :refer [in?]]]
   [wowman.ui
    [cli :as cli]
    [gui :as gui]])
  (:gen-class))

(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ thread ex]
     (error ex "Uncaught exception on" (.getName thread)))))

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
  [& [path]]
  (clojure.tools.namespace.repl/refresh) ;; reloads all namespaces, including wowman.whatever-test ones
  (try
    (logging/change-log-level :debug)
    (if path
      (if (some #{path} [:core :http :main :toc :utils :curseforge-api :zip :catalog :cli :gui :wowinterface :wowinterface-api :github-api :tukui-api])
        (clojure.test/run-all-tests (re-pattern (str "wowman." (name path) "-test")))
        (error "unknown test file:" path))
      (clojure.test/run-all-tests #"wowman\..*-test"))
    (finally
      (logging/change-log-level (:level logging/default-logging-config)))))

;;

(defn usage
  [parsed-opts]
  (str "Usage: ./wowman [--action] [--addons-dir]\n\n" (:summary parsed-opts)))

;;

(def catalog-actions
  #{:scrape-catalog :write-catalog
    :scrape-curseforge-catalog :scrape-wowinterface-catalog :scrape-tukui-catalog})

(def catalog-action-str (clojure.string/join ", " (mapv #(format "'%s'" (name %)) (sort catalog-actions))))

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

   ["-v" "--verbosity LEVEL" "level is one of 'debug', 'info', 'warn', 'error', 'critical'. default is 'info'"
    :parse-fn #(-> % lower-case keyword)
    :validate [(in? [:debug :info :warn :error :fatal])]]

   ["-u" "--ui UI" "ui is either 'gui' (graphical user interface, default) or 'cli' (command line interface)"
    ;;:default :gui ;; set after determining if --headless also set
    :parse-fn #(-> % lower-case keyword)
    :validate [(in? [:cli :gui])]]

   ["-a" "--action ACTION" (str "perform action and exit. action is one of: 'list', 'list-updates', 'update-all'," catalog-action-str)
    :id :action
    :parse-fn #(-> % lower-case keyword)
    :validate [(in? (concat [:list :list-updates :update-all] catalog-actions))]]])

(defn validate
  [parsed]
  (let [{:keys [options errors]} parsed]
    (cond
      (= "root" (System/getProperty "user.name")) {:ok? false, :exit-message "wowman should not be run as the 'root' user"}

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
            args (if (contains? catalog-actions (:action options))
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
