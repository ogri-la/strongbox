(ns strongbox.test-helper
  (:require
   [orchestra.core :refer [defn-spec]]
   [clojure.spec.alpha :as s]
   [envvar.core :refer [env with-env]]
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [me.raynes.fs :as fs :refer [with-cwd]]
   [clj-http.fake :refer [with-global-fake-routes-in-isolation]]
   [strongbox
    [nfo :as nfo]
    [zip :as zip]
    [specs :as sp]
    [main :as main]
    [core :as core]
    [utils :as utils]]))

(def toc-data
  "local addon .toc file"
  {:name "everyaddon",
   :description "Does what no other addon does, slightly differently"
   :dirname "EveryAddon",
   :label "EveryAddon 1.2.3",
   :interface-version 70000,
   :installed-version "1.2.3"
   :toc/game-track :retail
   :supported-game-tracks [:retail]})

(def nfo-data
  {:installed-version "1.2.3"
   :name "everyaddon"
   :group-id "https://www.curseforge.com/wow/addons/everyaddon"
   :primary? true
   :installed-game-track :retail
   :source "curseforge"
   :source-id 1})

(def strongbox-installed-addon
  (merge toc-data nfo-data))

(def addon-summary
  "catalogue of summaries"
  {:label "EveryAddon",
   :name  "everyaddon",
   :description  "Does what no other addon does, slightly differently"
   :tag-list [:auction :data-broker :economy]
   :source "curseforge"
   :source-id 1
   :created-date  "2009-02-08T13:30:30Z",
   :updated-date  "2016-09-08T14:18:33Z",
   :url "https://www.example.org/wow/addons/everyaddon"
   :download-count 1})

(def matched?
  "was the toc data matched to an addon in the catalogue? (yes)"
  {:matched? true})

(def source-updates
  "updates to the addon data fetched from remote source"
  {:interface-version  70000,
   :download-url  "https://www.example.org/wow/addons/everyaddon/download/123456/file",
   :version  "1.2.3"
   :game-track :retail})

(def addon
  "final mooshed result"
  (merge toc-data addon-summary matched? source-updates))

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

(defn install-every-addon!
  "convenience, unzips the EveryAddon to the given `addon-dir` and writes the toc data"
  [& [more-nfo-data]]
  (zip/unzip-file (fixture-path "everyaddon--1-2-3.zip") (install-dir))
  (spit (utils/join (install-dir) "EveryAddon" nfo/nfo-filename) ;; "/tmp/something/EveryAddon/.strongbox.json"
        (utils/to-json (merge nfo-data more-nfo-data))))

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

(defmacro with-running-app*
  "like `with-running-app`, but with no UI at all.
  I wouldn't say the app is running without a UI attached and it's `start` fn called, though."
  [& form]
  `(with-running-app+opts {:ui nil} ~@form))

(defn-spec select-addon (s/nilable :addon/installed)
  "returns the first installed addon matching the given `group-id`"
  [group-id ::sp/group-id]
  (->> (core/get-state :installed-addon-list)
       (filter (fn [addon] (= (:group-id addon) group-id)))
       first))
