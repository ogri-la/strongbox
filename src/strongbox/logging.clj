(ns strongbox.logging
  (:require
   [taoensso.timbre :as timbre]
   [taoensso.timbre.appenders.core :refer [spit-appender]]
   [taoensso.tufte :as tufte :refer [p profile]]
   [orchestra.core :refer [defn-spec]]
   [clojure.spec.alpha :as s]
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

(def -default-logging-config
  {:min-level default-log-level

   :timestamp-opts {;;:pattern "yyyy-MM-dd HH:mm:ss.SSS"
                    :pattern "HH:mm:ss.SSS"
                    ;; default is `:utc`, `nil` sets tz to current locale.
                    :timezone nil}

   :appenders {:println {:enabled? true
                         :async? false
                         :output-fn :inherit
                         :fn anon-println-appender}}})

(defn-spec add-appender! nil?
  "adds appender at `key` to logging config"
  ([key keyword?, f fn?]
   (add-appender! key f {}))
  ([key keyword?, f fn?, config map?]
   (let [appender-config (merge {:fn f, :enabled? true} config)]
     (timbre/debug "adding appender" key)
     (timbre/merge-config! {:appenders {key appender-config}})
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
  (timbre/debug "removing appender" key)
  (timbre/merge-config! {:appenders {key nil}})
  nil)

(defmacro buffered-log
  "macro. returns a list of log entries made while executing the given form"
  [level & form]
  `(let [stateful-buffer# (atom [])
         appender# (fn [data#]
                     (swap! stateful-buffer# into [(force (:msg_ data#))]))]
     (timbre/with-merged-config {:min-level ~level,
                                 :appenders {:-temp {:fn appender#
                                                     :enabled? true}}}
       ~@form)
     (deref stateful-buffer#)))

(defn-spec add-ui-appender! fn?
  "adds a logger intended for the UI to interact with.
  if an addon is passed as `:context` then an identifier can be pulled from it and the UI can use that to
  associate log events with addons. see `addon-log`, `with-addon` and `with-label`."
  [atm ::sp/atom, install-dir (s/nilable ::sp/install-dir)]
  (let [inc* #(inc (or % 0))
        func (fn [data]
               (let [addon (some-> data :context :addon)
                     addon-id (select-keys addon [:dirname :source :source-id :name])
                     ;; {:install-dir "/path/to/addons/dir" :dirname "some-addon" :name "SomeAddon"}
                     source (merge {:install-dir install-dir} addon-id)
                     level (force (:level data))
                     log-line {:time (force (:timestamp_ data))
                               :message (force (:msg_ data))
                               :level level
                               :source source}]
                 (when atm
                   (swap! atm update-in [:log-lines] into [log-line])
                   (when-let [dirname (:dirname addon)]
                     (swap! atm update-in [:log-stats dirname level] inc*)))))]
    (add-appender! :atom func {:timestamp-opts {:pattern "HH:mm:ss"} :atm atm}))
  (fn []
    (rm-appender! :atom)))

;; => (logging/addon-log :info {...} "installed!")
(defmacro addon-log
  "once-off addon logging message"
  [addon level & form]
  `(timbre/with-context
     {:addon ~addon}
     (timbre/log ~level ~@form)))

;; => (logging/with-addon {...} (info "installed!"))
(defmacro with-addon
  "all calls to debug/info/warn etc within enclosure become addon-level logging.
  app-level logging will have to futz with the logging context"
  [addon & form]
  `(timbre/with-context
     {:addon ~addon}
     ~@form))

(defmacro with-label
  [label & form]
  `(with-addon {:name ~label}
     ~@form))

;;

;; worked for a good long time:
;;  (timbre/merge-config! -default-logging-config)
;; now I need tighter control over the logging configuration as the UI appender modifies the application state

(defn-spec reset-logging! fn?
  ([testing? boolean?]
   (reset-logging! testing? nil nil))
  ([testing? boolean?, atm (s/nilable ::sp/atom), addon-dir (s/nilable ::sp/install-dir)]
   ;; reset logging configuration to whatever it should be. it's global mutable state and it's fucking us.
   (timbre/swap-config! timbre/default-config)
   (timbre/merge-config! -default-logging-config) ;; layer in our own config
   (let [rm-atm-appender (if atm
                           (add-ui-appender! atm addon-dir)
                           (constantly nil))]
     (when testing?
       (timbre/merge-config! {:testing? true, :min-level :debug, :appenders {:spit nil}}))
     rm-atm-appender)))
