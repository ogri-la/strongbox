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
    [core :as core :refer [paths]]
    [utils :as utils :refer [in?]]]
   [wowman.ui
    [cli :as cli]
    [gui :as gui]])
  (:gen-class))

(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ thread ex]
     (error (timbre/stacktrace ex) "Uncaught exception on" (.getName thread)))))

(defn stop
  []
  (let [opts (:cli-opts core/state)]
    (if (= :cli (:ui opts))
      (cli/stop core/state)
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
  (start cli-opts))

(defn test
  []
  (clojure.tools.namespace.repl/refresh) ;; reloads all namespaces, including wowman.whatever-test ones
  (logging/change-log-level :debug)
  (clojure.test/run-all-tests #"wowman\..*-test"))

;;

(defn usage
  [parsed-opts]
  (str "Usage: ./wowman [--action] [--addons-dir]\n\n" (:summary parsed-opts)))

;;

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

   ["-a" "--action ACTION" "perform action and exit. action is one of 'list', 'list-updates', 'update', 'update-all', 'scrape-addon-list', 'update-addon-list'"
    :id :action
    :parse-fn #(-> % lower-case keyword)
    :validate [(in? [:list :list-updates :update :update-all :scrape-addon-list :update-addon-list])]]])

(defn validate
  [parsed]
  (let [{:keys [options errors]} parsed]
    (cond
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

      ;; state directory doesn't exist and parent directory isn't writable
      ;; nowhere to create state dir, nowhere to download addon list. non-starter
      (and
       (not (fs/exists? (paths :state-dir)))
       (not (fs/writeable? (fs/parent (paths :state-dir))))) {:ok? false, :exit-message (str "State directory doesn't exist and it cannot be created: " (paths :state-dir))}

      ;; state directory *does* exist but isn't writeable
      ;; another non-starter
      (and (fs/exists? (paths :state-dir))
           (not (fs/writeable? (paths :state-dir)))) {:ok? false, :exit-message (str "State directory isn't writeable:" (paths :state-dir))}

      :else

      ;; post-processing
      (let [{:keys [options]} args

            ;; switch default ui to :cli if --headless given without explicit --ui
            args (if (not (contains? options :ui))
                   (assoc-in args [:options :ui] (if (:headless? options) :cli :gui))
                   args)

            ;; force :cli for certain actions
            args (if (contains? #{:scrape-addon-list :update-addon-list} (:action options))
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
