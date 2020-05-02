(ns strongbox.db
  (:require
   [taoensso.timbre :refer [log debug info warn error spy]]
   [strongbox.utils :as utils :refer [uuid]]
   [datascript.core :as ds]
   ;;[datascript.db :as ds]
   
   [clojure.java.io :as io]))

(defn to-doc
  [blob]
  (cond
    ;; data blob already has a `:db/id`
    ;; prefer that over any :id it may have picked up and pass it through
    (and (map? blob)
         (contains? blob :db/id)) (dissoc blob :id)

    ;; data blob has an `:id` but no `:db/id`, rename `:id` to `:db/id`
    (and (map? blob)
         (contains? blob :id)) (-> blob
                                   (update-in [:id] name)
                                   (clojure.set/rename-keys {:id :db/id}))

    ;; given something that isn't a map
    ;; wrap in a map, give it a `:db/id` and pass it through
    (not (map? blob)) {:db/id (uuid) :data blob}

    ;; otherwise, it *is* a map but is lacking an `:id` or a `:db/id`
    :else (assoc blob :db/id (uuid))))

(defn from-doc
  [result]
  (when result
    (if (map? result)
      (-> result 
          (clojure.set/rename-keys {:db/id :id})
          (update-in [:id] keyword))

      ;; ... ? just issue a warning and pass it through
      (do
        (warn (str "got unknown type attempting to coerce result from db:" (type result)))
        result))))

(defn put
  [conn blob]
  (ds/transact! conn [(to-doc blob)]))

(defn put-many
  [conn doc-list]
  (ds/transact! conn (mapv to-doc doc-list)))

(defn get-by-id
  [conn id]
  (from-doc (ds/entity conn id)))

(defn query
  [conn query]
  (ds/q query conn))

(defn query-by-type
  [node type-kw]
  (query node '{:find [e]
                :where [[e :type type-kw]]}))

(defn stored-query
  "common queries we can call by keyword"
  [node query-kw & [arg-list]]
  (let [query-map {;; todo, obviously.
                   :catalogue-size (constantly 0)}]
    (if-let [query-fn (query-kw query-map)]
      (query-fn node)
      (error "query not found:" (name query-kw)))))

(defn start
  "initialises the database, returning something that can be used to access it later"
  []
  (ds/create-conn))
