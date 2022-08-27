(ns strongbox.test-helper
  (:require
   [orchestra.core :refer [defn-spec]]
   [clojure.spec.alpha :as s]
   [envvar.core :refer [env with-env]]
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [me.raynes.fs :as fs :refer [with-cwd]]
   [me.raynes.fs.compression]
   [clj-http.fake :refer [with-global-fake-routes-in-isolation]]
   [clojure.pprint]
   [strongbox
    [nfo :as nfo]
    [toc :as toc]
    [addon :as addon]
    [zip :as zip]
    [specs :as sp]
    [main :as main]
    [core :as core]
    [utils :as utils]])
  (:import
   [java.util Random]
   [java.lang StringBuilder]))

;; utils used only during testing

(defn-spec cp ::sp/extant-file
  "copies `old-path` into `new-dir`, returning the new full path."
  [old-path ::sp/extant-file new-dir ::sp/extant-dir]
  (let [new-path (utils/join new-dir (fs/base-name old-path))]
    (fs/copy old-path new-path)
    new-path))

;; `rand-str2`, Istvan
;; - https://stackoverflow.com/questions/64034761/fast-random-string-generator-in-clojure
(defn short-unique-id
  ^String
  ([]
   (short-unique-id 8))
  ([^Long len]
   (let [leftLimit 97
         rightLimit 122
         random (java.util.Random.)
         stringBuilder (StringBuilder. len)
         diff (- rightLimit leftLimit)]
     (dotimes [_ len]
       (let [ch (char (.intValue ^Double (+ leftLimit (* (.nextFloat ^Random random) (+ diff 1)))))]
         (.append ^StringBuilder stringBuilder ch)))
     (.toString ^StringBuilder stringBuilder))))

;;

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
   :source-id 1
   :source-map-list [{:source "curseforge" :source-id 1}]})

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

(defn gen-addon-data
  "generates a complete set of addon data, including toc, nfo, summary, expanded (`:installable`) as well as
  a struct that can be used to create a zipfile that includes a .toc file.
  accepts a map with options:
  `:num-dirs` - the number of addons to generate.
  `:override` - a map of per-addon overrides, keyed by `i`, for example: {:override {1 {:version '5.4.3'}}}
  `:base-url` - the hostname used to generate a unique group ID."
  [& [{:keys [num-dirs override base-url]}]]
  (let [num-dirs (or num-dirs 1)
        override (or override {})

        nom (get override :label "EveryAddon")
        version (get override :version "1.2.3")
        description (get override :description "Does what no other addon does, slightly differently.")
        interface-version (get override :interface-version 70000)

        source "wowinterface"
        source-id (get override :source-id "999")
        game-track :retail
        group-id (get override :group-id)

        base-url (or base-url "https://example.org")

        ;; generate a single addon with a single zipfile directory
        -gen-addon
        (fn [i]
          (let [override-map (get override i {})
                i (get override-map :i i)
                i-label (clojure.pprint/cl-format nil "~@(~R~)" i) ;; 1 => One
                group-id (or group-id (get override-map :group-id) i-label)
                dirname (str nom i-label) ;; EveryAddonOne

                url (format "%s/%s" base-url dirname) ;; "https://example.org/EveryAddonOne"
                download-url (format "%s/%s/version/%s.zip" base-url dirname version) ;; "https://example.org/EveryAddonOne/version/1.2.3"
                normal-name (toc/normalise-name nom)

                ;; expected toc data after installation
                toc {:name normal-name
                     :label nom
                     :version version
                     :description description
                     :dirname dirname
                     :interface-version interface-version
                     :installed-version version
                     :supported-game-tracks [game-track]}

                ;; catalogue entry
                addon-summary {:url url
                               :name normal-name
                               :label nom
                               :tag-list []
                               :created-date "2015-05-15T15:15:15Z"
                               :updated-date "2016-06-16T16:16:16Z"
                               :download-count 0
                               :source source
                               :source-id source-id}

                ;; addon host source updates
                source-updates {:version version ;; no update
                                :download-url download-url
                                :game-track game-track}

                ;; valid nfo data we're just making up.
                nfo {:source source
                     :source-id source-id
                     :group-id group-id}

                ;; nfo data derived from the toc+addon-summary (catalogue entry) data.


                derived-nfo {:group-id url
                             :source-map-list [{:source "wowinterface", :source-id "999"}]
                             :installed-game-track game-track
                             :installed-version version

                             :source source
                             :source-id source-id
                             :name normal-name
                             :primary? (get override-map :primary? (= i 1)) ;; first addon is always the primary
                             }

                ;; zip file contents
                tocfile-name (str dirname "/" dirname ".toc") ;; EveryAddonOne/EveryAddonOne.toc
                tocfile (toc/gen-tocfile toc)
                luafile-name (str dirname "/" dirname ".lua") ;; EveryAddonOne/EveryAddonOne.lua
                luafile ""
                filename+content-list [[tocfile-name tocfile]
                                       [luafile-name luafile]]]

            {:toc toc
             :nfo nfo
             :derived-nfo derived-nfo
             :addon-summary addon-summary
             :source-updates source-updates
             :installable (merge addon-summary source-updates) ;; aka 'expanded'
             :installed toc
             :strongbox-installed (addon/merge-toc-nfo toc nfo)

             ;; single dir zip file contents
             :zip-contents filename+content-list}))

        generated (map -gen-addon (range 1 (inc num-dirs)))

        addon-data (mapv #(dissoc % :zip-contents) generated)

        ;; multi dir zip file contents
        output-filename (str nom "-" (short-unique-id) ".zip") ;; EveryAddon-fe3b9639.zip
        zipfile+contents [output-filename
                          (mapcat :zip-contents generated)]]

    [addon-data zipfile+contents]))

(defn mk-addon!
  "takes the `addon-data` created with `gen-addon` and writes a zipfile to the given `output-dir`.
  returns a pair of `(addon-data, output-dir+filename)`."
  [output-dir addon-data]
  (let [[addon-data [output-filename zipfile-contents]] addon-data
        output-path (utils/join output-dir output-filename)]
    (me.raynes.fs.compression/zip output-path zipfile-contents)
    [addon-data output-path]))

(defn gen-addon!
  "convenience. just like `gen-addon`, but also writes the generated zip file to the given `output-dir`."
  [output-dir & [opts]]
  (mk-addon! output-dir (gen-addon-data opts)))

;;
