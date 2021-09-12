(ns strongbox.wowinterface-api
  (:require
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [strongbox
    [utils :as utils]
    [specs :as sp]
    [http :as http]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]))

(def wowinterface-api "https://api.mmoui.com/v3/game/WOW")

(defn-spec extract-aid (s/nilable string?)
  "not sure what an 'aid' is, but if it's included in the download request it bypasses the 'approval pending' page."
  [url (s/nilable string?)]
  (when url
    (second (re-find #"aid=(\d+)" url))))

(defn-spec expand-summary (s/or :ok :addon/release-list, :error nil?)
  "given a summary, adds the remaining attributes that couldn't be gleaned from the summary page. one additional look-up per addon required"
  [addon-summary :addon/expandable, game-track ::sp/game-track]
  ;; this check is a little different to the others.
  ;; the `game-track-list` is stored in the catalogue for wowinterface because it's available at creation time.
  ;; however! we know this information isn't good and doesn't always match what we see on the website.
  ;; until wowinterface improve, and short of doing more scraping of html, this is the best we can do.
  (when-not (:game-track-list addon-summary)
    (error "given addon-summary has no game track list! please report this if you're not a developer.")
    (error addon-summary))

  ;; todo: this shouldn't be an 'if' if there is no 'else'
  (when (some #{game-track} (:game-track-list addon-summary))
    (let [url (str wowinterface-api "/filedetails/" (:source-id addon-summary) ".json")
          result-list (some-> url http/download http/sink-error utils/from-json)
          result (first result-list)]
      (when result
        (when (> (count result-list) 1)
          ;; has this happened before? can we find an example?
          (warn "wowinterface api returned more than one result for addon with id:" (:source-id addon-summary)))
        (let [sid (:source-id addon-summary)
              ;; rarely present. use it if found. actual value of `aid` not necessary, it seems to work when empty as well.
              aid (extract-aid (:UIDownload result))]
          [{:download-url (if aid
                            (format "https://cdn.wowinterface.com/downloads/getfile.php?id=%s&aid=%s" sid aid)
                            (format "https://cdn.wowinterface.com/downloads/getfile.php?id=%s" sid))
            :version (:UIVersion result)
            :game-track game-track}])))))

(defn-spec parse-user-string (s/or :ok :addon/source-id :error nil?)
  "extracts the addon ID from the given `url`"
  [url ::sp/url]
  (some->> url java.net.URL. .getPath (re-find #"/(?:info|download){1}(\d+)") second utils/to-int))
