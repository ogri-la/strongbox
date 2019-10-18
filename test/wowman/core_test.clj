(ns wowman.core-test
  (:require
   [clojure.string :refer [starts-with? ends-with?]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
   [envvar.core :refer [with-env]]
   [me.raynes.fs :as fs]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [wowman
    [main :as main]
    [catalog :as catalog]
    [utils :as utils]
    [test-helper :as helper :refer [fixture-path temp-path data-dir with-running-app]]
    [core :as core]]))

(use-fixtures :each helper/fixture-tempcwd)

(deftest app-must-be-started
  (testing "application must be started before state can be accessed"
    (is (thrown? RuntimeException
                 (core/get-state :installed-addon-list)))))

(deftest handle-legacy-install-dir
  (testing ":install-dir in user config is converted correctly"
    (let [install-dir (str fs/*cwd*)
          cfg {:install-dir install-dir :addon-dir-list []}
          expected {:addon-dir-list [{:addon-dir install-dir :game-track "retail"}]}]
      (is (= expected (core/handle-legacy-install-dir cfg)))))

  (testing ":install-dir in user config is appended to existing list correctly"
    (let [install-dir (str fs/*cwd*)
          install-dir2 "/tmp"

          addon-dir1 {:addon-dir install-dir :game-track "retail"}
          addon-dir2 {:addon-dir install-dir2 :game-track "retail"}

          cfg {:install-dir install-dir2
               :addon-dir-list [addon-dir1]}

          expected {:addon-dir-list [addon-dir1 addon-dir2]}]
      (is (= expected (core/handle-legacy-install-dir cfg))))))

(deftest addon-dir-handling
  (let [app-state (core/start {})
        [dir1 dir2 dir3] (mapv (fn [path]
                                 (let [path (utils/join fs/*cwd* path)]
                                   (fs/mkdir path)
                                   path)) ["foo" "bar" "baz"])]
    (try

      ;; big long stateful test

      (testing "add-addon-dir! adds an addon dir with a default game track of 'retail'"
        (core/add-addon-dir! dir1 "retail")
        (is (= [{:addon-dir dir1 :game-track "retail"}] (core/get-state :cfg :addon-dir-list))))

      (testing "add-addon-dir! idempotence"
        (core/add-addon-dir! dir1 "retail")
        (is (= [{:addon-dir dir1 :game-track "retail"}] (core/get-state :cfg :addon-dir-list))))

      (testing "add-addon-dir! just adds the dir, doesn't set it as selected"
        (is (= nil (core/get-state :selected-addon-dir))))

      (testing "set-addon-dir! sets the addon directory as selected and is also idempotent"
        (core/set-addon-dir! dir1)
        (is (= [{:addon-dir dir1 :game-track "retail"}] (core/get-state :cfg :addon-dir-list)))
        (is (= dir1 (core/get-state :selected-addon-dir))))

      (testing "remove-addon-dir! without args removes the currently selected addon-dir and ensures it's no longer selected"
        (core/remove-addon-dir!)
        (is (= [] (core/get-state :cfg :addon-dir-list)))
        (is (= nil (core/get-state :selected-addon-dir))))

      (testing "remove-addon-dir! without args won't do anything stupid when there is nothing to remove"
        (core/remove-addon-dir!))

      (testing "remove-addon-dir! with args is idempotent"
        (core/set-addon-dir! dir1)
        (core/set-addon-dir! dir2)
        (core/remove-addon-dir! dir2)
        (core/remove-addon-dir! dir2) ;; repeat
        (is (= dir1 (core/get-state :selected-addon-dir)))
        (is (= [{:addon-dir dir1 :game-track "retail"}] (core/get-state :cfg :addon-dir-list))))

      (testing "addon-dir-map, without args, returns the currently selected addon-dir"
        (is (= {:addon-dir dir1 :game-track "retail"} (core/addon-dir-map)))
        (core/set-addon-dir! dir2)
        (is (= {:addon-dir dir2 :game-track "retail"} (core/addon-dir-map)))
        (is (= {:addon-dir dir1 :game-track "retail"} (core/addon-dir-map dir1))))

      (testing "addon-dir-map returns nil if map cannot be found"
        (is (= nil (core/addon-dir-map dir3))))

      (testing "set-game-track! changes the game track of the given addon dir"
        (core/set-game-track! "classic" dir1)
        (is (= {:addon-dir dir1 :game-track "classic"} (core/addon-dir-map dir1))))

      (testing "set-game-track! without addon-dir, changes the game track of the currently selected addon dir"
        (core/set-game-track! "classic")
        (is (= {:addon-dir dir2 :game-track "classic"} (core/addon-dir-map dir2))))

      (finally
        (core/stop app-state)))))

(deftest catalog
  (let [app-state (core/start {})
        [short-catalog full-catalog] (->> core/-state-template :catalog-source-list (take 2))]
    (try
      (testing "by default we have at least 2 (short and full composite) + N (source) catalogs available to us"
        (is (> (count (core/get-state :catalog-source-list)) 2)))

      (testing "core/get-catalog-source returns the requested catalog if found"
        (is (= short-catalog (core/get-catalog-source :short))))

      (testing "core/get-catalog-source, without args, returns the currently selected catalog"
        (is (= short-catalog (core/get-catalog-source))))

      (testing "core/get-catalog-source returns nil if it can't find the requested catalog"
        (is (= nil (core/get-catalog-source :foo))))

      (testing "core/set-catalog-source! always returns nil, even when it successfully completes"
        (is (= nil (core/set-catalog-source! :foo)))
        (is (= short-catalog (core/get-catalog-source)))

        (is (= nil (core/set-catalog-source! :full)))
        (is (= full-catalog (core/get-catalog-source))))

      (testing "core/catalog-local-path returns the expected path to the catalog file on the filesystem"
        (is (= (utils/join fs/*cwd* data-dir "short-catalog.json") (core/catalog-local-path short-catalog)))
        (is (= (utils/join fs/*cwd* data-dir "full-catalog.json") (core/catalog-local-path full-catalog))))

      (testing "core/find-catalog-local-path just needs a catalog :name"
        (is (= (utils/join fs/*cwd* data-dir "short-catalog.json") (core/find-catalog-local-path :short))))

      (testing "core/find-catalog-local-path returns nil if the given catalog can't be found"
        (is (= nil (core/find-catalog-local-path :foo))))

      (finally
        (core/stop app-state)))))

(deftest paths
  (let [app-state (core/start {})]
    (try
      (testing "all path keys are using a known suffix"
        (doseq [key (keys (core/paths))]
          (is (some #{"dir" "file" "uri"} (clojure.string/split (name key) #"\-")))))

      (testing "all paths to files and directories are absolute"
        (let [files+dirs (filter (fn [[k v]] (or (ends-with? k "-dir")
                                                 (ends-with? k "-file")))
                                 (core/paths))]
          (doseq [[key path] files+dirs]
            (is (-> path (starts-with? "/")) (format "path %s is not absolute: %s" key path)))))

      (testing "all remote paths are using https"
        (let [remote-paths (filter (fn [[k v]] (ends-with? k "-uri")) (core/paths))]
          (doseq [[key path] remote-paths]
            (is (-> path (starts-with? "https://")) (format "remote path %s is not using HTTPS: %s" key path)))))

      (comment
        "what exactly am I testing here again? this path overriding has plenty of coverage already. see test_helper.clj"
        (testing "XDG data paths can be overridden with environment variables"
          (with-env [:xdg-data-home "/foo", :xdg-config-home "/bar"]
            (is (= "/foo" (:data-dir (core/paths))))
            (is (= "/bar" (:config-dir (core/paths)))))))

      (testing "paths that cannot be written to raise a runtime exception"
        (with-env [:xdg-data-home "/foo", :xdg-config-home "/bar"]
          (core/stop app-state)
          (is (thrown? RuntimeException (core/start {})))))

      (finally
        (core/stop app-state)))))

(deftest determine-primary-subdir
  (testing "basic failure cases"
    (is (= nil (core/determine-primary-subdir [])))
    (is (= nil (core/determine-primary-subdir [{}])))
    (is (= nil (core/determine-primary-subdir [{:path nil}])))
    (is (= nil (core/determine-primary-subdir [{:path ""}])))
    (is (= nil (core/determine-primary-subdir [{:path "Foo/"} {:path "Bar/"}]))))

  (testing "multiple paths, different lengths, shortest is not a common prefix"
    (is (= nil (core/determine-primary-subdir [{:path "z"} {:path "az"}]))))

  (testing "basic success cases"
    (is (= {:path "Foo/"} (core/determine-primary-subdir [{:path "Foo/"}])))
    (is (= {:path "Foo/"} (core/determine-primary-subdir [{:path "Foo-Bar/"} {:path "Foo/"}]))))

  (testing "actual case with HealBot"
    (let [fixture [{:path "HealBot/"} {:path "HealBot_br/"} {:path "HealBot_cn/"} {:path "HealBot_de/"}
                   {:path "HealBot_es/"} {:path "HealBot_fr/"} {:path "HealBot_gr/"} {:path "HealBot_hu/"}
                   {:path "HealBot_it/"} {:path "HealBot_kr/"} {:path "HealBot_ru/"} {:path "HealBot_Tips/"} {:path "HealBot_tw/"}]
          expected {:path "HealBot/"}]
      (is (= expected (core/determine-primary-subdir fixture)))))

  (testing "only unique values are compared"
    (is (= {:path "Foo/"} (core/determine-primary-subdir [{:path "Foo/"} {:path "Foo/"} {:path "Foo/"} {:path "Foo-Bar/"}]))))

  (testing "original value is preserved despite modification for comparison"
    (is (= {:path "Foo"} (core/determine-primary-subdir [{:path "Foo"}])))))

(deftest configure
  (testing "called with no overrides gives us whatever is in the state template"
    (let [expected (:cfg core/-state-template)
          file-opts {}
          cli-opts {}]
      (is (= expected (core/configure file-opts cli-opts)))))

  (testing "file overrides are preserved and foreign keys are removed"
    (let [cli-opts {}
          file-opts {:foo "bar" ;; unknown
                     :debug? true}
          expected (assoc (:cfg core/-state-template) :debug? true)]
      (is (= expected (core/configure file-opts cli-opts)))))

  (testing "cli overrides are preserved and foreign keys are removed"
    (let [cli-opts {:foo "bar"
                    :debug? true}
          file-opts {}
          expected (assoc (:cfg core/-state-template) :debug? true)]
      (is (= expected (core/configure file-opts cli-opts)))))

  (testing "cli overrides file overrides"
    (let [cli-opts {:debug? true}
          file-opts {:debug? false}
          expected (assoc (:cfg core/-state-template) :debug? true)]
      (is (= expected (core/configure file-opts cli-opts))))))

(deftest export-installed-addon-list
  (testing "exported data looks as expected"
    (let [addon-list (read-string (slurp "test/fixtures/export--installed-addons-list.edn"))
          output-path (temp-path "exports.json")
          _ (core/export-installed-addon-list output-path addon-list)
          expected [{:name "adibags" :source "curseforge"}
                    {:name "noname"} ;; an addon whose name is not present in the catalog (umatched)
                    {:name "carbonite" :source "curseforge"}]]
      (is (= expected (utils/load-json-file output-path))))))

(deftest import-exported-addon-list-file
  (testing "an export can be imported"
    (let [;; modified curseforge addon files to generate fake links
          every-addon-zip-file (fixture-path "everyaddon--1-2-3.zip")
          every-other-addon-zip-file (fixture-path "everyotheraddon--4-5-6.zip")

          every-addon-api (slurp (fixture-path "curseforge-api-addon--everyaddon.json"))
          every-other-addon-api (slurp (fixture-path "curseforge-api-addon--everyotheraddon.json"))

          addon-summary-list (utils/load-json-file (fixture-path "import--dummy-catalog.json"))

          fake-routes {;; catalog
                       "https://raw.githubusercontent.com/ogri-la/wowman-data/master/short-catalog.json"
                       {:get (fn [req] {:status 200 :body (utils/to-json {:addon-summary-list addon-summary-list})})}

                       ;; every-addon
                       "https://addons-ecs.forgesvc.net/api/v2/addon/1"
                       {:get (fn [req] {:status 200 :body every-addon-api})}

                       ;; ... it's zip file
                       "https://edge.forgecdn.net/files/1/1/EveryAddon.zip"
                       {:get (fn [req] {:status 200 :body (utils/file-to-lazy-byte-array every-addon-zip-file)})}

                       ;; every-other-addon
                       "https://addons-ecs.forgesvc.net/api/v2/addon/2"
                       {:get (fn [req] {:status 200 :body every-other-addon-api})}

                       ;; ... it's zip file
                       "https://edge.forgecdn.net/files/2/2/EveryOtherAddon.zip"
                       {:get (fn [req] {:status 200 :body (utils/file-to-lazy-byte-array every-other-addon-zip-file)})}}]
      (with-fake-routes-in-isolation fake-routes
        (try
          (main/start {:ui :cli, :install-dir (str fs/*cwd*)})

          (let [;; our list of addons to import
                output-path (fixture-path "import--exports.json")

                expected [{:description "Does what no other addon does, slightly differently",
                           :category-list ["Bags & Inventory"],
                           :update? false,
                           :updated-date "2019-06-26T01:21:39Z",
                           :group-id "https://www.curseforge.com/wow/addons/everyaddon",
                           :installed-version "v8.2.0-v1.13.2-7135.139",
                           :name "everyaddon",
                           :source "curseforge",
                           :interface-version 80000,
                           :download-uri "https://edge.forgecdn.net/files/1/1/EveryAddon.zip",
                           :alt-name "everyaddon",
                           :label "EveryAddon",
                           :download-count 3000000,
                           :source-id 1,
                           :uri "https://www.curseforge.com/wow/addons/everyaddon",
                           :version "v8.2.0-v1.13.2-7135.139",
                           :dirname "EveryAddon",
                           :primary? true,
                           :matched? true}

                          {:description "Does what every addon does, just better",
                           :category-list ["Professions" "Map & Minimap"],
                           :update? false,
                           :updated-date "2019-07-03T07:11:47Z",
                           :group-id "https://www.curseforge.com/wow/addons/everyotheraddon",
                           :installed-version "v8.2.0-v1.13.2-7135.139",
                           :name "everyotheraddon",
                           :source "curseforge",
                           :interface-version 80200,
                           :download-uri "https://edge.forgecdn.net/files/2/2/EveryOtherAddon.zip",
                           :alt-name "everyotheraddon",
                           :label "Every Other Addon",
                           :download-count 5400000,
                           :source-id 2,
                           :uri "https://www.curseforge.com/wow/addons/everyotheraddon",
                           :version "v8.2.0-v1.13.2-7135.139",
                           :dirname "EveryOtherAddon",
                           :primary? true,
                           :matched? true}]]

            (core/import-exported-file output-path)
            (core/refresh) ;; re-read the installation directory
            (is (= expected (core/get-state :installed-addon-list))))

          (finally
            (main/stop)))))))

(deftest check-for-update
  (testing "the key :update? is set on an addon when there is a difference between the installed version of an addon and it's matching catalog verison"

    (let [;; we start off with a list of these called a catalog. it's downloaded from github
          catalog {:category-list ["Auction House & Vendors"],
                   :download-count 1
                   :label "Every Addon"
                   :name "every-addon",
                   :source "curseforge",
                   :source-id 0
                   :updated-date "2012-09-20T05:32:00Z",
                   :uri "https://www.curseforge.com/wow/addons/every-addon"}

          ;; this is subset of the data the remote addon host (curseforge in this case) serves us
          api-result {:latestFiles [{:downloadUrl "https://example.org/foo"
                                     :displayName "v8.10.00"
                                     :gameVersionFlavor "retail",
                                     :fileDate "2001-01-03T00:00:00.000Z",
                                     :releaseType 1,
                                     :exposeAsAlternative nil}]}
          alt-api-result (assoc-in api-result [:latestFiles 0 :displayName] "v8.20.00")

          fake-routes {;; catalog
                       "https://raw.githubusercontent.com/ogri-la/wowman-data/master/short-catalog.json"
                       {:get (fn [req] {:status 200 :body (utils/to-json {:addon-summary-list [catalog]})})}

                       ;; every-addon
                       "https://addons-ecs.forgesvc.net/api/v2/addon/0"
                       {:get (fn [req] {:status 200 :body (utils/to-json api-result)})}

                       "https://addons-ecs.forgesvc.net/api/v2/addon/1"
                       {:get (fn [req] {:status 200 :body (utils/to-json alt-api-result)})}}]

      (with-fake-routes-in-isolation fake-routes
        (try
          ;; init the app, which downloads the catalog and loads the db
          (main/start {:ui :cli, :install-dir (str fs/*cwd*)})

          (let [;; a collection of these are scraped from the installed addons
                toc {:name "every-addon"
                     :label "Every Addon"
                     :description "foo"
                     :dirname "EveryAddon"
                     :interface-version 70000
                     :installed-version "v8.10.00"}

                ;; and optionally these from .wowman.json if we installed the addon
                nfo {:installed-version "v8.10.00",
                     :name "every-addon",
                     :group-id "doesntmatter"
                     :primary? true,
                     :source "curseforge"
                     :source-id 0}

                ;; the nfo data is simply merged over the top of the scraped toc data
                toc (merge toc nfo)

                ;; we then attempt to match this 'toc+nfo' to an addon in the catalog
                catalog-match (core/-db-match-installed-addons-with-catalog [toc])

                ;; this is a m:n match and we typically get back heaps of results
                ;; in this case we have a catalog of 1 and are not interested in how the addon was matched (:final)
                toc-addon (-> catalog-match first :final) ;; :update? will be false
                alt-toc-addon (assoc toc-addon :source-id 1) ;; :update? will be true

                ;; and what we 'expand' that data into
                api-xform {:download-uri "https://example.org/foo",
                           :version "v8.10.00"}
                alt-api-xform (assoc api-xform :version "v8.20.00")

                ;; after calling `check-for-update` we expect the result to be the merged sum of the below parts
                expected (merge toc-addon api-xform {:update? false})
                alt-expected (merge alt-toc-addon alt-api-xform {:update? true})]

            (is (= expected (core/check-for-update toc-addon)))
            (is (= alt-expected (core/check-for-update alt-toc-addon))))

          (finally
            (main/stop)))))))

;; todo: install classic addon into retail game track

(deftest db-row-wrangling
  (testing "converting a tab separated list back into an actual list works as expected"
    (let [cases [[nil []]
                 ["" []]
                 ["|" []]
                 ["foo" ["foo"]]
                 ["bar|baz" ["bar" "baz"]]]]

      (doseq [[given expected] cases]
        (is (= expected (core/db-split-category-list given))))))

  (testing "game track fields are turned back into a list"
    (let [cases [[{} {}]
                 [{:retail-track true} {:game-track-list ["retail"]}]
                 [{:classic-track true} {:game-track-list ["classic"]}]
                 [{:retail-track true, :classic-track true} {:game-track-list ["retail" "classic"]}]
                 [{:retail-track false, :classic-track true} {:game-track-list ["classic"]}]
                 [{:retail-track false, :classic-track false} {}]

                 ;; order is deterministic
                 [{:classic-track true, :retail-track true} {:game-track-list ["retail" "classic"]}]]]
      (doseq [[given expected] cases]
        (is (= expected (core/db-gen-game-track-list given)))))))

;; legacy

;; local addon .toc file
(def toc
  {:name "everyaddon",
   :description "Does what no other addon does, slightly differently"
   :dirname "EveryAddon",
   :label "EveryAddon 1.2.3",
   :interface-version 70000,
   :installed-version "1.2.3"})

;; catalog of summaries
(def addon-summary
  {:label "EveryAddon",
   :name  "everyaddon",
   :alt-name "everyaddon"
   :description  "Does what no other addon does, slightly differently"
   :category-list  ["Auction & Economy", "Data Broker"],
   :source "curseforge"
   :source-id 1
   :created-date  "2009-02-08T13:30:30Z",
   :updated-date  "2016-09-08T14:18:33Z",
   :uri "https://www.example.org/wow/addons/everyaddon"})

;; remote addon detail
(def addon
  (merge addon-summary
         {:download-count 1
          :interface-version  70000,
          :download-uri  "https://www.example.org/wow/addons/everyaddon/download/123456/file",
          :donation-uri nil,
          :version  "1.2.3"}))

(deftest install-addon
  (testing "installing an addon"
    (let [install-dir (str fs/*cwd*)
          ;; move dummy addon file into place so there is no cache miss
          fname (core/downloaded-addon-fname (:name addon) (:version addon))
          _ (utils/cp (fixture-path fname) install-dir)
          file-list (core/install-addon addon install-dir)]

      (testing "addon directory created, single file written (.wowman.json nfo file)"
        (is (= (count file-list) 1))
        (is (fs/exists? (first file-list)))))))

(deftest install-bad-addon
  (testing "installing a bad addon"
    (let [install-dir (str fs/*cwd*)
          fname (core/downloaded-addon-fname (:name addon) (:version addon))]
      (fs/copy (fixture-path "bad-truncated.zip") (utils/join install-dir fname)) ;; hoho, so evil
      (is (= (core/install-addon addon install-dir) nil))
      (is (= (count (fs/list-dir install-dir)) 0))))) ;; bad zip file deleted

(deftest re-download-catalog-on-bad-data
  (testing "catalog data is re-downloaded if it can't be read"
    (let [app-state (core/start {})
          ;; overrides the fake route in test_helper.clj
          fake-routes {"https://raw.githubusercontent.com/ogri-la/wowman-data/master/short-catalog.json"
                       {:get (fn [req] {:status 200 :body (slurp (fixture-path "dummy-catalog--single-entry.json"))})}}]
      (try
        (core/refresh)

        ;; this is the guard to the `db-load-catalog` fn
        ;; catalog fixture in test-helper is an empty map, this should always return false
        (is (not (core/db-catalog-loaded?)))

        ;; empty the file. quickest way to bad json
        (-> (core/get-catalog-source) core/catalog-local-path (spit ""))

        ;; the catalog will be re-requested, this time we've swapped out the fixture with one with a single entry
        (with-fake-routes-in-isolation fake-routes
          (core/db-load-catalog))

        (is (core/db-catalog-loaded?))

        (finally
          (core/stop app-state))))))

(deftest re-download-catalog-on-bad-data-2
  (testing "`db-load-catalog` doesn't fail catastrophically when re-downloaded json is still bad"
    (let [app-state (core/start {})
          ;; overrides the fake route in test_helper.clj
          fake-routes {"https://raw.githubusercontent.com/ogri-la/wowman-data/master/short-catalog.json"
                       {:get (fn [req] {:status 200 :body "borked json"})}}]
      (try
        (core/refresh)

        ;; this is the guard to the `db-load-catalog` fn
        ;; catalog fixture in test-helper is an empty map, this should always return false
        (is (not (core/db-catalog-loaded?)))

        ;; empty the file. quickest way to bad json
        (-> (core/get-catalog-source) core/catalog-local-path (spit ""))

        ;; the catalog will be re-requested, this time the remote file is also corrupt
        (with-fake-routes-in-isolation fake-routes
          (core/db-load-catalog))

        (is (not (core/db-catalog-loaded?)))

        (finally
          (core/stop app-state))))))

;;

(deftest add-user-addon-to-user-catalog
  (let [user-addon {:uri "https://github.com/Aviana/HealComm"
                    :updated-date "2019-10-09T17:40:01Z"
                    :source "github"
                    :source-id "Aviana/HealComm"
                    :label "HealComm"
                    :name "healcomm"
                    :download-count 30946
                    :category-list []}

        ;; this test is fubar and broken. 
        ;; we need to go back and fix the :source property in wowinterface and curseforge
        ;; then revisit the catalog merging to make it general purpose
        ;; perhaps push the :age property into the database loading and out of the catalog
        expected (merge (catalog/new-catalog [])
                        {;; hack, catalog/format-catalog-data orders the addon summary make them uncomparable
                         :total 1
                         :addon-summary-list [(merge user-addon
                                                     {;; properties catalog/-merge-catalogs adds. revisit
                                                      :age "new"
                                                      :alt-name "healcomm"})]})
        ]
    (testing "user addon is successfully added to the user catalog, creating it if it doesn't exist"
      (with-running-app
        (core/add-user-addon! user-addon)
        (is (= expected (catalog/read-catalog (core/paths :user-catalog-file))))))))
