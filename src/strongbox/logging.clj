(ns strongbox.logging
  (:require
   [taoensso.timbre :as logging]
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
  (and (-> logging/*config* :level (= :debug))
       (not (-> logging/*config* :testing?))))

(defn anon-println-appender
  "removes the hostname from the output format string"
  [data]
  (let [{:keys [timestamp_ msg_ level ?ns-str ?line]} data]
    ;; looks like: "2019-03-10 02:17:22.372 :info [strongbox.curseforge:89] downloading summary data for ..." 
    (println (format "%s %s [%s:%s] %s"
                     (force timestamp_) level (force ?ns-str) (force ?line) (force msg_)))))

(def default-logging-config
  {:level default-log-level
   :timestamp-opts {:pattern "yyyy-MM-dd HH:mm:ss.SSS"}
   ;;:output-fn (anon-println-appender)
   :appenders {:println {:enabled? true
                         :async? false
                         :output-fn :inherit
                         ;;:fn anon-println-appender ;; not printing out the exception as the first arg!
                         }}})

(logging/merge-config! default-logging-config)

(defn-spec add-appender! nil?
  "adds appender at `key` to logging config"
  ([key keyword?, f fn?]
   (add-appender! key f {}))
  ([key keyword?, f fn?, config map?]
   (logging/debug "adding appender" key config)
   (let [appender-config (merge {:fn f, :enabled? true} config)]
     (logging/merge-config! {:appenders {key appender-config}})
     nil)))

(defn-spec add-file-appender! nil?
  [output-file ::sp/file]
  (let [;; timbre being a little too clever...
        func (:fn (spit-appender {:fname output-file}))]
    (add-appender! :spit func))
  nil)

(defn-spec rm-appender! nil?
  "removes appender at `key` from logging config"
  [key keyword?]
  (logging/merge-config! {:appenders {key nil}})
  nil)

(defmacro buffered-log
  "macro. returns a list of log entries made while executing the given form"
  [level & form]
  `(let [stateful-buffer# (atom [])
         appender# (fn [data#]
                     (swap! stateful-buffer# into [(force (:msg_ data#))]))]
     (logging/with-merged-config {:level ~level,
                                  :appenders {:-temp {:fn appender#
                                                      :enabled? true}}}
       ~@form)
     (deref stateful-buffer#)))
