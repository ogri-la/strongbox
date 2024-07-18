(ns strongbox.curseforge-api
  (:require
   [strongbox
    [specs :as sp]]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]))

(def curseforge-api "https://addons-ecs.forgesvc.net/api/v2")

(defn-spec api-url ::sp/url
  [path string?, & args (s/* any?)]
  (str curseforge-api (apply format path args)))

;; catalogue building

(defn-spec parse-user-string (s/or :ok ::sp/url, :error nil?)
  "extracts the addon name from the given `url` and returns a URL that would match a catalogue addon summary."
  [url ::sp/url]
  (when-let [nom (some->> url java.net.URL. .getPath (re-find #"^/wow/addons/(.[\w-]+)") rest first)]
    (str "https://www.curseforge.com/wow/addons/" nom)))
