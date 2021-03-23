(ns strongbox.logging
  (:require
   [taoensso.timbre :as timbre]
   [taoensso.timbre.appenders.core :refer [spit-appender]]
   [taoensso.tufte :as tufte :refer [p profile]]
   [orchestra.core :refer [defn-spec]]
   [strongbox
    [specs :as sp]
    [utils :refer [join]]]))

;; profiling

(tufte/add-basic-println-handler! {})

(defn-spec add-profiling-handler! nil?
  "writes profiling data to a timestamped file in the given `output-dir`"
  [output-dir ::sp/extant-dir]
  (let [output-file (join output-dir (str (java-time/instant) ".pstats"))]
    (tufte/add-handler!
     :data-dir-logger "*"
     (fn [data-map]
       (spit output-file (tufte/format-pstats (:pstats data-map) nil)))))
  nil)

;; logging

(def default-log-level :info)

(defn-spec debug-mode? boolean?
  "debug mode is when the log level has been set to `:debug` and we're *not* running tests.
  the intent is to collect as much information around a problem as possible.
  the log level may change during REPL usage.
  the log level may change by using a `--verbosity` flag at runtime.
  `main.clj` adds an adhoc `testing?` flag to the logging configuration and removes it afterwards."
  []
  (and (-> timbre/*config* :level (= :debug))
       (not (-> timbre/*config* :testing?))))

;; https://github.com/ptaoussanis/timbre/blob/56d67dd274d7d11ab31624a70b4b5ae194c03acd/src/taoensso/timbre.cljc#L856-L858
(def colour-log-map
  {:debug :blue
   :info nil
   :warn :yellow
   :error :red
   :fatal :purple
   :report :blue})

(defn anon-println-appender
  "removes the hostname from the output format string"
  [data]
  (let [{:keys [?err timestamp_ msg_ level]} data
        level-colour (colour-log-map level)
        addon (some-> data :context :addon)
        label (or (:dirname addon)
                  (:name addon)
                  "app")
        pattern "%s [%s] [%s] %s"
        msg (force msg_)]
    (when ?err
      (println (timbre/stacktrace ?err)))

    (when-not (empty? msg)
      ;; looks like: "11:17:57.009 [info] [app] checking for updates"
      (println
       (timbre/color-str level-colour
                         (format
                          pattern
                          (force timestamp_)
                          (name level)
                          label
                          msg))))))

(def default-logging-config
  {:level default-log-level

   :timestamp-opts {;;:pattern "yyyy-MM-dd HH:mm:ss.SSS"
                    :pattern "HH:mm:ss.SSS"
                    ;; default is :utc apparently. documented fucking nowhere.
                    ;; nil will set tz to current locale.
                    :timezone nil}

   :appenders {:println {:enabled? true
                         :async? false
                         :output-fn :inherit
                         :fn anon-println-appender}}})

(timbre/merge-config! default-logging-config)

(defn-spec add-appender! nil?
  "adds appender at `key` to logging config"
  ([key keyword?, f fn?]
   (add-appender! key f {}))
  ([key keyword?, f fn?, config map?]
   (timbre/debug "adding appender" key config)
   (let [appender-config (merge {:fn f, :enabled? true} config)]
     (timbre/merge-config! {:appenders {key appender-config}})
     nil)))

(defn-spec add-file-appender! nil?
  [output-file ::sp/file]
  (let [;; timbre being a little too clever...
        func (:fn (spit-appender {:fname output-file}))]
    (add-appender! :spit func))
  nil)

(defn add-atom-appender!
  [atm install-dir]
  (let [path [:log-lines]
        func (fn [data]
               (let [addon (some-> data :context :addon)
                     addon-id (select-keys addon [:dirname :source :source-id :name])
                     source (merge {:install-dir install-dir} addon-id)
                     log-line {:time (force (:timestamp_ data))
                               :message (force (:msg_ data))
                               :level (force (:level data))
                               :source source}]
                 (when atm
                   (swap! atm update-in path into [log-line]))))]
    (add-appender! :atom func {:timestamp-opts {:pattern "HH:mm:ss"}}))
  nil)

(defn-spec rm-appender! nil?
  "removes appender at `key` from logging config"
  [key keyword?]
  (timbre/merge-config! {:appenders {key nil}})
  nil)

(defmacro buffered-log
  "macro. returns a list of log entries made while executing the given form"
  [level & form]
  `(let [stateful-buffer# (atom [])
         appender# (fn [data#]
                     (swap! stateful-buffer# into [(force (:msg_ data#))]))]
     (timbre/with-merged-config {:level ~level,
                                 :appenders {:-temp {:fn appender#
                                                     :enabled? true}}}
       ~@form)
     (deref stateful-buffer#)))

;; => (logging/addon-log :info {...} "installed!")
(defmacro addon-log
  "once-off addon logging message"
  [addon level & form]
  `(timbre/with-context
     {;;:install-dir (selected-addon-dir)
      :addon ~addon}
     (timbre/log ~level ~@form)))

;; => (logging/with-addon {...} (info "installed!"))
(defmacro with-addon
  "all calls to debug/info/warn etc within enclosure become addon-level logging.
  app-level logging will have to futz with the logging context"
  [addon & form]
  `(timbre/with-context
     {;;:install-dir (selected-addon-dir)
      :addon ~addon}
     ~@form))

(defmacro with-label
  [label & form]
  `(with-addon {:name ~label}
     ~@form))
