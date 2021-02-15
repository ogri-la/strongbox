(ns strongbox.core-test
  (:require
   [clojure.string :refer [starts-with? ends-with?]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
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
    [test-helper :as helper :refer [fixture-path slurp-fixture helper-data-dir with-running-app]]
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
        (is (= [{:addon-dir dir1 :game-track :retail}] (core/get-state :cfg :addon-dir-list))))

      (testing "add-addon-dir! idempotence"
        (core/add-addon-dir! dir1 :retail)
        (is (= [{:addon-dir dir1 :game-track :retail}] (core/get-state :cfg :addon-dir-list))))

      (testing "add-addon-dir! just adds the dir, doesn't set it as selected"
        (is (= nil (core/selected-addon-dir))))

      (testing "set-addon-dir! sets the addon directory as selected and is also idempotent"
        (core/set-addon-dir! dir1)
        (is (= [{:addon-dir dir1 :game-track :retail}] (core/get-state :cfg :addon-dir-list)))
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
        (is (= [{:addon-dir dir1 :game-track :retail}] (core/get-state :cfg :addon-dir-list))))

      (testing "addon-dir-map, without args, returns the currently selected addon-dir"
        (is (= {:addon-dir dir1 :game-track :retail} (core/addon-dir-map)))
        (core/set-addon-dir! dir2)
        (is (= {:addon-dir dir2 :game-track :retail} (core/addon-dir-map)))
        (is (= {:addon-dir dir1 :game-track :retail} (core/addon-dir-map dir1))))

      (testing "addon-dir-map returns nil if map cannot be found"
        (is (= nil (core/addon-dir-map dir3))))

      (testing "set-game-track! changes the game track of the given addon dir"
        (core/set-game-track! :classic dir1)
        (is (= {:addon-dir dir1 :game-track :classic} (core/addon-dir-map dir1))))

      (testing "set-game-track! without addon-dir, changes the game track of the currently selected addon dir"
        (core/set-game-track! :classic)
        (is (= {:addon-dir dir2 :game-track :classic} (core/addon-dir-map dir2))))

      (testing "set-game-track! can change the game track to a compound game track"
        (core/set-game-track! :classic-retail)
        (is (= {:addon-dir dir2 :game-track :classic-retail} (core/addon-dir-map dir2))))

      (testing "set-addon-dir! changes default game-track to 'classic' if '_classic_' detected in addon dir name"
        (core/set-addon-dir! dir4)
        (is (= {:addon-dir dir4 :game-track :classic} (core/addon-dir-map dir4)))))))

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

