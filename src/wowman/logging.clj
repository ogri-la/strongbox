(ns wowman.logging
  (:require
   [taoensso.timbre :as logging :refer [spy]]
   [orchestra.core :refer [defn-spec]]
   [orchestra.spec.test :as st]))

(defn anon-println-appender
  "removes the hostname from the output format string"
  [data]
  (let [{:keys [timestamp_ msg_ level ?ns-str ?line]} data]
    ;; looks like: "2019-03-10 02:17:22.372 :info [wowman.curseforge:89] downloading summary data for ..." 
    (println (format "%s %s [%s:%s] %s"
                     (force timestamp_) level (force ?ns-str) (force ?line) (force msg_)))))

(def default-logging-config
  {:level :info
   :timestamp-opts {:pattern "yyyy-MM-dd HH:mm:ss.SSS"}
   ;;:output-fn (anon-println-appender)
   :appenders {:println {:enabled? true
                         :async? false
                         :output-fn :inherit
                         ;;:fn anon-println-appender ;; not printing out the exception as the first arg!
                         }}})

(logging/merge-config! default-logging-config)

(defn-spec add-appender nil?
  "adds appender at `key` to logging config"
  ([key keyword?, f fn?]
   (add-appender key f {}))
  ([key keyword?, f fn?, config map?]
   (logging/debug "adding appender" key config)
   (let [appender-config (merge {:fn f, :enabled? true} config)]
     (logging/merge-config! {:appenders {key appender-config}})
     nil)))

(defn-spec rm-appender! nil?
  "removes appender at `key` from logging config"
  [key keyword?]
  (logging/merge-config! {:appenders {key nil}})
  nil)

(defmacro buffered-log
  "macro. returns a list of log entries made while executing the given form"
  [level form]
  `(let [stateful-buffer# (atom [])
         appender# (fn [data#]
                     (swap! stateful-buffer# into [(force (:msg_ data#))]))]
     ;; doesn't work. I suspect it has something to do with compile vs dynamic
     ;; https://github.com/ptaoussanis/timbre#log-levels-and-ns-filters
     ;; (add-appender :-temp appender# {:level ~level})
     (add-appender :-temp appender#)
     (try
       (logging/with-level ~level
         (do ~form))
       (deref stateful-buffer#)
       (finally
         (rm-appender! :-temp)))))

(defn-spec change-log-level nil?
  [new-level keyword?]
  (when new-level
    (logging/merge-config! {:level new-level}))
  nil)

(st/instrument)
