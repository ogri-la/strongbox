(ns strongbox.db
  (:require
   [taoensso.timbre :refer [log debug info warn error spy]]
   [strongbox.utils :as utils :refer [uuid]]
   [crux.api :as crux]
   [clojure.java.io :as io]))

(defn to-crux-doc
  [blob]
  (cond
    ;; data blob already has a crux id
    ;; prefer that over any id it may have picked up and pass it through
    (and (map? blob)
         (contains? blob :crux.db/id)) (dissoc blob :id)

    ;; data blob has an id but no crux id, rename :id to crux id
    (and (map? blob)
         (contains? blob :id)) (clojure.set/rename-keys blob {:id :crux.db/id})

    ;; given something that isn't a map
    ;; wrap in a map, give it an id and pass it through
    (not (map? blob)) {:crux.db/id (uuid) :data blob}

    ;; otherwise, it *is* a map but is lacking an id or a crux id
    :else (assoc blob :crux.db/id (uuid))))

(defn from-crux-doc
  [result]
  (when result
    (if (map? result)
      (clojure.set/rename-keys result {:crux.db/id :id})

      ;; ... ? just issue a warning and pass it through
      (do
        (warn (str "got unknown type attempting to coerce result from crux db:" (type result)))
        result))))

(defn put
  [node blob]
  (crux/submit-tx node [[:crux.tx/put (to-crux-doc blob)]]))

(defn put+wait
  [node blob]
  (crux/await-tx node (put blob)))

(defn get-by-id
  [node id]
  (from-crux-doc (crux/entity (crux/db node) id)))

(defn get-by-id+time
  [node id time]
  (from-crux-doc (crux/entity (crux/db node time) id)))

(defn query-by-type
  [node type-kw]
  (crux/q (crux/db node)
          '{:find [e]
            :where [[e :type type-kw]]}))

(defn start-node
  "returns a node that is needed for accessing the db"
  []
  ;; in-memory only
  (crux/start-node {:crux.node/topology '[crux.standalone/topology]
                    ;;:crux.standalone/event-log-kv-store 'crux.kv.memdb/kv ;; necessary?

                    }))
