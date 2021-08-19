(ns strongbox.joblib-test
  (:require
   [lasync.core :as lasync]
   [clojure.test :refer [deftest testing is use-fixtures]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [joblib :as joblib]]))

(deftest run-job!
  (let [queue-atm (atom (joblib/make-queue))
        stateful-thing (atom [])

        update-stateful-thing (fn []
                                (joblib/tick 0.0)
                                (swap! stateful-thing conj (.getId (Thread/currentThread)))
                                (joblib/tick 1.0))
        num 5
        pool (lasync/pool {:threads num})
        _ (vec (take num (repeatedly #(joblib/create-job! queue-atm update-stateful-thing (joblib/unique-id)))))]
    (try
      ;; n jobs in queue
      (is (= num (count @queue-atm)))

      ;; none are started
      (is (= (joblib/queue-info @queue-atm) {:total num
                                             :progress 0.0
                                             :not-started num
                                             :running 0
                                             :cancelled 0
                                             :done 0}))

      ;; execute all (without cleanup)
      (is (= num (count (joblib/run-jobs!* queue-atm num pool))))

      ;; overall progress is 100%, none are running, etc
      (is (= (joblib/queue-info @queue-atm) {:total num
                                             :progress 1.0
                                             :not-started 0
                                             :running 0
                                             :cancelled 0
                                             :done num}))

      (is (true? (->> @queue-atm vals first :job-id (joblib/has-job? @queue-atm))))

      (is (= num (count (joblib/pop-all-jobs! queue-atm))))

      (is (= (joblib/queue-info @queue-atm) {:total 0
                                             :progress 0.0
                                             :not-started 0
                                             :running 0
                                             :cancelled 0
                                             :done 0}))

      (finally
        (lasync/shutdown pool)))))