(deftest export-catalogue-addon-list
  (testing "exported addon list data is correct"
    (let [catalogue (slurp-fixture "import-export--user-catalogue.json")
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
      (with-fake-routes-in-isolation fake-routes
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
      (with-fake-routes-in-isolation fake-routes
        (with-running-app
          (helper/install-dir)

          (let [;; our list of addons to import
                output-path (fixture-path "import-export--export-v2.json")

                game-track :retail-classic

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
                           ;; is set to the compound `:retail-classic`
                           :game-track :classic
                           :name "everyotheraddon",
                           :source "curseforge",
                           :interface-version 11300, ;; changed
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
            (core/set-game-track! game-track)
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

      (with-fake-routes-in-isolation fake-routes
        (with-running-app
          (core/set-addon-dir! (str fs/*cwd*))

          (let [;; a collection of these are scraped from the installed addons
                toc {:name "every-addon"
                     :label "Every Addon"
                     :description "foo"
                     :dirname "EveryAddon"
                     :interface-version 70000
                     :installed-version "v8.10.00"}

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

      (with-fake-routes-in-isolation fake-routes
        (with-running-app
          (is (= expected
                 (:description (first (core/get-state :db))))))))))


;;


(def toc
  "local addon .toc file"
  {:name "everyaddon",
   :description "Does what no other addon does, slightly differently"
   :dirname "EveryAddon",
   :label "EveryAddon 1.2.3",
   :interface-version 70000,
   :installed-version "1.2.3"})

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
  (merge toc addon-summary matched? source-updates))

(deftest install-addon
  (testing "an addon can be installed"
    (with-fake-routes-in-isolation {}
      (let [install-dir (str fs/*cwd*)
            ;; move dummy addon file into place so there is no cache miss
            fname (downloaded-addon-fname (:name addon) (:version addon))
            _ (utils/cp (fixture-path fname) install-dir)
            test-only? false
            ;; without a running app we have no `selected-addon-dir`.
            ;; pretend to be an export record with a `:game-track` instead
            addon (assoc addon :game-track :retail)
            file-list (core/install-addon addon install-dir test-only?)]

        (testing "addon directory created, single file written (.strongbox.json nfo file)"
          (is (= (count file-list) 1))
          (is (fs/exists? (first file-list))))))))

(deftest install-addon--trial-installation
  (testing "trial installation of a good addon"
    (with-fake-routes-in-isolation {}
      (let [install-dir (helper/install-dir)
            ;; move dummy addon file into place so there is no cache miss
            fname (downloaded-addon-fname (:name addon) (:version addon))
            _ (utils/cp (fixture-path fname) install-dir)

            test-only? true
            result (core/install-addon addon install-dir test-only?)]
        (is result) ;; success

        ;; ensure nothing was actually unzipped
        (is (not (fs/exists? (utils/join install-dir "EveryAddon"))))))))

(deftest install-addon--trial-installation-bad-addon
  (testing "trial installation of a bad addon"
    (with-fake-routes-in-isolation {}
      (let [install-dir (helper/install-dir)
            ;; move dummy addon file into place so there is no cache miss
            fname (downloaded-addon-fname (:name addon) (:version addon))
            _ (fs/copy (fixture-path "bad-truncated.zip") (utils/join install-dir fname)) ;; hoho, so evil

            test-only? true
            result (core/install-addon addon install-dir test-only?)]
        (is (not result)) ;; failure

        ;; ensure nothing was actually unzipped
        (is (not (fs/exists? (utils/join install-dir "EveryAddon"))))))))

(deftest install-bad-addon
  (testing "installing a bad addon"
    (with-fake-routes-in-isolation {}
      (let [install-dir (str fs/*cwd*)
            fname (downloaded-addon-fname (:name addon) (:version addon))]
        ;; move dummy addon file into place so there is no cache miss
        (fs/copy (fixture-path "bad-truncated.zip") (utils/join install-dir fname))
        (is (= (core/install-addon addon install-dir) nil))
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

            result (core/install-addon bundled-addon)
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

        (core/install-addon addon)
        (is (= ["EveryAddon" "EveryAddon-BundledAddon"] (helper/install-dir-contents)))

        ;; make newly installed addon implicitly ignored
        (fs/mkdir (utils/join install-dir "EveryAddon" ".git"))
        (core/load-installed-addons) ;; refresh our knowledge of what is installed

        (let [addon2 {:name "everyotheraddon" :label "EveryOtherAddon" :version "5.6.7" :url "https://group.id/also/never/fetched"
                      :source "curseforge" :source-id 2
                      :download-url "https://path/to/remote/addon.zip" :game-track :retail
                      :-testing-zipfile (fixture-path "everyotheraddon--5-6-7.zip")}]
          (core/install-addon addon2)
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

        (core/install-addon addon)
        (is (= ["EveryAddon" "EveryAddon-BundledAddon"] (helper/install-dir-contents)))

        ;; refresh our knowledge of what is installed.
        (core/load-installed-addons)

        ;; pin the addon. 
        (addon/pin install-dir (first (core/get-state :installed-addon-list)) "0.1.2")

        ;; refresh our knowledge of what is installed.
        (core/load-installed-addons)

        ;; overwrite first addon with addon2.
        ;; this would ordinarily introduce the 'EveryOtherAddon' dirname
        (core/install-addon addon2)
        (is (= ["EveryAddon" "EveryAddon-BundledAddon"] (helper/install-dir-contents)))))))

(deftest install-addon--compound-game-track
  (testing "a classic addon can be installed into an addon directory using a compound game track with retail preferred"
    (with-running-app
      (let [install-dir (helper/install-dir)
            _ (core/set-game-track! :retail-classic)

            addon {:name "everyaddon-classic" :label "EveryAddon (Classic)" :version "1.2.3" :url "https://group.id/never/fetched"
                   :source "curseforge" :source-id 1
                   :download-url "https://path/to/remote/addon.zip"
                   :game-track :classic
                   :-testing-zipfile (fixture-path "everyaddon-classic--1-2-3.zip")}]

        (core/install-addon addon install-dir)))))

(deftest install-addon--remove-zip
  (testing "installing an addon with the `:addon-zips-to-keep` preference set to `0` will delete the zip afterwards"
    (with-running-app
      (let [install-dir (helper/install-dir)
            ;; move dummy addon file into place so there is no cache miss
            fname (downloaded-addon-fname (:name addon) (:version addon))
            _ (utils/cp (fixture-path fname) install-dir)]
        (cli/set-preference :addon-zips-to-keep 0)
        (core/install-addon addon install-dir)
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
        (core/install-addon addon install-dir)
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
        (core/remove-many-addons [toc])
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

            install-path-dirs #(->> install-dir fs/list-dir
                                    (filter fs/directory?) ;; exclude any .zip files
                                    (map fs/base-name) sort)]

        (core/install-addon addon-v0 install-dir)
        (is (= ["EveryAddon" "EveryAddon-BundledAddon"] (install-path-dirs)))

        ;; reload the list of addons. this groups the addons up by :group-id
        (core/load-installed-addons)
        (is (= 1 (count (core/get-state :installed-addon-list))))

        (let [;; our v0 addon should now have group information
              addon-v0 (first (core/get-state :installed-addon-list))
              addon-v1 (merge addon-v0 source-updates {:url "https://example.org/"}) ;; there is no catalogue so there is no download-url. the version has changed also
              ]
          ;; install the upgrade that gets rid of a directory
          (core/install-addon addon-v1 install-dir)
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
      (let [install-dir (helper/install-dir)
            install-dir-contents #(->> install-dir fs/list-dir (filter fs/directory?) (map fs/base-name) sort)

            ;; trick here: copying 0.1.2 fixture to 1.2.3 filename. this fixture unpacks two directories
            fname (downloaded-addon-fname (:name addon) (:version addon))
            _ (fs/copy (fixture-path "everyaddon--0-1-2.zip") (utils/join install-dir fname))
            _ (core/install-addon addon install-dir)

            ;; ensure bundled addon gets ignored
            _ (fs/mkdir (utils/join install-dir "EveryAddon-BundledAddon" ".git"))
            _ (core/load-installed-addons)
            addon (first (core/get-state :installed-addon-list))]
        (core/remove-many-addons [addon])
        (is (= ["EveryAddon" "EveryAddon-BundledAddon"] (install-dir-contents)))))))

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

        (core/install-addon addon-1)
        (is (= ["EveryAddon" "EveryAddon-BundledAddon"] (helper/install-dir-contents)))
        (is (= (first expected) (nfo/read-nfo install-dir bundled-dirname)))
        (is (= (first expected) (nfo/read-nfo-file install-dir bundled-dirname)))

        (core/load-installed-addons) ;; refresh our knowledge of what is installed

        (core/install-addon addon-2)
        (is (= ["EveryAddon" "EveryAddon-BundledAddon" "EveryOtherAddon"] (helper/install-dir-contents)))
        (is (= (second expected) (nfo/read-nfo install-dir bundled-dirname)))
        (is (= (subvec expected 0 2) (nfo/read-nfo-file install-dir bundled-dirname)))

        (core/load-installed-addons) ;; refresh our knowledge of what is installed

        (core/install-addon addon-3)
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
            expected ["addon 'everyotheraddon' is overwriting 'everyaddon'"]]
        (helper/install-dir)
        (core/install-addon addon-1)
        (core/load-installed-addons) ;; refresh our knowledge of what is installed
        (is (= expected (logging/buffered-log :warn (core/install-addon addon-2))))))))

(deftest uninstall-addons-with-mutual-dependencies--overwrote
  (testing "uninstalling an addon whose mutual dependency *overwrote another* will see the original one restored"
    (with-running-app
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

        (core/install-addon addon-1)
        (is (= ["EveryAddon" "EveryAddon-BundledAddon"] (helper/install-dir-contents)))
        (core/load-installed-addons) ;; refresh our knowledge of what is installed

        (core/install-addon addon-2)
        (is (= ["EveryAddon" "EveryAddon-BundledAddon" "EveryOtherAddon"] (helper/install-dir-contents)))
        (core/load-installed-addons) ;; refresh our knowledge of what is installed

        (core/remove-addon (helper/select-addon (:url addon-2)))
        (is (= ["EveryAddon" "EveryAddon-BundledAddon"] (helper/install-dir-contents)))
        (is (= expected (nfo/read-nfo install-dir bundled-dirname)))))))

(deftest uninstall-addons-with-mutual-dependencies--overwritten
  (testing "uninstalling an addon whose mutual dependency *was overwritten* by another will see it removed from the list"
    (with-running-app
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

        (core/install-addon addon-1)
        (is (= ["EveryAddon" "EveryAddon-BundledAddon"] (helper/install-dir-contents)))
        (core/load-installed-addons) ;; refresh our knowledge of what is installed

        (core/install-addon addon-2)
        (is (= ["EveryAddon" "EveryAddon-BundledAddon" "EveryOtherAddon"] (helper/install-dir-contents)))
        (core/load-installed-addons) ;; refresh our knowledge of what is installed

        (core/remove-addon (helper/select-addon (:url addon-1)))
        (is (= ["EveryAddon-BundledAddon" "EveryOtherAddon"] (helper/install-dir-contents)))
        (is (= expected (nfo/read-nfo install-dir bundled-dirname)))))))

;;

(deftest http-500-downloading-catalogue
  (testing "HTTP 500 while fetching catalogue from github"
    (let [;; overrides fake route in `./test_helper.clj`
          fake-routes {"https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json"
                       {:get (fn [req] {:status 500 :host "raw.githubusercontent.com" :reason-phrase "500 Server Error"})}}

          expected ["downloading catalogue: short"
                    "failed to download file 'https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json': 500 Server Error (HTTP 500)"]]
      (with-fake-routes-in-isolation fake-routes
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
        (with-fake-routes-in-isolation fake-routes
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
        (with-fake-routes-in-isolation fake-routes
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
                          {;; hack, catalogue/format-catalogue-data orders the addon summary make them uncomparable
                           :total 1
                           :addon-summary-list [user-addon]})]

      (with-running-app
        (core/add-user-addon! user-addon)
        (is (= expected (catalogue/read-catalogue (core/paths :user-catalogue-file)))))))

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
                          {;; hack, catalogue/format-catalogue-data orders the addon summary make them uncomparable
                           :total 1
                           :addon-summary-list [user-addon]})]

      (with-running-app
        (core/add-user-addon! user-addon)
        (core/add-user-addon! user-addon)
        (core/add-user-addon! user-addon)
        (is (= expected (catalogue/read-catalogue (core/paths :user-catalogue-file))))))))

(deftest add+install-user-addon!
  (testing "user addon is successfully added to the user catalogue from just a github url"
    (let [every-addon-zip-file (fixture-path "everyaddon--1-2-3.zip")

          fake-routes {"https://api.github.com/repos/Aviana/HealComm/releases"
                       {:get (fn [req] {:status 200 :body (slurp (fixture-path "github-repo-releases--aviana-healcomm.json"))})}

                       "https://api.github.com/repos/Aviana/HealComm/contents"
                       {:get (fn [req] {:status 200 :body "[]"})}

                       "https://github.com/Aviana/HealComm/releases/download/2.04/HealComm.zip"
                       {:get (fn [req] {:status 200 :body (utils/file-to-lazy-byte-array every-addon-zip-file)})}}

          user-url "https://github.com/Aviana/HealComm"

          install-dir (helper/install-dir)

          expected-addon-dir (utils/join install-dir "EveryAddon")

          expected-user-catalogue [{:tag-list [],
                                    :game-track-list [],
                                    :updated-date "2019-10-09T17:40:04Z",
                                    :name "healcomm",
                                    :source "github",
                                    :label "HealComm",
                                    :download-count 30946,
                                    :source-id "Aviana/HealComm",
                                    :url "https://github.com/Aviana/HealComm"}]]
      (with-running-app
        (core/set-addon-dir! install-dir)
        (with-fake-routes-in-isolation fake-routes
          (core/add+install-user-addon! user-url)

          ;; addon was found and added to user catalogue
          (is (= expected-user-catalogue
                 (:addon-summary-list (catalogue/read-catalogue (core/paths :user-catalogue-file)))))

          ;; addon was successfully download and installed
          (is (fs/exists? expected-addon-dir)))))))

(deftest moosh-addons
  (testing "addons are mooshed correctly when a match is found in the db"
    (let [toc {:name "everyaddon"
               :label "EveryAddon"
               :description "Toc Description"
               :dirname "EveryAddon"
               :interface-version 70000
               :installed-version "1.2.3"}

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
                      :label "EveryAddon 1.2.3",
                      :name "everyaddon",
                      :primary? true,
                      :source "curseforge",
                      :source-id 1,
                      :update? false}]
        (core/install-addon addon)
        (is (= ["EveryAddon"] (helper/install-dir-contents)))
        (core/load-installed-addons)

        ;; todo: the below makes this a UI test. move test to cli_test.clj
        (cli/select-addons)
        (cli/ignore-selected) ;; calls `core/refresh`
        (is (= expected (first (core/get-state :installed-addon-list))))))))

(deftest clear-addon-ignore-flag
  (testing "an ignored addon can be 'unignored'"
    (with-running-app
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
                      :label "EveryAddon 1.2.3",
                      :name "everyaddon",
                      :primary? true,
                      :source "curseforge",
                      :source-id 1,
                      :update? false}]
        (core/install-addon addon)
        (core/load-installed-addons)

        ;; todo: the below makes this a UI test. move test to cli_test.clj
        (cli/select-addons)
        (cli/ignore-selected) ;; calls `core/refresh`
        (is (:ignore? (first (core/get-state :installed-addon-list))))

        (cli/clear-ignore-selected)
        (is (= expected (first (core/get-state :installed-addon-list))))))))

(deftest clear-addon-ignore-flag--group-addons
  (testing "an addon with an ignored group member can be 'unignored'. see issue#193"
    (with-running-app
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
        (core/install-addon addon)
        (nfo/ignore install-dir "EveryAddon-BundledAddon")
        (core/load-installed-addons)

        ;; todo: the below makes this a UI test. move test to cli_test.clj
        (cli/select-addons)
        (is (= expected (first (core/get-state :installed-addon-list))))

        (cli/clear-ignore-selected)
        (is (= expected-2 (first (core/get-state :installed-addon-list))))))))

(deftest clear-addon-ignore-flag--implicit-ignore
  (testing "an implicitly ignored (vcs) addon can be 'unignored' as well"
    (with-running-app
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
                      :label "EveryAddon 1.2.3",
                      :name "everyaddon",
                      :primary? true,
                      :source "curseforge",
                      :source-id 1,
                      :update? false}]
        (core/install-addon addon)
        (fs/mkdir (utils/join install-dir "EveryAddon" ".git"))
        (core/load-installed-addons)
        (is (:ignore? (first (core/get-state :installed-addon-list)))) ;; implicitly ignored

        ;; todo: the below makes this a UI test. move test to cli_test.clj
        (cli/select-addons)
        (cli/clear-ignore-selected)
        (is (= expected (first (core/get-state :installed-addon-list))))))))

