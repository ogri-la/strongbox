(ns strongbox.gitlab-api
  (:require
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [me.raynes.fs :as fs]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [http :as http]
    [toc :as toc]
    [utils :as utils]
    [specs :as sp]]))

(defn-spec expand-summary (s/or :ok :addon/release-list, :error nil?)
  [addon-summary :addon/expandable, game-track ::sp/game-track]
  nil)


(defn-spec parse-user-string (s/or :ok :addon/source-id :error nil?)
  "extracts the addon ID from the given `url`."
  [url ::sp/url]
  (->> url java.net.URL. .getPath (re-matches #"^/([^/]+/[^/]+)[/]?.*") rest first))

(defn-spec find-addon (s/or :ok :addon/summary, :error nil?)
  [source-id :addon/source-id]
  nil)
