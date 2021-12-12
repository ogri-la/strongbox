(ns strongbox.core-test
  (:require
   [clojure.string :refer [starts-with? ends-with?]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clj-http.fake :refer [with-global-fake-routes-in-isolation]]
   [envvar.core :refer [with-env]]
   [me.raynes.fs :as fs]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox.ui.cli :as cli]
   [strongbox
    [addon :as addon :refer [downloaded-addon-fname]]
    [db :as db]
    [logging :as logging]
    [zip :as zip]
    [main :as main]
    [nfo :as nfo]
    [catalogue :as catalogue]
    [utils :as utils]
    [config :as config]
    [test-helper :as helper :refer [fixture-path slurp-fixture helper-data-dir with-running-app+opts with-running-app with-running-app*]]
    [core :as core]]))

(use-fixtures :each helper/fixture-tempcwd)

(deftest app-must-be-started
  (testing "application must be started before state can be accessed"
    (is (thrown? RuntimeException
                 (core/get-state :installed-addon-list)))))

(deftest addon-dir-handling
  (let [[dir1 dir2 dir3 dir4] (mapv (fn [path]
                                      (let [path (utils/join fs/*cwd* path)]
                                        (fs/mkdir path)
                                        path)) ["foo" "bar" "baz" "_classic_"])]
    (with-running-app

      ;; big long stateful test

      (testing "fetching the addon-dir map data, without args, without addon directories, returns nil"
        (is (nil? (core/addon-dir-map))))

      (testing "setting the game track, without args, without addon directories, does nothing"
        (is (nil? (core/set-game-track! :retail))))

      (testing "add-addon-dir! adds an addon dir with a default game track of 'retail'"
        (core/add-addon-dir! dir1 :retail)
        (is (= [{:addon-dir dir1 :game-track :retail :strict? true}] (core/get-state :cfg :addon-dir-list))))

      (testing "add-addon-dir! idempotence"
        (core/add-addon-dir! dir1 :retail)
        (is (= [{:addon-dir dir1 :game-track :retail :strict? true}] (core/get-state :cfg :addon-dir-list))))

      (testing "add-addon-dir! just adds the dir, doesn't set it as selected"
        (is (= nil (core/selected-addon-dir))))

      (testing "set-addon-dir! sets the addon directory as selected and is also idempotent"
        (core/set-addon-dir! dir1)
        (is (= [{:addon-dir dir1 :game-track :retail :strict? true}] (core/get-state :cfg :addon-dir-list)))
        (is (= dir1 (core/selected-addon-dir))))

      (testing "remove-addon-dir! without args removes the currently selected addon-dir and ensures it's no longer selected"
        (core/remove-addon-dir!)
        (is (= [] (core/get-state :cfg :addon-dir-list)))
        (is (= nil (core/selected-addon-dir))))

      (testing "remove-addon-dir! without args won't do anything stupid when there is nothing to remove"
        (core/remove-addon-dir!))

      (testing "remove-addon-dir! with args is idempotent"
        (core/set-addon-dir! dir1)
        (core/set-addon-dir! dir2)
        (core/remove-addon-dir! dir2)
        (core/remove-addon-dir! dir2) ;; repeat
        (is (= dir1 (core/selected-addon-dir)))
        (is (= [{:addon-dir dir1 :game-track :retail :strict? true}] (core/get-state :cfg :addon-dir-list))))

      (testing "addon-dir-map, without args, returns the currently selected addon-dir"
        (is (= {:addon-dir dir1 :game-track :retail :strict? true} (core/addon-dir-map)))
        (core/set-addon-dir! dir2)
        (is (= {:addon-dir dir2 :game-track :retail :strict? true} (core/addon-dir-map)))
        (is (= {:addon-dir dir1 :game-track :retail :strict? true} (core/addon-dir-map dir1))))

      (testing "addon-dir-map returns nil if map cannot be found"
        (is (= nil (core/addon-dir-map dir3))))

      (testing "set-game-track! changes the game track of the given addon dir"
        (core/set-game-track! :classic dir1)
        (is (= {:addon-dir dir1 :game-track :classic :strict? true} (core/addon-dir-map dir1))))

      (testing "set-game-track! without addon-dir, changes the game track of the currently selected addon dir"
        (core/set-game-track! :classic)
        (is (= {:addon-dir dir2 :game-track :classic :strict? true} (core/addon-dir-map dir2))))

      (testing "set-addon-dir! changes default game-track to 'classic' if '_classic_' detected in addon dir name"
        (core/set-addon-dir! dir4)
        (is (= {:addon-dir dir4 :game-track :classic :strict? true} (core/addon-dir-map dir4))))

      (testing "set-addon-dir! resets the list of selected addons"
        (swap! core/state update-in [:selected-addon-list] conj "foo!")
        (is (= (core/get-state :selected-addon-list) ["foo!"]))
        (core/set-addon-dir! dir4)
        (is (= (core/get-state :selected-addon-list) []))))))

(deftest game-strictness
  (with-running-app
    (testing "default strictness for a running app with no addon directory selected is nil"
      (is (nil? (core/get-game-track-strictness))))

    (testing "setting strictness with no addon directory selected doesn't change a thing"
      (core/set-game-track-strictness! false)
      (is (nil? (core/get-game-track-strictness))))

    (helper/install-dir) ;; adds and selects an addon dir

    (testing "default strictness for new addon directories is 'strict' (true)"
      (is (true? (core/get-game-track-strictness))))

    (testing "strictness can be toggled to 'lenient' (false)"
      (core/set-game-track-strictness! false)
      (is (false? (core/get-game-track-strictness)))
      (core/set-game-track-strictness! true)
      (is (true? (core/get-game-track-strictness))))))

(deftest catalogue
  (let [[short-catalogue full-catalogue] (take 2 config/-default-catalogue-list)]
    (with-running-app
      (testing "under regular circumstances we have at least two catalogues available to us (short and full)"
        (is (> (count (core/get-state :cfg :catalogue-location-list)) 2)))

      (testing "core/get-catalogue-location returns the requested catalogue if found"
        (is (= short-catalogue (core/get-catalogue-location :short))))

      (testing "core/get-catalogue-location, without args, returns the currently selected catalogue"
        (is (= short-catalogue (core/get-catalogue-location))))

      (testing "core/get-catalogue-location returns nil if it can't find the requested catalogue"
        (is (= nil (core/get-catalogue-location :foo))))

      (testing "core/set-catalogue-location! always returns nil, even when it successfully completes"
        (is (= nil (core/set-catalogue-location! :foo)))
        (is (= short-catalogue (core/get-catalogue-location)))

        (is (= nil (core/set-catalogue-location! :full)))
        (is (= full-catalogue (core/get-catalogue-location))))

      (testing "core/catalogue-local-path returns the expected path to the catalogue file on the filesystem"
        (is (= (utils/join fs/*cwd* helper-data-dir "short-catalogue.json") (core/catalogue-local-path short-catalogue)))
        (is (= (utils/join fs/*cwd* helper-data-dir "full-catalogue.json") (core/catalogue-local-path full-catalogue))))

      (testing "core/find-catalogue-local-path just needs a catalogue :name"
        (is (= (utils/join fs/*cwd* helper-data-dir "short-catalogue.json") (core/find-catalogue-local-path :short))))

      (testing "core/find-catalogue-local-path returns nil if the given catalogue can't be found"
        (is (= nil (core/find-catalogue-local-path :foo)))))))

(deftest paths
  (with-running-app
    (testing "all path keys are using a known suffix"
      (doseq [key (keys (core/paths))]
        (is (some #{"dir" "file" "url"} (clojure.string/split (name key) #"\-")))))

    (testing "all paths to files and directories are absolute"
      (let [files+dirs (filter (fn [[k _]] (or (ends-with? k "-dir")
                                               (ends-with? k "-file")))
                               (core/paths))]
        (doseq [[key path] files+dirs]
          (is (-> path (starts-with? "/")) (format "path %s is not absolute: %s" key path)))))

    (testing "all remote paths are using https"
      (let [remote-paths (filter (fn [[k _]] (ends-with? k "-url")) (core/paths))]
        (doseq [[key path] remote-paths]
          (is (-> path (starts-with? "https://")) (format "remote path %s is not using HTTPS: %s" key path))))))

  (testing "paths that cannot be written to raise a runtime exception"
    (let [app-state (core/start {})]
      (with-env [:xdg-data-home "/foo", :xdg-config-home "/bar"]
        (core/stop app-state)
        (is (thrown? RuntimeException (core/start {})))))))

(deftest generate-path-map
  (testing "XDG data paths can be overridden with environment variables"
    (with-env [:xdg-data-home "/foo", :xdg-config-home "/home/layday/.config"]
      (let [paths (core/generate-path-map)]
        (is (= "/foo/strongbox" (:data-dir paths)))
        (is (= "/home/layday/.config/strongbox" (:config-dir paths))))))

  (testing "XDG data paths are ignored if present, but empty"
    (with-env [:xdg-data-home "", :xdg-config-home ""]
      (let [paths (core/generate-path-map)
            expected-config-dir (-> core/default-config-dir utils/expand-path)
            expected-data-dir (-> core/default-data-dir utils/expand-path)]
        (is (= expected-data-dir (:data-dir paths)))
        (is (= expected-config-dir (:config-dir paths)))))))

(deftest export-installed-addon-list
  (testing "exported addon data is correct"
    (let [addon-list (slurp-fixture "import-export--installed-addons-list.edn")
          expected [{:name "adibags" :source "curseforge" :game-track :retail}
                    {:name "noname"} ;; an addon whose name is not present in the catalogue (umatched)
                    {:name "carbonite" :source "curseforge" :game-track :retail}]]
      (is (= expected (core/export-installed-addon-list addon-list)))))

  (testing "export ignores ignored addons"
    (let [addon-list (vec (slurp-fixture "import-export--installed-addons-list.edn"))
          addon-list (assoc-in addon-list [0 :ignore?] true)
          expected [{:name "noname"} ;; an addon whose name is not present in the catalogue (umatched)
                    {:name "carbonite" :source "curseforge" :game-track :retail}]]
      (is (= expected (core/export-installed-addon-list addon-list))))))

(deftest export-catalogue-addon-list--v1
  (testing "exported addon list data from v1 catalogue data is correct"
    (let [catalogue (catalogue/read-catalogue (fixture-path "import-export--user-catalogue-v1.json"))
          expected (slurp-fixture "import-export--user-catalogue-export.json")]
      (is (= expected (core/export-catalogue-addon-list catalogue))))))

(deftest export-catalogue-addon-list--v2
  (testing "exported addon list data from v2 catalogue data is correct"
    (let [catalogue (catalogue/read-catalogue (fixture-path "import-export--user-catalogue-v2.json"))
          expected (slurp-fixture "import-export--user-catalogue-export.json")]
      (is (= expected (core/export-catalogue-addon-list catalogue))))))

(deftest import-exported-addon-list-file-v1
  (testing "an export can be imported"
    (let [;; modified curseforge addon files to generate fake links
          every-addon-zip-file (fixture-path "everyaddon--1-2-3.zip")
          every-other-addon-zip-file (fixture-path "everyotheraddon--4-5-6.zip")

          every-addon-api (slurp (fixture-path "curseforge-api-addon--everyaddon.json"))
          every-other-addon-api (slurp (fixture-path "curseforge-api-addon--everyotheraddon.json"))

          dummy-catalogue (slurp (fixture-path "import-export--dummy-catalogue.json"))

          fake-routes {;; catalogue
                       "https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json"
                       {:get (fn [req] {:status 200 :body dummy-catalogue})}

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
      (with-global-fake-routes-in-isolation fake-routes
        (with-running-app
          (core/set-addon-dir! (str fs/*cwd*))

          (let [;; our list of addons to import
                output-path (fixture-path "import-export--export-v1.json")

                expected [{:created-date "2010-05-07T18:48:16Z",
                           :description "Does what no other addon does, slightly differently",
                           :tag-list [:bags :inventory]
                           :update? false,
                           :updated-date "2019-06-26T01:21:39Z",
                           :group-id "https://www.curseforge.com/wow/addons/everyaddon",
                           :installed-version "v8.2.0-v1.13.2-7135.139",
                           :installed-game-track :retail
                           :game-track :retail
                           :name "everyaddon",
                           :source "curseforge",
                           :interface-version 80000,
                           :supported-game-tracks [:retail]
                           :download-url "https://edge.forgecdn.net/files/1/1/EveryAddon.zip",
                           :label "EveryAddon",
                           :download-count 3000000,
                           :source-id 1,
                           :url "https://www.curseforge.com/wow/addons/everyaddon",
                           :version "v8.2.0-v1.13.2-7135.139",
                           :dirname "EveryAddon",
                           :primary? true,
                           :matched? true
                           :release-list [{:download-url "https://edge.forgecdn.net/files/1/1/EveryAddon.zip",
                                           :game-track :retail,
                                           :interface-version 80000,
                                           :release-label "[WoW 8.0.1] EveryAddon-v8.2.0-v1.13.2-7135.139",
                                           :version "v8.2.0-v1.13.2-7135.139"}]}

                          {:created-date "2011-01-04T05:42:23Z",
                           :description "Does what every addon does, just better",
                           :tag-list [:coords :map :minimap :professions :ui]
                           :update? false,
                           :updated-date "2019-07-03T07:11:47Z",
                           :group-id "https://www.curseforge.com/wow/addons/everyotheraddon",
                           :installed-version "v8.2.0-v1.13.2-7135.139",
                           :installed-game-track :retail
                           :game-track :retail
                           :name "everyotheraddon",
                           :source "curseforge",
                           :interface-version 80200,
                           :supported-game-tracks [:retail]
                           :download-url "https://edge.forgecdn.net/files/2/2/EveryOtherAddon.zip",
                           :label "Every Other Addon",
                           :download-count 5400000,
                           :source-id 2,
                           :url "https://www.curseforge.com/wow/addons/everyotheraddon",
                           :version "v8.2.0-v1.13.2-7135.139",
                           :dirname "EveryOtherAddon",
                           :primary? true,
                           :matched? true
                           :release-list [{:download-url "https://edge.forgecdn.net/files/2/2/EveryOtherAddon.zip",
                                           :game-track :retail,
                                           :interface-version 80200,
                                           :release-label "[WoW 8.2.0] EveryOtherAddon-v8.2.0-v1.13.2-7135.139",
                                           :version "v8.2.0-v1.13.2-7135.139"}]}]]

            (core/import-exported-file output-path)
            (core/refresh) ;; re-read the installation directory
            (is (= expected (core/get-state :installed-addon-list)))))))))

(deftest import-exported-addon-list-file-v2
  (testing "an export can be imported AND per-addon game track preferences are preserved"
    (let [;; modified curseforge addon files to generate fake links
          every-addon-zip-file (fixture-path "everyaddon--1-2-3.zip")
          every-other-addon-zip-file (fixture-path "everyotheraddon--4-5-6.zip")

          every-addon-api (slurp (fixture-path "curseforge-api-addon--everyaddon.json"))
          every-other-addon-api (slurp (fixture-path "curseforge-api-addon--everyotheraddon-classic.json"))

          dummy-catalogue (slurp (fixture-path "import-export--dummy-catalogue.json"))

          fake-routes {;; catalogue
                       "https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json"
                       {:get (fn [req] {:status 200 :body dummy-catalogue})}

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
      (with-global-fake-routes-in-isolation fake-routes
        (with-running-app
          (helper/install-dir)

          (let [;; our list of addons to import
                output-path (fixture-path "import-export--export-v2.json")

                strict? false

                expected [{:created-date "2010-05-07T18:48:16Z",
                           :description "Does what no other addon does, slightly differently",
                           :tag-list [:bags :inventory]
                           :update? false,
                           :updated-date "2019-06-26T01:21:39Z",
                           :group-id "https://www.curseforge.com/wow/addons/everyaddon",
                           :installed-version "v8.2.0-v1.13.2-7135.139",
                           :installed-game-track :retail
                           :game-track :retail
                           :name "everyaddon",
                           :source "curseforge",
                           :interface-version 80000,
                           :supported-game-tracks [:retail]
                           :download-url "https://edge.forgecdn.net/files/1/1/EveryAddon.zip",
                           :label "EveryAddon",
                           :download-count 3000000,
                           :source-id 1,
                           :url "https://www.curseforge.com/wow/addons/everyaddon",
                           :version "v8.2.0-v1.13.2-7135.139",
                           :dirname "EveryAddon",
                           :primary? true,
                           :matched? true
                           :release-list [{:download-url "https://edge.forgecdn.net/files/1/1/EveryAddon.zip",
                                           :game-track :retail,
                                           :interface-version 80000,
                                           :release-label "[WoW 8.0.1] EveryAddon-v8.2.0-v1.13.2-7135.139",
                                           :version "v8.2.0-v1.13.2-7135.139"}]},

                          {:created-date "2011-01-04T05:42:23Z",
                           :description "Does what every addon does, just better",
                           :tag-list [:coords :map :minimap :professions :ui]
                           :update? false,
                           :updated-date "2019-07-03T07:11:47Z",
                           :group-id "https://www.curseforge.com/wow/addons/everyotheraddon",
                           :installed-version "v8.2.0-v1.13.2-7135.139",
                           :installed-game-track :classic
                           ;; significant! differs from above because addon directory's `:game-track`
                           ;; is set to `:retail` and `strict?` is `false`
                           :game-track :classic
                           :name "everyotheraddon",
                           :source "curseforge",
                           :interface-version 11300, ;; changed

                           ;; why :retail? According to EveryOtherAddon.toc, it's actually a retail addon and not a classic addon.
                           ;; we're skewing the API results and the addon-dir's strictness to ensure a classic version is found and installed
                           ;; but this test is essentially drifting and should be using a EveryOtherAddonClassic type addon where the toc is consistent.
                           :supported-game-tracks [:retail]

                           :download-url "https://edge.forgecdn.net/files/2/2/EveryOtherAddon.zip",
                           :label "Every Other Addon",
                           :download-count 5400000,
                           :source-id 2,
                           :url "https://www.curseforge.com/wow/addons/everyotheraddon",
                           :version "v8.2.0-v1.13.2-7135.139",
                           :dirname "EveryOtherAddon",
                           :primary? true,
                           :matched? true
                           :release-list [{:download-url "https://edge.forgecdn.net/files/2/2/EveryOtherAddon.zip",
                                           :game-track :classic,
                                           :interface-version 11300,
                                           :release-label "[WoW 1.13.2] EveryOtherAddon-v8.2.0-v1.13.2-7135.139",
                                           :version "v8.2.0-v1.13.2-7135.139"}]}]]

            (core/import-exported-file output-path)
            ;; so both retail and classic are picked up on refresh
            (core/set-game-track-strictness! strict?)
            (core/refresh) ;; re-read the installation directory
            (is (= expected (core/get-state :installed-addon-list)))))))))

(deftest check-for-addon-update
  (testing "the key `:update?` is set to `true` when the installed version doesn't match the catalogue version"
    (let [;; we start off with a list of these called a catalogue. it's downloaded from github
          catalogue {:category-list ["Auction House & Vendors"],
                     :download-count 1
                     :label "Every Addon"
                     :name "every-addon",
                     :source "curseforge",
                     :source-id 0
                     :updated-date "2012-09-20T05:32:00Z",
                     :url "https://www.curseforge.com/wow/addons/every-addon"}

          ;; this is subset of the data the remote addon host (like curseforge) serves us
          api-result {:latestFiles [{:downloadUrl "https://example.org/foo"
                                     :displayName "v8.10.00"
                                     :gameVersionFlavor "wow_retail",
                                     :gameVersion ["7.0.0"]
                                     :fileDate "2001-01-03T00:00:00.000Z",
                                     :fileName "EveryAddon.zip"
                                     :releaseType 1,
                                     :exposeAsAlternative nil}]}
          alt-api-result (assoc-in api-result [:latestFiles 0 :displayName] "v8.20.00")

          dummy-catalogue (merge (catalogue/new-catalogue [])
                                 {:addon-summary-list [catalogue]
                                  :spec {:version 1}
                                  :total 1})

          fake-routes {;; catalogue
                       "https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json"
                       {:get (fn [req] {:status 200 :body (utils/to-json dummy-catalogue)})}

                       ;; every-addon
                       "https://addons-ecs.forgesvc.net/api/v2/addon/0"
                       {:get (fn [req] {:status 200 :body (utils/to-json api-result)})}

                       "https://addons-ecs.forgesvc.net/api/v2/addon/1"
                       {:get (fn [req] {:status 200 :body (utils/to-json alt-api-result)})}}]

      (with-global-fake-routes-in-isolation fake-routes
        (with-running-app
          (core/set-addon-dir! (str fs/*cwd*))

          (let [;; a collection of these are scraped from the installed addons
                toc {:name "every-addon"
                     :label "Every Addon"
                     :description "foo"
                     :dirname "EveryAddon"
                     :interface-version 70000
                     :installed-version "v8.10.00"
                     :supported-game-tracks [:retail]}

                ;; and optionally these from .strongbox.json if we installed the addon
                nfo {:installed-version "v8.10.00",
                     :installed-game-track :retail
                     :name "every-addon",
                     :group-id "doesntmatter"
                     :primary? true,
                     :source "curseforge"
                     :source-id 0}

                ;; the nfo data is simply merged over the top of the scraped toc data
                toc (addon/merge-toc-nfo toc nfo)

                ;; we then attempt to match this 'toc+nfo' to an addon in the catalogue
                ;; in this case we have a catalogue of 1 and only interested in the first result
                result (first (db/-db-match-installed-addons-with-catalogue (core/get-state :db) [toc]))

                ;; previously done in above step, mooshing the installed addon and catalogue item together is
                ;; now a separate step
                toc-addon (core/moosh-addons toc (:catalogue-match result))

                alt-toc-addon (assoc toc-addon :source-id 1)

                ;; and what we 'expand' that data into
                source-updates {:download-url "https://example.org/foo",
                                :version "v8.10.00"
                                :game-track :retail
                                :release-list [{:download-url "https://example.org/foo",
                                                :game-track :retail,
                                                :interface-version 70000,
                                                :release-label "[WoW 7.0.0] EveryAddon",
                                                :version "v8.10.00"}]}

                alt-source-updates (assoc source-updates :version "v8.20.00")
                alt-source-updates (assoc-in alt-source-updates [:release-list 0 :version] "v8.20.00")

                ;; after calling `check-for-update` we expect the result to be the merged sum of the below parts
                expected (merge toc-addon source-updates {:update? false})
                alt-expected (merge alt-toc-addon alt-source-updates {:update? true})]

            (is (= expected (core/check-for-update toc-addon)))
            (is (= alt-expected (core/check-for-update alt-toc-addon)))))))))

;; todo: install classic addon into retail game track

(deftest db-load-catalogue
  (testing "very long descriptions are truncated"
    (let [addon-with-long-description
          {:label "EveryAddon",
           :name  "everyaddon",

           ;; 256 characters, 1 more than is supported
           :description  "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Curabitur dapibus, ligula at auctor facilisis, arcu metus tempor neque, a aliquam sem magna in augue. Cras vel justo augue. Suspendisse ex leo, pellentesque ut congue vel, lobortis eu nisl. Sed amet."

           :category-list  ["Auction & Economy", "Data Broker"],
           :source "curseforge"
           :source-id 1
           :created-date  "2009-02-08T13:30:30Z",
           :updated-date  "2016-09-08T14:18:33Z",
           :url "https://www.example.org/wow/addons/everyaddon"
           :download-count 1}

          expected (subs (:description addon-with-long-description) 0 255)

          dummy-catalogue (merge (catalogue/new-catalogue [])
                                 {:addon-summary-list [addon-with-long-description]
                                  :total 1
                                  :spec {:version 1}})

          fake-routes {"https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json"
                       {:get (fn [req] {:status 200 :body (utils/to-json dummy-catalogue)})}}]

      (with-global-fake-routes-in-isolation fake-routes
        (with-running-app
          (is (= expected
                 (:description (first (core/get-state :db))))))))))


;;


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
  (merge helper/toc helper/addon-summary matched? source-updates))

(deftest install-addon-guard
  (testing "an addon can be installed"
    (with-running-app*
      (let [install-dir (str fs/*cwd*)
            ;; move dummy addon file into place so there is no cache miss
            fname (downloaded-addon-fname (:name addon) (:version addon))
            _ (utils/cp (fixture-path fname) install-dir)
            test-only? false
            file-list (core/install-addon-guard addon install-dir test-only?)]
        (testing "addon directory created, single file written (.strongbox.json nfo file)"
          (is (= (count file-list) 1))
          (is (fs/exists? (first file-list))))))))

(deftest install-addon-guard--trial-installation
  (testing "trial installation of a good addon"
    (with-running-app*
      (let [install-dir (helper/install-dir)
            fname (downloaded-addon-fname (:name addon) (:version addon))
            dest (utils/cp (fixture-path fname) install-dir)
            addon (assoc addon :-testing-zipfile dest)
            test-only? true
            result (core/install-addon-guard addon install-dir test-only?)]
        (is result) ;; success

        ;; ensure nothing was actually unzipped
        (is (not (fs/exists? (utils/join install-dir "EveryAddon"))))))))

(deftest install-addon-guard--trial-installation-bad-addon
  (testing "trial installation of a bad addon"
    (with-running-app*
      (let [install-dir (helper/install-dir)
            ;; move dummy addon file into place so there is no cache miss
            fname (downloaded-addon-fname (:name addon) (:version addon))
            _ (fs/copy (fixture-path "bad-truncated.zip") (utils/join install-dir fname))

            test-only? true
            result (core/install-addon-guard addon install-dir test-only?)]
        (is (not result)) ;; failure

        ;; ensure nothing was actually unzipped
        (is (not (fs/exists? (utils/join install-dir "EveryAddon"))))))))

(deftest install-bad-addon
  (testing "installing a bad addon"
    (with-global-fake-routes-in-isolation {}
      (let [install-dir (str fs/*cwd*)
            fname (downloaded-addon-fname (:name addon) (:version addon))
            dest (utils/join install-dir fname)
            _ (fs/copy (fixture-path "bad-truncated.zip") dest)
            addon (assoc addon :-testing-zipfile dest)]
        (is (nil? (core/install-addon-guard addon install-dir)))
        ;; bad zip file deleted
        (is (= 0 (count (fs/list-dir install-dir))))))))

(deftest install-bundled-addon
  (testing "installing a bundled addon"
    (with-running-app
      (let [install-dir (helper/install-dir)
            ;; reuse the addon fixture but change it's version
            bundled-addon (merge addon {:version "0.1.2"})

            ;; move dummy addon file into place so there is no cache miss
            fname (downloaded-addon-fname (:name bundled-addon) (:version bundled-addon))
            _ (fs/copy (fixture-path "everyaddon--0-1-2.zip") (utils/join install-dir fname))

            result (core/install-addon-guard bundled-addon)
            directory-list (helper/install-dir-contents)

            expected-nfo {;; bundled addon is simply a part of the 'everyaddon' addon.
                          ;; without a distinct name or version for itself.
                          :name "everyaddon"
                          :installed-version "0.1.2"
                          :group-id "https://www.example.org/wow/addons/everyaddon",
                          :installed-game-track :retail,
                          :primary? false,
                          :source "curseforge",
                          :source-id 1}]
        (is result) ;; success
        (is (= ["EveryAddon" "EveryAddon-BundledAddon" "everyaddon--0-1-2.zip"] directory-list))
        (is (= expected-nfo (nfo/read-nfo-file install-dir "EveryAddon-BundledAddon")))))))

(deftest install-bundled-addon-overwriting-ignored-addon
  (testing "installing/unzipping an addon with a shared mutual dependency of an addon that is ignored isn't possible"
    (with-running-app
      (let [install-dir (helper/install-dir)
            addon {:name "everyaddon" :label "EveryAddon" :version "0.1.2" :url "https://group.id/never/fetched"
                   :source "curseforge" :source-id 1
                   :download-url "https://path/to/remote/addon.zip" :game-track :retail
                   :-testing-zipfile (fixture-path "everyaddon--0-1-2.zip")}]

        (core/install-addon-guard addon)
        (is (= ["EveryAddon" "EveryAddon-BundledAddon"] (helper/install-dir-contents)))

        ;; make newly installed addon implicitly ignored
        (fs/mkdir (utils/join install-dir "EveryAddon" ".git"))
        (core/load-installed-addons) ;; refresh our knowledge of what is installed

        (let [addon2 {:name "everyotheraddon" :label "EveryOtherAddon" :version "5.6.7" :url "https://group.id/also/never/fetched"
                      :source "curseforge" :source-id 2
                      :download-url "https://path/to/remote/addon.zip" :game-track :retail
                      :-testing-zipfile (fixture-path "everyotheraddon--5-6-7.zip")}]
          (core/install-addon-guard addon2)
          (is (= ["EveryAddon" "EveryAddon-BundledAddon"] (helper/install-dir-contents))))))))

(deftest install-bundled-addon-overwriting-pinned-addon
  (testing "installing/unzipping an addon with a shared mutual dependency of an addon that is pinned isn't possible"
    (with-running-app
      (let [install-dir (helper/install-dir)
            addon {:name "everyaddon" :label "EveryAddon" :version "0.1.2" :url "https://group.id/never/fetched"
                   :source "curseforge" :source-id 1
                   :download-url "https://path/to/remote/addon.zip" :game-track :retail
                   :-testing-zipfile (fixture-path "everyaddon--0-1-2.zip")}

            addon2 {:name "everyotheraddon" :label "EveryOtherAddon" :version "5.6.7" :url "https://group.id/also/never/fetched"
                    :source "curseforge" :source-id 2
                    :download-url "https://path/to/remote/addon.zip" :game-track :retail
                    :-testing-zipfile (fixture-path "everyotheraddon--5-6-7.zip")}]

        (core/install-addon-guard addon)
        (is (= ["EveryAddon" "EveryAddon-BundledAddon"] (helper/install-dir-contents)))

        ;; refresh our knowledge of what is installed.
        (core/load-installed-addons)

        ;; pin the addon. 
        (addon/pin install-dir (first (core/get-state :installed-addon-list)) "0.1.2")

        ;; refresh our knowledge of what is installed.
        (core/load-installed-addons)

        ;; overwrite first addon with addon2.
        ;; this would ordinarily introduce the 'EveryOtherAddon' dirname
        (core/install-addon-guard addon2)
        (is (= ["EveryAddon" "EveryAddon-BundledAddon"] (helper/install-dir-contents)))))))

(deftest install-addon--lenient-game-track
  (testing "a classic addon can be installed into an addon directory by setting the non-strict (lenient) flag"
    (with-running-app
      (let [install-dir (helper/install-dir)
            strict? false
            _ (core/set-game-track-strictness! strict? install-dir)

            addon {:name "everyaddon-classic" :label "EveryAddon (Classic)" :version "1.2.3" :url "https://group.id/never/fetched"
                   :source "curseforge" :source-id 1
                   :download-url "https://path/to/remote/addon.zip"
                   :game-track :classic
                   :-testing-zipfile (fixture-path "everyaddon-classic--1-2-3.zip")}]

        (core/install-addon-guard addon install-dir)))))

(deftest install-addon--remove-zip
  (testing "installing an addon with the `:addon-zips-to-keep` preference set to `0` will delete the zip afterwards"
    (with-running-app
      (let [install-dir (helper/install-dir)
            ;; move dummy addon file into place so there is no cache miss
            fname (downloaded-addon-fname (:name addon) (:version addon))
            _ (utils/cp (fixture-path fname) install-dir)]
        (cli/set-preference :addon-zips-to-keep 0)
        (core/install-addon-guard addon install-dir)
        (is (= ["EveryAddon"] (helper/install-dir-contents)))))))

(deftest install-addon--remove-multiple-zips
  (testing "installing an addon with the `:addon-zips-to-keep` preference set to `0` will delete the zip afterwards"
    (with-running-app
      (let [install-dir (helper/install-dir)
            ;; move dummy addon file into place so there is no cache miss
            fname (downloaded-addon-fname (:name addon) (:version addon))]

        ;; create a bunch of empty files that will be matched and cleaned up.
        (doseq [i (range 1 6)]
          (let [empty-file (fs/file install-dir (downloaded-addon-fname (:name addon) (str "0.0." i)))]
            (fs/touch empty-file)
            ;; ensure each one is definitively a little older than the previous
            (Thread/sleep 10)))

        ;; ensure the actual zip arrives last
        (utils/cp (fixture-path fname) install-dir)

        (is (= ["everyaddon--0-0-1.zip"
                "everyaddon--0-0-2.zip"
                "everyaddon--0-0-3.zip"
                "everyaddon--0-0-4.zip"
                "everyaddon--0-0-5.zip"
                "everyaddon--1-2-3.zip"]
               (helper/install-dir-contents)))

        (cli/set-preference :addon-zips-to-keep 3)
        (core/install-addon-guard addon install-dir)
        (is (= ["EveryAddon"
                "everyaddon--0-0-4.zip"
                "everyaddon--0-0-5.zip"
                "everyaddon--1-2-3.zip"]
               (helper/install-dir-contents)))))))

;;

(deftest uninstall-addon
  (testing "uninstalling an addon without a nfo file is supported, but won't remove addons that came bundled"
    (with-running-app
      (let [install-dir (helper/install-dir)
            ;; even though they're part of the same addon strongbox didn't install it
            ;; and doesn't know about the connection. expect the bundled addon to remain
            expected ["EveryAddon-BundledAddon"]]
        (zip/unzip-file (fixture-path "everyaddon--0-1-2.zip") install-dir)
        (core/remove-many-addons [helper/toc])
        (is (= expected (helper/install-dir-contents)))))))

(deftest uninstall-installed-addon
  (testing "uninstalling an addon installed with strongbox also removes bundled addons"
    (with-running-app
      (let [install-dir (helper/install-dir)

            addon-v0 (merge addon {:version "0.1.2"})
            addon-v1 addon

            ;; move dummy addon files into place so there is no cache miss
            fname-v0 (downloaded-addon-fname (:name addon-v0) (:version addon-v0))
            fname-v1 (downloaded-addon-fname (:name addon-v1) (:version addon-v1))

            fixture-v0 (fixture-path "everyaddon--0-1-2.zip") ;; v0.1 unzips to two directories
            fixture-v1 (fixture-path "everyaddon--1-2-3.zip") ;; v1.2 has just the one directory

            _ (fs/copy fixture-v0 (utils/join install-dir fname-v0))
            _ (fs/copy fixture-v1 (utils/join install-dir fname-v1))

            install-path-dirs #(->> install-dir
                                    fs/list-dir
                                    (filter fs/directory?) ;; exclude any .zip files
                                    (map fs/base-name)
                                    sort)]

        (core/install-addon-guard addon-v0 install-dir)
        (is (= ["EveryAddon" "EveryAddon-BundledAddon"] (install-path-dirs)))

        ;; reload the list of addons. this groups the addons up by :group-id
        (core/load-installed-addons)
        (is (= 1 (count (core/get-state :installed-addon-list))))

        (let [;; our v0 addon should now have group information
              addon-v0 (first (core/get-state :installed-addon-list))
              ;; there is no catalogue so there is no download-url. the version has also changed.
              addon-v1 (merge addon-v0 source-updates {:url "https://example.org/"})]
          ;; install the upgrade that gets rid of a directory
          (core/install-addon-guard addon-v1 install-dir)
          (is (= ["EveryAddon"] (install-path-dirs))))))))

(deftest uninstall-ignored-addon
  (testing "uninstalling an addon we're ignoring isn't possible."
    (with-running-app
      (let [install-dir (helper/install-dir)
            _ (zip/unzip-file (fixture-path "everyaddon--1-2-3.zip") install-dir)
            _ (fs/mkdir (utils/join install-dir "EveryAddon" ".git"))
            _ (core/load-installed-addons)
            addon (first (core/get-state :installed-addon-list))]
        (core/remove-many-addons [addon])
        (is (= ["EveryAddon"] (helper/install-dir-contents)))))))

(deftest uninstall-ignored-bundled-addon
  (testing "uninstalling an addon with a bundled addon that is being ignored isn't possible."
    (with-running-app

      ;; 2021-09-04: change in behaviour. addons that no longer match the catalogue are still checked for
      ;; updates if the right toc+nfo data is available.
      (with-global-fake-routes-in-isolation
        {"https://addons-ecs.forgesvc.net/api/v2/addon/1"
         {:get (fn [req] {:status 404 :reason-phrase "not found"})}}

        (let [install-dir (helper/install-dir)
              install-dir-contents #(->> install-dir fs/list-dir (filter fs/directory?) (map fs/base-name) sort)

            ;; trick here: copying 0.1.2 fixture to 1.2.3 filename. this fixture unpacks two directories
              fname (downloaded-addon-fname (:name addon) (:version addon))
              _ (fs/copy (fixture-path "everyaddon--0-1-2.zip") (utils/join install-dir fname))
              _ (core/install-addon-guard addon install-dir)

            ;; ensure bundled addon gets ignored
              _ (fs/mkdir (utils/join install-dir "EveryAddon-BundledAddon" ".git"))
              _ (core/load-installed-addons)
              addon (first (core/get-state :installed-addon-list))]
          (core/remove-many-addons [addon])
          (is (= ["EveryAddon" "EveryAddon-BundledAddon"] (install-dir-contents))))))))

;; mutual dependencies

(deftest install-addons-with-mutual-dependencies
  (testing "installing addons with mutual dependencies captures the dependency"
    (with-running-app
      (let [install-dir (helper/install-dir)

            ;; all these addons install the 'EveryAddon-BundledAddon' addon
            addon-1 {:name "everyaddon" :label "EveryAddon" :version "0.1.2" :url "https://group.id/never/fetched"
                     :source "curseforge" :source-id 1
                     :download-url "https://path/to/remote/addon.zip" :game-track :retail
                     :-testing-zipfile (fixture-path "everyaddon--0-1-2.zip")}

            addon-2 {:name "everyotheraddon" :label "EveryOtherAddon" :version "5.6.7" :url "https://group.id/also/never/fetched"
                     :source "curseforge" :source-id 2
                     :download-url "https://path/to/remote/addon.zip" :game-track :retail
                     :-testing-zipfile (fixture-path "everyotheraddon--5-6-7.zip")}

            ;; 'bundled is misleading here', standalone is more like it
            addon-3 {:name "bundledaddon" :label "BundledAddon" :version "a.b.c" :url "https://group.id/still/not/fetched"
                     :source "curseforge" :source-id 3
                     :download-url "https://path/to/remote/addon.zip" :game-track :retail
                     :-testing-zipfile (fixture-path "everyaddon-bundledaddon--a-b-c.zip")}

            bundled-dirname "EveryAddon-BundledAddon"

            ;; addon-2 overwrites the bundled nfo data but preserves previous
            ;; addon-3 overwrites the bundled nfo data *again* but preserves previous two
            expected [{:group-id "https://group.id/never/fetched"
                       :installed-game-track :retail ;; ignore the implications for now?
                       :installed-version "0.1.2"
                       :name "everyaddon"
                       :primary? false
                       :source "curseforge"
                       :source-id 1}
                      {:group-id "https://group.id/also/never/fetched",
                       :installed-game-track :retail,
                       :installed-version "5.6.7",
                       :name "everyotheraddon",
                       :primary? false,
                       :source "curseforge",
                       :source-id 2}
                      {:group-id "https://group.id/still/not/fetched",
                       :installed-game-track :retail,
                       :installed-version "a.b.c",
                       :name "bundledaddon",
                       :primary? true,
                       :source "curseforge",
                       :source-id 3}]]

        (core/install-addon-guard addon-1)
        (is (= ["EveryAddon" "EveryAddon-BundledAddon"] (helper/install-dir-contents)))
        (is (= (first expected) (nfo/read-nfo install-dir bundled-dirname)))
        (is (= (first expected) (nfo/read-nfo-file install-dir bundled-dirname)))

        (core/load-installed-addons) ;; refresh our knowledge of what is installed

        (core/install-addon-guard addon-2)
        (is (= ["EveryAddon" "EveryAddon-BundledAddon" "EveryOtherAddon"] (helper/install-dir-contents)))
        (is (= (second expected) (nfo/read-nfo install-dir bundled-dirname)))
        (is (= (subvec expected 0 2) (nfo/read-nfo-file install-dir bundled-dirname)))

        (core/load-installed-addons) ;; refresh our knowledge of what is installed

        (core/install-addon-guard addon-3)
        (is (= (last expected) (nfo/read-nfo install-dir bundled-dirname)))
        (is (= expected (nfo/read-nfo-file install-dir bundled-dirname)))))))

(deftest install-addons-with-mutual-dependencies-user-warning
  (testing "installing addons with mutual dependencies warns the user"
    (with-running-app
      (let [addon-1 {:name "everyaddon" :label "EveryAddon" :version "0.1.2" :url "https://group.id/never/fetched"
                     :source "curseforge" :source-id 1
                     :download-url "https://path/to/remote/addon.zip" :game-track :retail
                     :-testing-zipfile (fixture-path "everyaddon--0-1-2.zip")}
            addon-2 {:name "everyotheraddon" :label "EveryOtherAddon" :version "5.6.7" :url "https://group.id/also/never/fetched"
                     :source "curseforge" :source-id 2
                     :download-url "https://path/to/remote/addon.zip" :game-track :retail
                     :-testing-zipfile (fixture-path "everyotheraddon--5-6-7.zip")}
            expected ["addon \"everyotheraddon\" is overwriting \"everyaddon\""]]
        (helper/install-dir)
        (core/install-addon-guard addon-1)
        (core/load-installed-addons) ;; refresh our knowledge of what is installed
        (is (= expected (logging/buffered-log :warn (core/install-addon-guard addon-2))))))))

(deftest uninstall-addons-with-mutual-dependencies--overwrote
  (testing "uninstalling an addon whose mutual dependency *overwrote another* will see the original one restored"
    (with-running-app

      ;; 2021-09-04: change in behaviour. addons that no longer match the catalogue are still checked for
      ;; updates if the right toc+nfo data is available.
      (with-global-fake-routes-in-isolation
        {"https://addons-ecs.forgesvc.net/api/v2/addon/1"
         {:get (fn [req] {:status 404 :reason-phrase "not found"})}}

        (let [install-dir (helper/install-dir)
              addon-1 {:name "everyaddon" :label "EveryAddon" :version "0.1.2" :url "https://group.id/never/fetched"
                       :source "curseforge" :source-id 1
                       :download-url "https://path/to/remote/addon.zip" :game-track :retail
                       :-testing-zipfile (fixture-path "everyaddon--0-1-2.zip")}

              addon-2 {:name "everyotheraddon" :label "EveryOtherAddon" :version "5.6.7" :url "https://group.id/also/never/fetched"
                       :source "curseforge" :source-id 2
                       :download-url "https://path/to/remote/addon.zip" :game-track :retail
                       :-testing-zipfile (fixture-path "everyotheraddon--5-6-7.zip")}

              bundled-dirname "EveryAddon-BundledAddon"

              expected {:group-id "https://group.id/never/fetched"
                        :installed-game-track :retail
                        :installed-version "0.1.2"
                        :name "everyaddon"
                        :primary? false
                        :source "curseforge"
                        :source-id 1}]

          (core/install-addon-guard addon-1)
          (is (= ["EveryAddon" "EveryAddon-BundledAddon"] (helper/install-dir-contents)))
          (core/load-installed-addons) ;; refresh our knowledge of what is installed

          (core/install-addon-guard addon-2)
          (is (= ["EveryAddon" "EveryAddon-BundledAddon" "EveryOtherAddon"] (helper/install-dir-contents)))
          (core/load-installed-addons) ;; refresh our knowledge of what is installed

          (core/remove-addon (helper/select-addon (:url addon-2)))
          (is (= ["EveryAddon" "EveryAddon-BundledAddon"] (helper/install-dir-contents)))
          (is (= expected (nfo/read-nfo install-dir bundled-dirname))))))))

(deftest uninstall-addons-with-mutual-dependencies--overwritten
  (testing "uninstalling an addon whose mutual dependency *was overwritten* by another will see it removed from the list"
    (with-running-app

      ;; 2021-09-04: change in behaviour. addons that no longer match the catalogue are still checked for
      ;; updates if the right toc+nfo data is available.
      (with-global-fake-routes-in-isolation
        {"https://addons-ecs.forgesvc.net/api/v2/addon/2"
         {:get (fn [req] {:status 404 :reason-phrase "not found"})}}

        (let [install-dir (helper/install-dir)
              addon-1 {:name "everyaddon" :label "EveryAddon" :version "0.1.2" :url "https://group.id/never/fetched"
                       :source "curseforge" :source-id 1
                       :download-url "https://path/to/remote/addon.zip" :game-track :retail
                       :-testing-zipfile (fixture-path "everyaddon--0-1-2.zip")}

              addon-2 {:name "everyotheraddon" :label "EveryOtherAddon" :version "5.6.7" :url "https://group.id/also/never/fetched"
                       :source "curseforge" :source-id 2
                       :download-url "https://path/to/remote/addon.zip" :game-track :retail
                       :-testing-zipfile (fixture-path "everyotheraddon--5-6-7.zip")}

              bundled-dirname "EveryAddon-BundledAddon"

              expected {:group-id "https://group.id/also/never/fetched"
                        :installed-game-track :retail
                        :installed-version "5.6.7"
                        :name "everyotheraddon"
                        :primary? false
                        :source "curseforge"
                        :source-id 2}]

          (core/install-addon-guard addon-1)
          (is (= ["EveryAddon" "EveryAddon-BundledAddon"] (helper/install-dir-contents)))
          (core/load-installed-addons) ;; refresh our knowledge of what is installed

          (core/install-addon-guard addon-2)
          (is (= ["EveryAddon" "EveryAddon-BundledAddon" "EveryOtherAddon"] (helper/install-dir-contents)))
          (core/load-installed-addons) ;; refresh our knowledge of what is installed

          (core/remove-addon (helper/select-addon (:url addon-1)))
          (is (= ["EveryAddon-BundledAddon" "EveryOtherAddon"] (helper/install-dir-contents)))
          (is (= expected (nfo/read-nfo install-dir bundled-dirname))))))))

;;

(deftest http-500-downloading-catalogue
  (testing "HTTP 500 while fetching catalogue from github"
    (let [;; overrides fake route in `./test_helper.clj`
          fake-routes {"https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json"
                       {:get (fn [req] {:status 500 :host "raw.githubusercontent.com" :reason-phrase "500 Server Error"})}}

          expected ["downloading 'short' catalogue"
                    "failed to fetch 'https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json': 500 Server Error (HTTP 500)"]]
      (with-global-fake-routes-in-isolation fake-routes
        (with-running-app
          (is (= expected (logging/buffered-log
                           :info (core/download-catalogue (core/get-catalogue-location :short))))))))))

(deftest re-download-catalogue-on-bad-data
  (testing "catalogue data is re-downloaded if it can't be read"
    (let [;; overrides the fake route in test_helper.clj
          fake-routes {"https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json"
                       {:get (fn [req] {:status 200 :body (slurp (fixture-path "dummy-catalogue--single-entry.json"))})}}]
      (with-running-app
        (core/refresh)

        ;; this is the guard to the `db-load-catalogue` fn
        ;; catalogue fixture in `test-helper.clj` is an empty map so this should always return false
        (is (not (core/db-catalogue-loaded?)))

        ;; empty the file. quickest way to bad json
        (-> (core/get-catalogue-location) core/catalogue-local-path (spit ""))

        ;; the catalogue will be re-requested, this time we've swapped out the fixture with one with a single entry
        (with-global-fake-routes-in-isolation fake-routes
          ;; this will print a warning with a stacktrace.
          ;; it's being hidden so actual stacktraces don't get overlooked
          (log/with-level :error
            (core/db-load-catalogue)))

        (is (core/db-catalogue-loaded?))))))

(deftest re-download-catalogue-on-bad-data-2
  (testing "`db-load-catalogue` doesn't fail catastrophically when re-downloaded json is still bad"
    (let [;; overrides the fake route in test_helper.clj
          fake-routes {"https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json"
                       {:get (fn [req] {:status 200 :body "borked json"})}}]
      (with-running-app
        (core/refresh)

        ;; this is the guard to the `db-load-catalogue` fn
        ;; catalogue fixture in test-helper is an empty map, this should always return false
        (is (not (core/db-catalogue-loaded?)))

        ;; empty the file. quickest way to bad json
        (-> (core/get-catalogue-location) core/catalogue-local-path (spit ""))

        ;; the catalogue will be re-requested, this time the remote file is also corrupt
        (with-global-fake-routes-in-isolation fake-routes
          ;; this will print a warning with a stacktrace.
          ;; it's being hidden so actual stacktraces don't get overlooked
          (log/with-level :error
            (core/db-load-catalogue)))

        (is (not (core/db-catalogue-loaded?)))))))

;;

(deftest add-user-addon-to-user-catalogue
  (testing "user addon is successfully added to the user catalogue, creating it if it doesn't exist"
    (let [user-addon {:url "https://github.com/Aviana/HealComm"
                      :updated-date "2019-10-09T17:40:01Z"
                      :source "github"
                      :source-id "Aviana/HealComm"
                      :label "HealComm"
                      :name "healcomm"
                      :download-count 30946
                      :tag-list []}

          expected (merge (catalogue/new-catalogue [])
                          {;; hack, catalogue/format-catalogue-data orders the addon summary making them uncomparable
                           :total 1
                           :addon-summary-list [user-addon]})]

      (with-running-app
        (core/add-user-addon! user-addon)
        (is (= expected (catalogue/read-catalogue (core/paths :user-catalogue-file))))))))

(deftest add-user-addon-to-user-catalogue--idempotence
  (testing "adding addons to the user catalogue is idempotent"
    (let [user-addon {:url "https://github.com/Aviana/HealComm"
                      :updated-date "2019-10-09T17:40:01Z"
                      :source "github"
                      :source-id "Aviana/HealComm"
                      :label "HealComm"
                      :name "healcomm"
                      :download-count 30946
                      :tag-list []}

          expected (merge (catalogue/new-catalogue [])
                          {;; hack, catalogue/format-catalogue-data orders the addon summary making them uncomparable
                           :total 1
                           :addon-summary-list [user-addon]})]

      (with-running-app
        (core/add-user-addon! user-addon)
        (core/add-user-addon! user-addon)
        (core/add-user-addon! user-addon)
        (is (= expected (catalogue/read-catalogue (core/paths :user-catalogue-file))))))))

;; todo: can these fixtures use the test_helper versions?
(deftest moosh-addons
  (testing "addons are mooshed correctly when a match is found in the db"
    (let [toc {:name "everyaddon"
               :label "EveryAddon"
               :description "Toc Description"
               :dirname "EveryAddon"
               :interface-version 70000
               :installed-version "1.2.3"
               :supported-game-tracks [:retail]}

          addon-summary {:name "everyaddon"
                         :label "EveryAddon"
                         :tag-list []
                         :updated-date "2001-01-01"
                         :download-count 123
                         :source "wowinterface"
                         :source-id 1
                         :url "https://www.wowinterface.com/downloads/info1"

                         ;; wowinterface and tukui don't have descriptions in their api
                         ;; the database will return a field with `nil` if the addon-summary
                         ;; was inserted without one
                         :description nil}

          expected {:name "everyaddon"
                    :label "EveryAddon"
                    :description "Toc Description"
                    :dirname "EveryAddon"
                    :interface-version 70000
                    :installed-version "1.2.3"
                    :supported-game-tracks [:retail]

                    :tag-list []
                    :updated-date "2001-01-01"
                    :download-count 123
                    :source "wowinterface"
                    :source-id 1
                    :url "https://www.wowinterface.com/downloads/info1"

                    ;; optional, lets the GUI know we have a match that can be checked for updates
                    :matched? true}]
      (is (= expected (core/moosh-addons toc addon-summary))))))

;;

(deftest ignore-addon
  (testing "a regular installed addon can be marked as 'ignored'"
    (with-running-app

      ;; 2021-09-04: change in behaviour. addons that no longer match the catalogue are still checked for
      ;; updates if the right toc+nfo data is available.
      (with-global-fake-routes-in-isolation
        {"https://addons-ecs.forgesvc.net/api/v2/addon/1"
         {:get (fn [req] {:status 404 :reason-phrase "not found"})}}

        (helper/install-dir)
        (let [addon {:name "everyaddon" :label "EveryAddon" :version "1.2.3" :url "https://group.id/never/fetched"
                     :source "curseforge" :source-id 1
                     :download-url "https://path/to/remote/addon.zip" :game-track :retail
                     :-testing-zipfile (fixture-path "everyaddon--1-2-3.zip")}

              expected {:ignore? true,
                        ;; `catalogue/expand-summary` is never called so the source updates are never added.
                        ;;:game-track :retail
                        ;;:download-url ...
                        ;;:version ...
                        :description "Does what no other addon does, slightly differently",
                        :dirname "EveryAddon",
                        :group-id "https://group.id/never/fetched",
                        :installed-game-track :retail,
                        :installed-version "1.2.3",
                        :interface-version 70000,
                        :supported-game-tracks [:retail]
                        :label "EveryAddon 1.2.3",
                        :name "everyaddon",
                        :primary? true,
                        :source "curseforge",
                        :source-id 1,
                        :update? false}]
          (core/install-addon-guard addon)
          (is (= ["EveryAddon"] (helper/install-dir-contents)))
          (core/load-installed-addons)

          ;; todo: the below makes this a UI test. move test to cli_test.clj
          (cli/select-addons)
          (cli/ignore-selected) ;; calls `core/refresh`
          (is (= expected (first (core/get-state :installed-addon-list)))))))))

(deftest clear-addon-ignore-flag
  (testing "an ignored addon can be 'unignored'"
    (with-running-app

      ;; 2021-09-04: change in behaviour. addons that no longer match the catalogue are still checked for
      ;; updates if the right toc+nfo data is available.
      (with-global-fake-routes-in-isolation
        {"https://addons-ecs.forgesvc.net/api/v2/addon/1"
         {:get (fn [req] {:status 404 :reason-phrase "not found"})}}

        (helper/install-dir)
        (let [addon {:name "everyaddon" :label "EveryAddon" :version "1.2.3" :url "https://group.id/never/fetched"
                     :source "curseforge" :source-id 1
                     :download-url "https://path/to/remote/addon.zip" :game-track :retail
                     :-testing-zipfile (fixture-path "everyaddon--1-2-3.zip")}

              expected {;;:ignore? false, ;; removed rather than set to false.
                        :description "Does what no other addon does, slightly differently",
                        :dirname "EveryAddon",
                        :group-id "https://group.id/never/fetched",
                        :installed-game-track :retail,
                        :installed-version "1.2.3",
                        :interface-version 70000,
                        :supported-game-tracks [:retail]
                        :label "EveryAddon 1.2.3",
                        :name "everyaddon",
                        :primary? true,
                        :source "curseforge",
                        :source-id 1,
                        :update? false}]
          (core/install-addon-guard addon)
          (core/load-installed-addons)

          ;; todo: the below makes this a UI test. move test to cli_test.clj
          (cli/select-addons)
          (cli/ignore-selected) ;; calls `core/refresh`
          (is (:ignore? (first (core/get-state :installed-addon-list))))

          ;; addon are deselected after having an action performed on them.
          (cli/select-addons)
          (cli/clear-ignore-selected)
          (is (= expected (first (core/get-state :installed-addon-list)))))))))

(deftest clear-addon-ignore-flag--group-addons
  (testing "an addon with an ignored group member can be 'unignored'. see issue#193"
    (with-running-app

      ;; 2021-09-04: change in behaviour. addons that no longer match the catalogue are still checked for
      ;; updates if the right toc+nfo data is available.
      (with-global-fake-routes-in-isolation
        {"https://addons-ecs.forgesvc.net/api/v2/addon/2"
         {:get (fn [req] {:status 404 :reason-phrase "not found"})}}

        (let [install-dir (helper/install-dir)
              addon {:name "everyotheraddon" :label "EveryOtherAddon" :version "5.6.7" :url "https://group.id/also/never/fetched"
                     :source "curseforge" :source-id 2
                     :download-url "https://path/to/remote/addon.zip" :game-track :retail
                     :-testing-zipfile (fixture-path "everyotheraddon--5-6-7.zip")}

              expected {:description "group record for the fetched addon",
                        :dirname "EveryAddon-BundledAddon",
                        :group-addon-count 2,
                        :group-addons [{:description "A useful addon that everyone bundles with their own.",
                                        :dirname "EveryAddon-BundledAddon",
                                        :group-id "https://group.id/also/never/fetched",

                                        :ignore? true,

                                        :installed-game-track :retail,
                                        :installed-version "5.6.7",
                                        :interface-version 80000,
                                        :supported-game-tracks [:retail]
                                        :label "BundledAddon a.b.c",
                                        :name "everyotheraddon",
                                        :primary? false,
                                        :source "curseforge",
                                        :source-id 2}

                                       {:description "Does what every addon does, just better",
                                        :dirname "EveryOtherAddon",
                                        :group-id "https://group.id/also/never/fetched",
                                        :installed-game-track :retail,
                                        :installed-version "5.6.7",
                                        :interface-version 70000,
                                        :supported-game-tracks [:retail]
                                        :label "EveryOtherAddon 5.6.7",
                                        :name "everyotheraddon",
                                        :primary? false,
                                        :source "curseforge",
                                        :source-id 2}],
                        :group-id "https://group.id/also/never/fetched",
                        :ignore? true,
                        :installed-game-track :retail,
                        :installed-version "5.6.7",
                        :interface-version 80000,
                        :supported-game-tracks [:retail]
                        :label "fetched (group)",
                        :name "everyotheraddon",
                        :primary? false,
                        :source "curseforge",
                        :source-id 2}

              target-idx 0
              expected-2 (-> expected
                             (dissoc :ignore?)
                             (assoc :update? false)
                             (update-in [:group-addons target-idx] dissoc :ignore?))]
          (core/install-addon-guard addon)
          (nfo/ignore install-dir "EveryAddon-BundledAddon")
          (core/load-installed-addons)

          ;; todo: the below makes this a UI test. move test to cli_test.clj
          (cli/select-addons)
          (is (= expected (first (core/get-state :installed-addon-list))))

          (cli/clear-ignore-selected)
          (is (= expected-2 (first (core/get-state :installed-addon-list)))))))))

(deftest clear-addon-ignore-flag--implicit-ignore
  (testing "an implicitly ignored (vcs) addon can be 'unignored' as well"
    (with-running-app

      ;; 2021-09-04: change in behaviour. addons that no longer match the catalogue are still checked for
      ;; updates if the right toc+nfo data is available.
      (with-global-fake-routes-in-isolation
        {"https://addons-ecs.forgesvc.net/api/v2/addon/1"
         {:get (fn [req] {:status 404 :reason-phrase "not found"})}}

        (let [install-dir (helper/install-dir)
              addon {:name "everyaddon" :label "EveryAddon" :version "1.2.3" :url "https://group.id/never/fetched"
                     :source "curseforge" :source-id 1
                     :download-url "https://path/to/remote/addon.zip" :game-track :retail
                     :-testing-zipfile (fixture-path "everyaddon--1-2-3.zip")}

              expected {:ignore? false, ;; explicit `false` rather than removed
                        :description "Does what no other addon does, slightly differently",
                        :dirname "EveryAddon",
                        :group-id "https://group.id/never/fetched",
                        :installed-game-track :retail,
                        :installed-version "1.2.3",
                        :interface-version 70000,
                        :supported-game-tracks [:retail]
                        :label "EveryAddon 1.2.3",
                        :name "everyaddon",
                        :primary? true,
                        :source "curseforge",
                        :source-id 1,
                        :update? false}]
          (core/install-addon-guard addon)
          (fs/mkdir (utils/join install-dir "EveryAddon" ".git"))
          (core/load-installed-addons)
          (is (:ignore? (first (core/get-state :installed-addon-list)))) ;; implicitly ignored

          ;; todo: the below makes this a UI test. move test to cli_test.clj
          (cli/select-addons)
          (cli/clear-ignore-selected)
          (is (= expected (first (core/get-state :installed-addon-list)))))))))

(deftest empty-search-state
  (testing "temporary search state can be emptied of potentially stale catalogue data, preserving the search term."
    (let [dummy-catalogue (slurp (fixture-path "catalogue--v2.json"))
          fake-routes {"https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json"
                       {:get (fn [req] {:status 200 :body dummy-catalogue})}}
          search-term "a"
          ;; the buffers are emptied, the search term is preserved
          expected-empty-search-state (assoc core/-search-state-template :term search-term)]
      (with-global-fake-routes-in-isolation fake-routes
        (with-running-app
          (cli/search search-term)
           ;; searching happens in the background
          (Thread/sleep 50)
          ;; we have one search result from a catalogue of 4 addons
          (is (= 1 (-> (core/get-state :search) :results count)))
          ;; empty the stale search state
          (core/empty-search-results)
          (is (= expected-empty-search-state (core/get-state :search)))
          ;; do the search again without specifying a search term
          (cli/bump-search)
          (Thread/sleep 50)
          (is (= 1 (-> (core/get-state :search) :results count))))))))

(deftest -latest-strongbox-release
  (testing "standard github response for strongbox release data can be parsed and the release version extracted"
    (let [fake-routes {"https://api.github.com/repos/ogri-la/strongbox/releases/latest"
                       {:get (fn [req] {:status 200 :body (slurp (fixture-path "github-strongbox-release.json"))})}}
          expected "4.3.0"]
      (with-global-fake-routes-in-isolation fake-routes
        (is (= expected (core/-latest-strongbox-release)))))))

(deftest -latest-strongbox-release--throttled
  (testing "throttled github response status for strongbox release data returns a :failed "
    (let [fake-routes {"https://api.github.com/repos/ogri-la/strongbox/releases/latest"
                       {:get (fn [req] {:status 403 :reason-phrase "asdf"})}}
          expected :failed]
      (with-global-fake-routes-in-isolation fake-routes
        (is (= expected (core/-latest-strongbox-release)))))))

(deftest -latest-strongbox-release--unknown
  (testing "weird github response statuses for strongbox release data returns a :failed "
    (let [fake-routes {"https://api.github.com/repos/ogri-la/strongbox/releases/latest"
                       {:get (fn [req] {:status 999 :reason-phrase "asdf"})}}
          expected :failed]
      (with-global-fake-routes-in-isolation fake-routes
        (is (= expected (core/-latest-strongbox-release)))))))

(deftest -latest-strongbox-release--malformed
  (testing "successful but malformed/unparseable github response for strongbox release data returns a :failed "
    (let [fake-routes {"https://api.github.com/repos/ogri-la/strongbox/releases/latest"
                       {:get (fn [req] {:status 200 :body "asdf"})}}
          expected :failed]
      (with-global-fake-routes-in-isolation fake-routes
        (is (= expected (core/-latest-strongbox-release)))))))

(deftest latest-strongbox-release
  (testing "standard github response for strongbox release data can be parsed and the release version extracted"
    (let [fake-routes {"https://api.github.com/repos/ogri-la/strongbox/releases/latest"
                       {:get (fn [req] {:status 200 :body (slurp (fixture-path "github-strongbox-release.json"))})}}
          expected "4.3.0"]
      (with-running-app
        (with-global-fake-routes-in-isolation fake-routes
          (is (= expected (core/latest-strongbox-release))))))))

(deftest latest-strongbox-release--throttled
  (testing "throttled github response status for strongbox release data returns `nil`"
    (let [fake-routes {"https://api.github.com/repos/ogri-la/strongbox/releases/latest"
                       {:get (fn [req] {:status 403 :reason-phrase "asdf"})}}]
      (with-running-app
        (with-global-fake-routes-in-isolation fake-routes
          (is (nil? (core/latest-strongbox-release))))))))

(deftest latest-strongbox-release--subsequent-failure
  (testing "once discovered, release versions are are stored in app state and not fetched again."
    (let [fake-routes {"https://api.github.com/repos/ogri-la/strongbox/releases/latest"
                       {:get (fn [req] {:status 403 :reason-phrase "asdf"})}}
          expected "foo.bar.baz"]
      (with-running-app
        (swap! core/state assoc :latest-strongbox-release expected)
        (with-global-fake-routes-in-isolation fake-routes
          (is (= expected (core/latest-strongbox-release))))))))

(deftest unsteady?
  (testing "a function that operates on addons can be wrapped to mark the addon as 'unsteady'"
    (let [addon {:name "foo"}
          addon-fn (fn [addon*]
                     (and (core/unsteady? (:name addon*))
                          (not (empty? (core/get-state :unsteady-addon-list)))))
          addon-fn-affective (core/affects-addon-wrapper addon-fn)]
      (with-running-app
        (is (empty? (core/get-state :unsteady-addon-list)))
        (is (true? (addon-fn-affective addon)))
        (is (empty? (core/get-state :unsteady-addon-list)))))))

(deftest expandable?
  (let [cases [[{} false]
               [{:foo :bar} false]
               [{:source "wowinterface" :source-id 123} false]
               [{:name "foo" :label "Foo" :source "wowinterface" :source-id 123} true]
               [{:name "foo" :label "Foo" :source "wowinterface" :source-id 123 :ignore? true} false]]]
    (doseq [[given expected] cases]
      (is (= expected (core/expandable? given))))))

;; ---

(deftest read-strange-catalogue--unknown-source
  (testing "strongbox can try to install an addon from an unknown source and not crash"
    (let [future-data
          {:spec {:version 2}
           :datestamp "2020-02-20"
           :total 2
           :addon-summary-list
           [;; unknown source
            {:updated-date "2019-10-29T01:01:01Z",
             :created-date "2019-04-13T15:23:09.397Z",
             :description "A New Simple Percent",
             :download-count 1034,
             :label "A New Simple Percent",
             :name "a-new-simple-percent",
             :source "gitplex",
             :source-id "user/repo",
             :tag-list [:unit-frames],
             :url "https://www.gitplex.com/user/repo"}]}

          dummy-catalogue (utils/to-json future-data)
          fake-routes {"https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json"
                       {:get (fn [req] {:status 200 :body dummy-catalogue})}}
          expected-messages ["addon 'A New Simple Percent' is from an unsupported source 'gitplex'."
                             "refresh"]]

      (with-global-fake-routes-in-isolation fake-routes
        (with-running-app+opts {:spec? false}
          (helper/install-dir)
          (core/refresh)

          (is (= expected-messages
                 (logging/buffered-log
                  :error
                  (-> (core/db-search "new")
                      first ;; first page of results
                      first ;; first result
                      cli/install-addon))))

          (is (= [] (core/get-state :installed-addon-list))))))))

(deftest read-strange-catalogue--unknown-game-track
  (let [future-game-track :classic-bfa
        future-data
        {:spec {:version 2}
         :datestamp "2020-02-20"
         :total 2
         :addon-summary-list
         [;; unknown game track
          {:updated-date "2019-10-19T01:01:01Z",
           :download-count 9,
           :game-track-list [future-game-track]
           :label "Chinchilla",
           :name "chinchilla",
           :source "github",
           :source-id "Ravendwyr/Chinchilla",
           :tag-list [],
           :url "https://github.com/Ravendwyr/Chinchilla"}]}

        dummy-catalogue (utils/to-json future-data)

        fake-routes {;; catalogue
                     "https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json"
                     {:get (fn [req] {:status 200 :body dummy-catalogue})}}
        expected-messages ["unsupported game track ':classic-bfa'."
                           "refresh"]]

    (testing "strongbox can attempt to install an addon from an unknown source and not crash"
      (with-global-fake-routes-in-isolation fake-routes
        (with-running-app+opts {:spec? false}
          (helper/install-dir)
          (core/refresh)

          ;; the game track of the selected addon dir is used.
          ;; the game track in the addon is only used to pare down available releases.
          (swap! core/state assoc-in [:cfg :addon-dir-list 0 :game-track] future-game-track)

          (is (= expected-messages
                 (logging/buffered-log
                  :error
                  (-> (core/db-search "chin")
                      first ;; first page of results
                      first ;; first result
                      cli/install-addon))))

          (is (= [] (core/get-state :installed-addon-list))))))))

(deftest compressed-slurp
  (let [expected "foo!"]
    (is (= expected (core/decompress-bytes (core/compressed-slurp "foo.txt"))))))

(deftest compressed-slurp--not-found
  (is (nil? (core/compressed-slurp "file-that-definitely-does-not-exist.txt"))))

(deftest decompress-bytes--empty
  (is (nil? (core/decompress-bytes (.getBytes ""))))
  (is (nil? (core/decompress-bytes nil))))

(deftest decompress-bytes--not-compressed
  (let [result (try
                 (core/decompress-bytes (.getBytes "!"))
                 (catch java.io.IOException ioe
                   ioe))]
    (is (instance? java.io.IOException result))
    (is (= "Stream is not in the BZip2 format" (.getMessage result)))))

;;

(deftest default-catalogue
  (let [expected (first config/-default-catalogue-list)]
    (with-running-app*
      (is (= expected (core/default-catalogue))))))

(deftest emergency-catalogue
  (let [expected-warnings ["backup catalogue generated: 2021-09-25"
                           "remote catalogue unreachable or corrupt: https://example.org"]
        expected-total 3

        catalogue-location {:name :full :label "Full" :source "https://example.org"}
        warnings (logging/buffered-log
                  :warn
                  (is (= expected-total (:total (core/emergency-catalogue catalogue-location)))))]
    (is (= expected-warnings warnings))))

