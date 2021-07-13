(ns strongbox.joblib
  (:require
   [flatland.ordered.map :refer [ordered-map]]
   [strongbox.utils :as utils]))

(comment "simple one job per thread queue manager. 
    keep module uncoupled from core and ui.")

(def ^:dynamic tick
  "per-thread binding to a function that updates progress of running job"
  (constantly nil))

;; utils

(defn progress
  [total pos]
  (if (or (zero? pos)
          (zero? total))
    0.0
    (float (* pos (/ 1 total)))))

(defn unique-id
  "same as `utils/unique-id` but coerced to a keyword."
  []
  (keyword (utils/unique-id)))

;;

(defn make-queue
  "returns an empty ordered map suitable for new queues"
  []
  (ordered-map))

(defn make-ticker
  "returns a fn that accepts a float value between 0.0 and 1.0 inclusive.
  called with a float will update the job's progress in the queue.
  called without arguments returns the job's current progress."
  [queue-atm job-id]
  (fn [& [pct]]
    (when pct
      (swap! queue-atm assoc-in [job-id :progress] pct))
    (:progress (get @queue-atm job-id))))

(defn job
  "given a function `f`, returns a 'job' (fn) that accepts an atom to be used as a progress meter.
  'starting' this job returns a future that can be deref'ed to retrieve the result or cancelled with `future-cancel`."
  [f]
  (fn [tick-fn]
    (future
      (binding [tick tick-fn]
        (try
          (f)
          (catch Exception uncaught-exc
            uncaught-exc))))))

(defn job-info
  "given a job `j` and optional map with keys :job-id, return a struct that can be added to a queue"
  [j & [{:keys [job-id]}]]
  {:job j
   :job-id (or job-id (unique-id))
   :progress nil})

(defn add-to-queue!
  [queue-atm ji]
  (let [job-id (:job-id ji)]
    (dosync
     (when-not (contains? @queue-atm job-id)
       (swap! queue-atm assoc job-id ji)
       job-id))))

(defn create-job-add-to-queue!
  "convenience, takes a function `f`, wraps it in a `job`, gives it a tick function and adds it to the queue.
  returns the job ID"
  [queue-atm f]
  (add-to-queue! queue-atm (job-info (job f))))

(defn get-job
  "fetches the job from the queue"
  [queue job-id]
  (:job (get queue job-id)))

(defn job-started?
  "returns `true` if the job has been started. It may even be done."
  [job]
  (future? job))

(defn job-not-started?
  [job]
  (fn? job))

(defn job-running?
  "returns `true` if job was found and isn't finished or cancelled yet, else `false`."
  [job]
  (and (future? job)
       (not (future-done? job))))

(defn job-cancelled?
  "returns `true` if job was found was has been cancelled, else `false`."
  [job]
  (and (future? job)
       (future-cancelled? job)))

(defn job-done?
  "returns `true` if job was found and has completed, this includes being cancelled or failing with an exception, otherwise `false`"
  [job]
  (and (future? job)
       (future-done? job)))

(defn start-job!
  "starts a job, updates the job-info with the new `future` and returns it."
  [queue-atm job-id]
  (dosync
   (let [ji (get @queue-atm job-id)
         j-fn (:job ji)]
     (when (and ji
                (job-not-started? j-fn))
       (let [;; `tick` updates the job's progress in the queue
             tick-fn (make-ticker queue-atm job-id)
             running-job (j-fn tick-fn)]
         ;; replace the job fn with the future object
         (swap! queue-atm assoc-in [job-id :job] running-job)
         running-job)))))

(defn cancel-job!
  "returns `true` if job was found and successfully cancelled. cancelled and completed jobs cannot be cancelled."
  [queue job-id]
  (let [job (get-job queue job-id)]
    (and (future? job)
         (future-cancel job))))

(defn job-results
  "returns the results of the job with the given `job-id`.
  if an exception was thrown during the job, the exception is returned.
  if the job was cancelled, the cancellation exception is returned."
  [queue job-id]
  (try
    (let [future-obj (get-job queue job-id)]
      ;; job may not be started yet and is still a fn! if it's a future, deref it
      (if (future? future-obj)
        @future-obj
        future-obj))
    (catch java.util.concurrent.CancellationException ce
      ;; deref'ing a cancelled job raises a cancellation exception.
      ce)))

(defn all-job-results
  [queue]
  (->> queue keys (mapv job-results)))

(defn pop-job!
  "removes the job info from the queue and returns the derefable future, regardless of whether the job has been started, cancelled, completed etc"
  [queue-atm job-id]
  (dosync
   (when-let [ji (get @queue-atm job-id)]
     (swap! queue-atm dissoc job-id)
     ji)))

(defn pop-all-jobs!
  [queue-atm]
  (dosync
   (some->> @queue-atm keys (mapv (partial pop-job! queue-atm)))))

(defn queue-progress
  "returns the total progress of all jobs in given queue"
  [queue]
  (->> queue vals (map :progress) (remove nil?) (apply +) (progress (count queue))))

(defn queue-info
  [queue]
  (let [jobs (->> queue vals (map :job))]
    {:total (count jobs)
     :progress (queue-progress queue)
     :not-started (->> jobs (filter job-not-started?) count)
     :running (->> jobs (filter job-running?) count)
     :cancelled (->> jobs (filter job-cancelled?) count)
     :done (->> jobs (filter job-done?) count)}))

(defn start-jobs-in-queue!
  "given a queue of jobs in various states and N number of jobs to be running, ensures that many jobs are running"
  ([queue-atm]
   (start-jobs-in-queue! queue-atm nil))
  ([queue-atm n-jobs-running]
   (dosync
    (let [job-done?* (fn [[_ ji]]
                       (job-done? (:job ji)))

          ;; ignore jobs that are done
          queue (remove job-done?* @queue-atm)

          job-running?* (fn [[_ ji]]
                          (job-running? (:job ji)))

          ;; leaving just those running or not started
          [jobs-running, jobs-not-started] (split-with job-running?* queue)

          start-job!* (partial start-job! queue-atm)

          num-to-run (if (nil? n-jobs-running)
                       (count jobs-not-started)
                       (- n-jobs-running (count jobs-running)))]
      (when (> num-to-run 0)
        (some->> jobs-not-started
                 (take num-to-run)
                 keys
                 (run! start-job!*)))))))

(defn monitor!
  "attaches a watch to the queue that calls `start-jobs-in-queue!` every time the queue changes.
  returns a function that accepts no arguments and stops the monitor when called."
  [queue-atm n-jobs-running]
  (let [key (unique-id)
        watch-fn (fn [key queue-atm-ref old-queue new-queue]
                   (let [queue-stats (queue-info new-queue)]
                     ;; number of jobs running is less than desired AND there are jobs outstanding
                     (when (and (< (:running queue-stats) n-jobs-running)
                                (> (:not-started queue-stats) 0))
                       ;;(println (format "thread %s tiggering start-jobs %s" (.getId (Thread/currentThread)) queue-stats))
                       ;; we're just using changes to the atm as a trigger,
                       ;; we're not really interested in what has changed
                       (start-jobs-in-queue! queue-atm-ref n-jobs-running))))]
    (add-watch queue-atm key watch-fn)
    (fn []
      (remove-watch queue-atm key))))
