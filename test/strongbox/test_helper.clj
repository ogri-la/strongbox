(ns strongbox.test-helper
  (:require
   [orchestra.core :refer [defn-spec]]
   [clojure.spec.alpha :as s]
   [envvar.core :refer [env with-env]]
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [me.raynes.fs :as fs :refer [with-cwd]]
   [clj-http.fake :refer [with-global-fake-routes-in-isolation]]
   [strongbox
    [specs :as sp]
    [main :as main]
    [core :as core]
    [utils :as utils]]))

(def fixture-dir (-> "test/fixtures" fs/absolute fs/normalized str))

(def helper-data-dir "data/strongbox")

(def helper-config-dir "config/strongbox")

(def helper-addon-dir "addons")

(defn install-dir
  "convenience. return path to an addon directory called 'addons', creating it if it doesn't exist."
  []
  (let [path (utils/join fs/*cwd* helper-addon-dir)]
    (when-not (fs/exists? path)
      (fs/mkdir path))
    (when @core/state
      ;; state is non-nil, assume app is running
      (core/set-addon-dir! path))
    path))

(defn install-dir-contents
  "convenience. returns the contents of the install-dir/addons-dir"
  []
  (->> (install-dir) fs/list-dir (map fs/base-name) sort))

(defn fixture-path
  [filename]
  (utils/join fixture-dir filename))

(defn slurp-fixture
  "reads the contents of the given fixture and deserialises the contents according the file extension"
  [filename]
  (let [path (fixture-path filename)
        contents (slurp path)]
    (case (fs/extension filename)
      ".edn" (read-string contents)
      ".json" (utils/from-json contents)

      contents)))

(defn fixture-tempcwd
  "each `deftest` is executed in a new and self-contained location, accessible as fs/*cwd*.
  `(testing ...` sections share the same fixture. beware of cache hits.
  if the app is started:
  * an empty catalogue is downloaded
  * fake strongbox version data is downloaded"
  [f]
  (let [;; for some reason, Macs symlink /var to /private/var and this needs to be resolved before comparison
        temp-dir-path (utils/expand-path (str (fs/temp-dir "strongbox-test.")))
        fake-routes {;; catalogue
                     ;; return dummy data. we can do this because the catalogue isn't loaded/parsed/validated
                     ;; until the UI (gui or cli) tells it to via a later call to `refresh`
                     "https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json"
                     {:get (fn [req] {:status 200 :body "{}"})}

                     "https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/full-catalogue.json"
                     {:get (fn [req] {:status 200 :body "{}"})}

                     ;; latest strongbox version
                     "https://api.github.com/repos/ogri-la/strongbox/releases/latest"
                     {:get (fn [req] {:status 200 :body "{\"tag_name\": \"0.0.0\"}"})}}]
    (try
      (debug "stopping application if it hasn't already been stopped")
      (main/stop)

      (with-global-fake-routes-in-isolation fake-routes
        (with-env [:xdg-data-home (utils/join temp-dir-path helper-data-dir)
                   :xdg-config-home (utils/join temp-dir-path helper-config-dir)]
          (with-cwd temp-dir-path
            (debug "created temp working directory" fs/*cwd*)
            (f))))
      (finally
        (debug "destroying temp working directory" temp-dir-path) ;; "with contents" (vec (file-seq fs/*cwd*)))
        (fs/delete-dir temp-dir-path)))))

(defmacro with-running-app+opts
  [opts & form]
  `(try
     (main/start (merge {:ui :cli} ~opts))
     ~@form
     (finally
       (main/stop))))

(defmacro with-running-app
  [& form]
  `(with-running-app+opts {:ui :cli} ~@form))

(defn-spec select-addon (s/nilable :addon/installed)
  "returns the first installed addon matching the given `group-id`"
  [group-id ::sp/group-id]
  (->> (core/get-state :installed-addon-list)
       (filter (fn [addon] (= (:group-id addon) group-id)))
       first))
