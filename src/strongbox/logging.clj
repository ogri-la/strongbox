(ns strongbox.logging
  (:require
   [taoensso.timbre :as timbre]
   [taoensso.timbre.appenders.core :refer [spit-appender]]
   [orchestra.core :refer [defn-spec]]
   [clojure.spec.alpha :as s]
   [strongbox
    [specs :as sp]]))

;; set the default log level to :info as soon as possible (default is `:debug`)
;; `core/debug-mode?` relies on environment having been downgraded to `:debug`

(timbre/merge-config! {:min-level :info})

(def default-log-level :info)
(def level-map {:debug 0 :info 1 :warn 2 :error 3 :report 4})

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
  (timbre/merge-config! {:appenders {key nil}})
  nil)

(defmacro buffered-log+return-value
  "macro. returns a pair of `form` results and a list of log entries at the given `level` and above made while executing the given `form`"
  [level & form]
  `(let [stateful-buffer# (atom [])
         appender# (fn [data#]
                     (swap! stateful-buffer# into [(force (:msg_ data#))]))]
     (timbre/with-merged-config {:min-level ~level,
                                 :appenders {:-temp {:fn appender#
                                                     :enabled? true}}}
       (let [result# ~@form]
         [(deref stateful-buffer#) result#]))))

(defmacro buffered-log
  "macro. returns a list of log entries at the given `level` and above made while executing the given `form`"
  [level & form]
  `(first (buffered-log+return-value ~level ~@form)))

(defn-spec log-line-filter fn?
  "returns a function that matches a log entry to the given `install-dir` + `addon`"
  [install-dir (s/nilable ::sp/install-dir), addon map?]
  (let [;; installed addon
        preferred-match {:install-dir install-dir, :dirname (:dirname addon)}
        ;; addons from the catalogue
        alt-match {:install-dir install-dir, :source (:source addon), :source-id (:source-id addon)}]
    (fn [log-line]
      (or (= preferred-match (select-keys (:source log-line) [:install-dir :dirname]))
          (= alt-match (select-keys (:source log-line) [:install-dir :source :source-id]))))))

(defn-spec log-line-filter-with-reports fn?
  "like `log-line-filter`, but conveniently includes 'report' level log lines as well."
  [install-dir (s/nilable ::sp/install-dir), addon map?]
  (let [filter-fn (log-line-filter install-dir addon)]
    (fn [log-line]
      (or (-> log-line :level (= :report))
          (filter-fn log-line)))))

(defn-spec add-ui-appender! fn?
  "adds an appender intended for the UI to interact with.
  If an addon is passed as `:context` then an identifier can be pulled from it and the UI can use that to
  associate log events with addons. 
  'report' level logs have no addon information attached to them.
  see `addon-log`, `with-addon` and `with-label`."
  [state-atm ::sp/atom, install-dir (s/nilable ::sp/install-dir)]
  (let [func (fn [data]
               (let [addon (some-> data :context :addon)
                     addon-id (select-keys addon [:dirname :source :source-id :name])
                     ;; {:install-dir "/path/to/addons/dir" :dirname "some-addon" :name "SomeAddon"}
                     source (merge {:install-dir install-dir} addon-id)
                     level (force (:level data))
                     msg (force (:msg_ data))
                     log-line {:time (force (:timestamp_ data))
                               :message msg
                               :level level
                               :source source}

                     ;; report-level logs are addon agnostic
                     log-line (if (= :report level)
                                (dissoc log-line :source)
                                log-line)]

                 ;; 2021-11-10: disabled as it's preventing addons from reporting errors correctly.
                 ;; I'm also not seeing any error floods (yet).
                 (if (and false (= msg (-> @state-atm :log-lines last :message)))
                   ;; purely to stop infinite feedback loop. not sure how this one is happening but urgh.
                   (println "[dropping duplicate message]")
                   (when state-atm
                     (swap! state-atm update-in [:log-lines] into [log-line])))))]

    (add-appender! :atom func {:timestamp-opts {:pattern "HH:mm:ss"}}))
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

(defmacro without-addon
  "an explicit opt-out of addon logging for log events nested within a `with-addon`"
  [& form]
  `(with-addon nil ~@form))

(defmacro with-label
  [label & form]
  `(with-addon {:name ~label}
     ~@form))

(defmacro silenced
  "swallows log output. recommended for shallow forms where we knows errors/warnings can be discarded"
  [& form]
  `(timbre/with-merged-config
     {:middleware [(constantly nil)]}
     ~@form))
