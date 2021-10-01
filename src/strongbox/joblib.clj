(ns strongbox.joblib
  (:require
   [lasync.core :as lasync]
   [clojure.set]
   [taoensso.timbre :as timbre :refer [warn spy]]
   [flatland.ordered.map :refer [ordered-map]]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [strongbox
    ;;[specs :as sp]
    [utils :as utils :refer [atom?]]]))

(comment "Simple one job per thread queue manager. Keep module uncoupled from core and ui.")

(def ^:dynamic tick
  "per-thread binding to a function that updates progress of running job"
  (constantly nil))

(defn-spec tick-delay :joblib/progress
  "ticks but then pauses for a short random period providing an illusion that something is happening.
  pause only occurs if the `tick` fn is bound."
  [pct :joblib/progress]
  (when-let [progress (tick pct)]
    (Thread/sleep (rand-int 150))
    progress))

(defn-spec deref* any?
  [future-obj future?]
  (try
    (deref future-obj)
    (catch java.util.concurrent.CancellationException ce
      ;; deref'ing a cancelled job raises a cancellation exception.
      ce)))

(defn-spec pmap* vector?
  [queue-atm atom?, pool utils/thread-pool-executor?, f fn?, xs coll?]
  (->> xs
       (mapv (fn [[job-id job-info]]
               (let [future-obj (lasync/submit pool #(f [job-id job-info]))]
                 (swap! queue-atm assoc-in [job-id :job] future-obj)
                 future-obj)))
       (mapv deref*)))

;; utils

(defn-spec progress :joblib/progress
  [total number?, pos number?]
  (if (or (zero? pos)
          (zero? total))
    0.0
    (double (* pos (/ 1 total)))))

(defn-spec unique-id :joblib/job-id
  "same as `utils/unique-id` but coerced to a keyword."
  []
  (keyword (utils/unique-id)))

(defn-spec addon-id :joblib/job-id
  [addon :joblib/addon]
  (set (utils/select-vals addon [:source :source-id :dirname])))

(defn-spec addon-job-id :joblib/job-id
  [addon :joblib/addon, job-id :joblib/job-id]
  (conj (addon-id addon) job-id))

;;

(defn-spec make-queue :joblib/queue
  "returns an empty ordered map suitable for new queues"
  []
  (ordered-map))

(defn-spec make-ticker fn?
  "returns a fn that accepts a double value between 0.0 and 1.0 inclusive.
  called with a double will update the job's progress in the queue.
  called without arguments returns the job's current progress.
  called without arguments AND not bound to a thread, will return `nil` - see `tick`."
  [queue-atm atom?, job-id :joblib/job-id]
  (fn [& [pct]]
    (when pct
      ;;(println (format "thread %s goes tick! (%s)" (.getId (Thread/currentThread)) pct))
      (swap! queue-atm assoc-in [job-id :progress] pct))
    (:progress (get @queue-atm job-id))))

(defn-spec job-info :joblib/job-info
  "given a job `j` and optional map with keys :job-id, return a struct that can be added to a queue"
  [job :joblib/job, job-id :joblib/job-id]
  {:job job
   :job-id job-id
   :progress nil})

(defn-spec add-to-queue! (s/or :added :joblib/job-id, :not-added :joblib/job-id)
  "adds a `job-info` map to the queue (and *not* a job function)"
  [queue-atm atom?, job-info :joblib/job-info]
  (let [job-id (:job-id job-info)]
    (dosync
     (if (contains? @queue-atm job-id)
       (warn "job with that id exists! not overwriting:" job-id)
       (do (swap! queue-atm assoc job-id job-info)
           job-id)))))

(defn-spec create-job! :joblib/job-id
  "convenience. takes a function `f`, wraps it in a `job-info`, gives it a tick function and adds it to the queue.
  returns the job ID"
  ([queue-atm atom?, f fn?]
   (create-job! queue-atm f (unique-id)))
  ([queue-atm atom?, f fn?, job-id :joblib/job-id]
   (add-to-queue! queue-atm (job-info f job-id))))

(defn-spec create-addon-job! :joblib/job-id
  "convenience. takes a function `f` whose first argument should be an addon map, wraps it in a `job-info`, etc etc."
  [queue-atm atom?, addon :joblib/addon, f fn?]
  (add-to-queue! queue-atm (job-info #(f addon) (addon-id addon))))

(defn-spec job-not-started? boolean?
  "returns `true` if the job hasn't been started yet."
  [job :joblib/job]
  (fn? job))

(defn-spec job-started? boolean?
  "returns `true` if the job has been started. It may even be done."
  [job :joblib/job]
  (future? job))

(defn-spec job-running? boolean?
  "returns `true` if job has been started and isn't finished or cancelled yet."
  [job :joblib/job]
  (and (future? job)
       (not (future-done? job))))

(defn-spec job-cancelled? boolean?
  "returns `true` if job was found was has been cancelled, else `false`."
  [job :joblib/job]
  (and (future? job)
       (future-cancelled? job)))

(defn-spec job-done? boolean?
  "returns `true` if job was found and has completed, this includes being cancelled or failing with an exception, otherwise `false`"
  [job :joblib/job]
  (and (future? job)
       (future-done? job)))

(defn-spec cancel-job boolean?
  "returns `true` if job was found and successfully cancelled. Already-cancelled and completed jobs cannot be cancelled."
  [job :joblib/job]
  (and (future? job)
       (future-cancel job)))

(defn-spec pop-job! :joblib/job-info
  "removes the job info from the queue and returns the derefable future, regardless of whether the job has been started, cancelled, completed etc"
  [queue-atm atom?, job-id :joblib/job-id]
  (dosync
   (when-let [job-info (get @queue-atm job-id)]
     (swap! queue-atm dissoc job-id)
     job-info)))

(defn-spec pop-all-jobs! (s/coll-of :joblib/job-info, :kind vector?)
  "removes all jobs from the `queue-atm`, returning their job information.
  blocks if any job is still incomplete."
  [queue-atm atom?]
  (dosync
   (or (some->> @queue-atm keys (mapv (partial pop-job! queue-atm)))
       [])))

(defn-spec run-jobs!* (s/coll-of any?, :kind vector?)
  "run all of the jobs in the given `queue-atm` in parallel, `n` at a time, until all are done.
  jobs that raised an exception have the exception raised returned."
  [queue-atm atom?, n pos-int?, pool utils/thread-pool-executor?]
  (let [start-job (fn [[job-id {:keys [job]}]]
                    (binding [tick (make-ticker queue-atm job-id)]
                      (try
                        (job)
                        (catch Exception uncaught-exc
                          uncaught-exc))))]
    (pmap* queue-atm pool start-job @queue-atm)))

(defn-spec run-jobs! (s/coll-of any?, :kind vector?)
  "convenience. run all of the jobs in the given `queue-atm` in parallel, `n` at a time, until all are done.
  jobs that raised an exception have the exception raised returned.
  queue jobs are removed afterwards.
  threadpool is shutdown afterwards."
  [queue-atm atom?, n pos-int?]
  (let [pool (lasync/pool {:threads n})]
    (try
      (run-jobs!* queue-atm n pool)
      (finally
        (pop-all-jobs! queue-atm)
        (lasync/shutdown pool)))))

(defn-spec queue-progress :joblib/progress
  "returns the total progress of all jobs in given queue"
  [queue :joblib/queue]
  (->> queue vals (map :progress) (remove nil?) (apply +) (progress (count queue))))

(defn-spec queue-info map?
  [queue :joblib/queue]
  (let [jobs (->> queue vals (map :job))]
    {:total (count jobs)
     :progress (queue-progress queue)
     :not-started (->> jobs (filter job-not-started?) count)
     :running (->> jobs (filter job-running?) count)
     :cancelled (->> jobs (filter job-cancelled?) count)
     :done (->> jobs (filter job-done?) count)}))

(defn-spec by-keyset fn?
  "returns a function that can filter a queue by the given `job-id`. 
  only works if `job-id` is a set (see `addon-id`)."
  [job-id :joblib/job-id]
  (if (set? job-id)
    (fn [[queue-key _]]
      (clojure.set/subset? job-id queue-key))
    (fn [[queue-key _]]
      (= queue-key job-id))))

(defn-spec has-job? boolean?
  "returns `true` if the given `queue` contains one or more jobs whose job-id is a *superset* of the given `job-id`.
  for example: 
    (has-job? my-queue #{:download-addon})` 
  would return all jobs whose `job-id` contains the `:download-addon` keyword."
  [queue :joblib/queue, job-id :joblib/job-id]
  (->> queue (filter (by-keyset job-id)) empty? not))
