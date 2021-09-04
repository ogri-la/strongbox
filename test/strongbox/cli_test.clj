(ns strongbox.cli-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [strongbox.ui.cli :as cli]
   [clj-http.fake :refer [with-global-fake-routes-in-isolation]]
   [strongbox
    [utils :as utils]
    [logging :as logging]
    [main :as main]
    [catalogue :as catalogue]
    [core :as core]]
   [me.raynes.fs :as fs :refer [with-cwd]]
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [strongbox.test-helper :as helper :refer [with-running-app with-running-app+opts fixture-path]]))

(use-fixtures :each helper/fixture-tempcwd)

(deftest action
  (let [expected "0 installed\n0 updates\n"]
    (with-running-app
      (testing "action func can accept a plain keyword"
        (is (= (with-out-str (cli/action :list-updates)) expected)))
      (testing "action func can accept a map of args"
        (is (= (with-out-str (cli/action {:action :list-updates})) expected))))))

(deftest shameless-coverage-bump
  (let [safe-actions [:???
                      :list :list-updates :update-all]]
    (with-running-app
      (doseq [action-kw safe-actions]
        (testing (str "cli: " action)
          (cli/action action-kw))))))

(deftest write-catalogue
  (with-running-app
    (let [full (core/find-catalogue-local-path :full)
          short (core/find-catalogue-local-path :short)

          curse (core/find-catalogue-local-path :curseforge)
          wowi (core/find-catalogue-local-path :wowinterface)
          tukui (core/find-catalogue-local-path :tukui)]

      ;; copy some fixtures
      (fs/copy (fixture-path "catalogue--v2--curseforge.json") curse)
      (fs/copy (fixture-path "catalogue--v2--wowinterface.json") wowi)
      (fs/copy (fixture-path "catalogue--v2--tukui.json") tukui)

      (cli/action :write-catalogue)

      (testing "full and shortened catalogues were written"
        (is (fs/exists? (core/find-catalogue-local-path :full)))
        (is (fs/exists? (core/find-catalogue-local-path :short))))

      (testing "each catalogue has one addon each"
        (is (= 3 (-> full catalogue/read-catalogue :total))))

      (testing "the short catalogue has just one addon in range"
        (is (= 1 (-> short catalogue/read-catalogue :total)))))))

(deftest search-db--empty-db
  (testing "an empty database can be searched from the CLI"
    (with-running-app
      (let [expected []]
        (cli/search "foo")
        (Thread/sleep 10)
        (is (= expected (cli/search-results)))))))

(deftest search-db
  (testing "a populated database can be searched from the CLI"
    (let [catalogue (slurp (fixture-path "catalogue--v2.json"))
          fake-routes {"https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json"
                       {:get (fn [req] {:status 200 :body catalogue})}}
          expected [{:download-count 9,
                     :game-track-list [:retail :classic],
                     :label "Chinchilla",
                     :name "chinchilla",
                     :source "github",
                     :source-id "Ravendwyr/Chinchilla",
                     :tag-list [],
                     :updated-date "2019-10-19T15:07:07Z",
                     :url "https://github.com/Ravendwyr/Chinchilla"}]]
      (with-global-fake-routes-in-isolation fake-routes
        (with-running-app
          (cli/search "chin")
          (Thread/sleep 10)
          (is (= expected (cli/search-results))))))))

(deftest search-db--random
  (testing "a populated database can be randomly searched from the CLI"
    (let [catalogue (slurp (fixture-path "catalogue--v2.json"))
          fake-routes {"https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json"
                       {:get (fn [req] {:status 200 :body catalogue})}}]
      (with-global-fake-routes-in-isolation fake-routes
        (with-running-app
          ;; any catalogue with less than 60 (a magic number) items has >100% probability of being included.
          (cli/random-search)
          (Thread/sleep 50)
          (is (= (core/get-state :db)
                 (cli/search-results))))))))

(deftest search-db--empty-term
  (testing "a populated database can be randomly searched from the CLI by passing in empty values"
    (let [catalogue (slurp (fixture-path "catalogue--v2.json"))
          fake-routes {"https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json"
                       {:get (fn [req] {:status 200 :body catalogue})}}]
      (with-global-fake-routes-in-isolation fake-routes
        (with-running-app
          ;; any catalogue with less than 60 (a magic number) items has >100% probability of being included.
          (cli/search nil)
          (Thread/sleep 10)
          (is (= "" (core/get-state :search :term)))
          (cli/search "")
          (Thread/sleep 10)
          (is (= nil (core/get-state :search :term))))))))

(deftest search-db--navigate
  (testing "a populated database can be searched forwards and backwards from the CLI"
    (let [catalogue (slurp (fixture-path "catalogue--v2.json"))
          fake-routes {"https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json"
                       {:get (fn [req] {:status 200 :body catalogue})}}

          expected-page-1 [{:created-date "2019-04-13T15:23:09.397Z",
                            :description "A New Simple Percent",
                            :download-count 1034,
                            :label "A New Simple Percent",
                            :name "a-new-simple-percent",
                            :source "curseforge",
                            :source-id 319346,
                            :tag-list [:unit-frames],
                            :updated-date "2019-10-29T22:47:42.463Z",
                            :url "https://www.curseforge.com/wow/addons/a-new-simple-percent"}]

          expected-page-2 [{:download-count 9,
                            :game-track-list [:retail :classic],
                            :label "Chinchilla",
                            :name "chinchilla",
                            :source "github",
                            :source-id "Ravendwyr/Chinchilla",
                            :tag-list [],
                            :updated-date "2019-10-19T15:07:07Z",
                            :url "https://github.com/Ravendwyr/Chinchilla"}]

          no-results []]

      (with-global-fake-routes-in-isolation fake-routes
        (with-running-app
          (swap! core/state assoc-in [:search :results-per-page] 1)
          (cli/search "c")
          (Thread/sleep 100)
          (is (= 1 (count (cli/search-results))))
          (is (= expected-page-1 (cli/search-results)))
          (is (cli/search-has-next?))
          (cli/search-results-next-page)
          (is (= expected-page-2 (cli/search-results)))

          ;; with 1 result per-page and exactly 1 result on this page, there may be more results
          ;; but we can't know for definite without realising the next page of results.
          (is (cli/search-has-next?))
          (cli/search-results-next-page)
          ;; in this case, there wasn't. 
          (is (= no-results (cli/search-results)))

          ;; now walk backwards
          (is (cli/search-has-prev?))
          (cli/search-results-prev-page)
          (is (= expected-page-2 (cli/search-results)))

          (is (cli/search-has-prev?))
          (cli/search-results-prev-page)
          (is (= expected-page-1 (cli/search-results)))

          (is (not (cli/search-has-prev?))))))))

(deftest pin-addon
  (testing "an addon can be installed, selected and pinned to it's current installed version"
    (let [addon {:name "everyaddon-classic" :label "EveryAddon (Classic)" :version "1.2.3" :url "https://group.id/never/fetched"
                 :source "curseforge" :source-id 1
                 :download-url "https://path/to/remote/addon.zip"
                 :game-track :classic
                 :-testing-zipfile (fixture-path "everyaddon-classic--1-2-3.zip")}]
      (with-running-app

        ;; 2021-09-04: change in behaviour. addons that no longer match the catalogue are still checked for
        ;; updates if the right toc+nfo data is available.
        (with-global-fake-routes-in-isolation
          {"https://addons-ecs.forgesvc.net/api/v2/addon/1"
           {:get (fn [req] {:status 404 :reason-phrase "not found"})}}

          (cli/set-addon-dir! (helper/install-dir))
          (core/install-addon-guard addon)
          (core/load-installed-addons)

          (let [addon (first (core/get-state :installed-addon-list))]
            (is (= "1.2.3" (:installed-version addon)))
            (is (not (contains? addon :pinned-version))))

          (cli/select-addons)
          (is (= 1 (count (core/get-state :selected-addon-list))))
          (cli/pin)

          (let [addon (first (core/get-state :installed-addon-list))]
            (is (= "1.2.3" (:pinned-version addon)))))))))

(deftest unpin-addon
  (testing "an addon can be installed, selected and un-pinned"
    (let [addon {:name "everyaddon-classic" :label "EveryAddon (Classic)" :version "1.2.3" :url "https://group.id/never/fetched"
                 :source "curseforge" :source-id 1
                 :download-url "https://path/to/remote/addon.zip"
                 :game-track :classic
                 :-testing-zipfile (fixture-path "everyaddon-classic--1-2-3.zip")
                 ;; yes! we can installed an addon that is pre-pinned.
                 :pinned-version "1.2.3"}]
      (with-running-app

        ;; 2021-09-04: change in behaviour. addons that no longer match the catalogue are still checked for
        ;; updates if the right toc+nfo data is available.
        (with-global-fake-routes-in-isolation
          {"https://addons-ecs.forgesvc.net/api/v2/addon/1"
           {:get (fn [req] {:status 404 :reason-phrase "not found"})}}

          (cli/set-addon-dir! (helper/install-dir))
          (core/install-addon-guard addon)
          (core/load-installed-addons)

          (let [addon (first (core/get-state :installed-addon-list))]
            (is (= "1.2.3" (:installed-version addon)))
            (is (= "1.2.3" (:pinned-version addon))))

          (cli/select-addons)
          (is (= 1 (count (core/get-state :selected-addon-list))))
          (cli/unpin)

          (let [addon (first (core/get-state :installed-addon-list))]
            (is (not (contains? addon :pinned-version)))))))))

(deftest add-tab
  (testing "a generic tab can be created"
    (let [expected [{:tab-id "foo" :label "Foo!" :closable? false :log-level :info :tab-data {:dirname "EveryAddon"}}]]
      (with-running-app
        (is (= [] (core/get-state :tab-list)))
        (cli/add-tab "foo" "Foo!" false {:dirname "EveryAddon"})
        (is (= expected (core/get-state :tab-list)))))))

(deftest add-addon-tab
  (testing "an addon can be used to create a tab"
    (let [addon {:source "curseforge" :source-id 123 :label "Foo"}
          expected [{:closable? true, :label "Foo", :tab-data {:source "curseforge", :source-id 123}, :tab-id "foobar" :log-level :info}]]
      (with-running-app
        (with-redefs [strongbox.utils/unique-id (constantly "foobar")]
          (cli/add-addon-tab addon))
        (is (= expected (core/get-state :tab-list)))))))

(deftest remove-all-tabs
  (testing "all tabs can be removed at once"
    (let [tab-list [{:tab-id "foo" :label "Foo!" :closable? false :log-level :info :tab-data {:dirname "EveryAddon"}}
                    {:tab-id "bar" :label "Bar!" :closable? true :log-level :info :tab-data {:dirname "EveryOtherAddon"}}]
          expected []]
      (with-running-app
        (cli/add-tab "foo" "Foo!" false {:dirname "EveryAddon"})
        (cli/add-tab "bar" "Bar!" true {:dirname "EveryOtherAddon"})
        (is (= tab-list (core/get-state :tab-list)))
        (cli/remove-all-tabs)
        (is (= expected (core/get-state :tab-list)))))))

(deftest remove-tab-at-idx
  (testing "all tabs can be removed at once"
    (let [tab-list [{:tab-id "foo" :label "Foo!" :closable? false :log-level :info :tab-data {:dirname "EveryAddon"}}
                    {:tab-id "bar" :label "Bar!" :closable? true :log-level :info :tab-data {:dirname "EveryOtherAddon"}}
                    {:tab-id "baz" :label "Baz!" :closable? false :log-level :info :tab-data {:dirname "EveryAddonClassic"}}]
          expected [(first tab-list)
                    (last tab-list)]]
      (with-running-app
        (cli/add-tab "foo" "Foo!" false {:dirname "EveryAddon"})
        (cli/add-tab "bar" "Bar!" true {:dirname "EveryOtherAddon"})
        (cli/add-tab "baz" "Baz!" false {:dirname "EveryAddonClassic"})
        (is (= tab-list (core/get-state :tab-list)))
        (cli/remove-tab-at-idx 1)
        (is (= expected (core/get-state :tab-list)))))))

(deftest addon-num-log-level
  (with-running-app
    (is (zero? (cli/addon-num-log-level :warn "EveryAddon")))
    (is (zero? (cli/addon-num-log-level :error "EveryAddon")))

    (logging/addon-log {:dirname "EveryAddon"} :warn "awooga") ;; warn #1
    (logging/with-addon {:dirname "EveryAddon"}
      (timbre/warn "wooooooga")) ;; warn #2

    (is (= 2 (cli/addon-num-log-level :warn "EveryAddon")))
    (is (zero? (cli/addon-num-log-level :error "EveryAddon")))

    (logging/addon-log {:dirname "EveryAddon"} :error "AWOOGA!")
    (is (= 2 (cli/addon-num-log-level :warn "EveryAddon")))
    (is (= 1 (cli/addon-num-log-level :error "EveryAddon")))))

(deftest addon-has-log-level
  (with-running-app
    (is (false? (cli/addon-has-log-level? :warn "EveryAddon")))
    (logging/addon-log {:dirname "EveryAddon"} :warn "warning message")
    (is (true? (cli/addon-has-log-level? :warn "EveryAddon")))
    (is (false? (cli/addon-has-log-level? :error "EveryAddon")))))

(deftest import-addon--github
  (testing "user addon is successfully added to the user catalogue from a github url"
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
        (with-global-fake-routes-in-isolation fake-routes
          (cli/import-addon user-url)

          ;; addon was found and added to user catalogue
          (is (= expected-user-catalogue
                 (:addon-summary-list (catalogue/read-catalogue (core/paths :user-catalogue-file)))))

          ;; addon was successfully download and installed
          (is (fs/exists? expected-addon-dir)))))))

(deftest import-addon--wowinterface
  (testing "user addon is successfully added to the user catalogue from a wowinterface url"
    (let [install-dir (helper/install-dir)

          match {:url "https://www.wowinterface.com/downloads/info25079",
                 :name "rotation-master",
                 :label "Rotation Master",
                 :updated-date "2019-07-29T21:37:00Z",
                 :download-count 80,
                 :source "wowinterface"
                 :source-id 25079
                 :game-track-list [:retail]
                 :tag-list []}

          ;; a mush of the above (.nfo written during install) and the EveryAddon .toc file
          expected {:description "Does what no other addon does, slightly differently",
                    :dirname "EveryAddon",
                    :group-id "https://www.wowinterface.com/downloads/info25079",
                    :installed-game-track :retail,
                    :installed-version "1.2.3",
                    :interface-version 70000,
                    :label "EveryAddon 1.2.3",
                    :name "rotation-master",
                    :primary? true,
                    :source "wowinterface",
                    :source-id 25079}

          expected-addon-dir (utils/join install-dir "EveryAddon")
          expected-user-catalogue [match]

          catalogue (utils/to-json (catalogue/new-catalogue [match]))

          every-addon-zip-file (fixture-path "everyaddon--1-2-3.zip")
          fake-routes {"https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json"
                       {:get (fn [req] {:status 200 :body catalogue})}

                       "https://api.mmoui.com/v3/game/WOW/filedetails/25079.json"
                       {:get (fn [req] {:status 200 :body (slurp (fixture-path "wowinterface-api--addon-details.json"))})}

                       "https://cdn.wowinterface.com/downloads/getfile.php?id=25079"
                       {:get (fn [req] {:status 200 :body (utils/file-to-lazy-byte-array every-addon-zip-file)})}}

          user-url (:url match)]

      (with-global-fake-routes-in-isolation fake-routes
        (with-running-app
          (core/set-addon-dir! install-dir)

          ;; one addon in the database
          (is (= [match] (core/get-state :db)))

          ;; user gives us this url, we find it and install it
          (cli/import-addon user-url)

          ;; addon was successfully download and installed
          (is (fs/exists? expected-addon-dir))

          ;; re-read install dir
          (core/load-installed-addons)

          ;; we expect our mushy set of .nfo and .toc data
          (is (= [expected] (core/get-state :installed-addon-list)))

          ;; and that the addon was added to the user catalogue
          (is (= expected-user-catalogue
                 (:addon-summary-list (catalogue/read-catalogue (core/paths :user-catalogue-file))))))))))

(deftest import-addon--curseforge
  (testing "user addon is successfully added to the user catalogue from a curseforge url"
    (let [install-dir (helper/install-dir)

          match {:created-date "2010-05-07T18:48:16Z",
                 :description "Does what no other addon does, slightly differently",
                 :tag-list [:bags :inventory]
                 :updated-date "2019-06-26T01:21:39Z",
                 :name "everyaddon",
                 :source "curseforge",
                 :label "EveryAddon",
                 :download-count 3000000,
                 :source-id 1,
                 :url "https://www.curseforge.com/wow/addons/everyaddon"}

          ;; a mush of the above (.nfo written during install) and the EveryAddon .toc file
          expected {:description "Does what no other addon does, slightly differently",
                    :dirname "EveryAddon",
                    :group-id "https://www.curseforge.com/wow/addons/everyaddon",
                    :installed-game-track :retail,
                    :installed-version "v8.2.0-v1.13.2-7135.139",
                    :interface-version 70000,
                    :label "EveryAddon 1.2.3",
                    :name "everyaddon",
                    :primary? true,
                    :source "curseforge",
                    :source-id 1}

          expected-addon-dir (utils/join install-dir "EveryAddon")
          expected-user-catalogue [match]

          catalogue (utils/to-json (catalogue/new-catalogue [match]))

          every-addon-zip-file (fixture-path "everyaddon--1-2-3.zip")
          fake-routes {"https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json"
                       {:get (fn [req] {:status 200 :body catalogue})}

                       "https://addons-ecs.forgesvc.net/api/v2/addon/1"
                       {:get (fn [req] {:status 200 :body (slurp (fixture-path "curseforge-api-addon--everyaddon.json"))})}

                       "https://edge.forgecdn.net/files/1/1/EveryAddon.zip"
                       {:get (fn [req] {:status 200 :body (utils/file-to-lazy-byte-array every-addon-zip-file)})}}

          user-url (:url match)]

      (with-global-fake-routes-in-isolation fake-routes
        (with-running-app
          (core/set-addon-dir! install-dir)

          ;; one addon in the database
          (is (= [match] (core/get-state :db)))

          ;; user gives us this url, we find it and install it
          (cli/import-addon user-url)

          ;; addon was successfully download and installed
          (is (fs/exists? expected-addon-dir))

          ;; re-read install dir
          (core/load-installed-addons)

          ;; we expect our mushy set of .nfo and .toc data
          (is (= [expected] (core/get-state :installed-addon-list)))

          ;; and that the addon was added to the user catalogue
          (is (= expected-user-catalogue
                 (:addon-summary-list (catalogue/read-catalogue (core/paths :user-catalogue-file))))))))))

(deftest import-addon--tukui
  (testing "user addon is successfully added to the user catalogue from a tukui url"
    (let [install-dir (helper/install-dir)

          match {:description "Add roleplaying fields to ElvUI to create RP UIs.",
                 :tag-list [:roleplay]
                 :game-track-list [:retail],
                 :updated-date "2019-07-29T20:48:25Z",
                 :name "-rp-tags",
                 :source "tukui",
                 :label "[rp:tags]",
                 :download-count 2838,
                 :source-id 98,
                 :url "https://www.tukui.org/addons.php?id=98"}

          ;; a mush of the above (.nfo written during install) and the EveryAddon .toc file
          expected {:description "Does what no other addon does, slightly differently",
                    :dirname "EveryAddon",
                    :group-id "https://www.tukui.org/addons.php?id=98",
                    :installed-game-track :retail,
                    :installed-version "0.960",
                    :interface-version 70000,
                    :label "EveryAddon 1.2.3",
                    :name "-rp-tags",
                    :primary? true,
                    :source "tukui",
                    :source-id 98}

          expected-addon-dir (utils/join install-dir "EveryAddon")
          expected-user-catalogue [match]

          catalogue (utils/to-json (catalogue/new-catalogue [match]))

          every-addon-zip-file (fixture-path "everyaddon--1-2-3.zip")
          fake-routes {"https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json"
                       {:get (fn [req] {:status 200 :body catalogue})}

                       "https://www.tukui.org/api.php?addons"
                       {:get (fn [req] {:status 200 :body (slurp (fixture-path "tukui--addon-details.json"))})}

                       "https://www.tukui.org/addons.php?download=98"
                       {:get (fn [req] {:status 200 :body (utils/file-to-lazy-byte-array every-addon-zip-file)})}}

          user-url (:url match)]

      (with-global-fake-routes-in-isolation fake-routes
        (with-running-app
          (core/set-addon-dir! install-dir)

          ;; one addon in the database
          (is (= [match] (core/get-state :db)))

          ;; user gives us this url, we find it and install it
          (cli/import-addon user-url)

          ;; addon was successfully download and installed
          (is (fs/exists? expected-addon-dir))

          ;; re-read install dir
          (core/load-installed-addons)

          ;; we expect our mushy set of .nfo and .toc data
          (is (= [expected] (core/get-state :installed-addon-list)))

          ;; and that the addon was added to the user catalogue
          (is (= expected-user-catalogue
                 (:addon-summary-list (catalogue/read-catalogue (core/paths :user-catalogue-file))))))))))

(deftest refresh-user-catalogue
  (testing "the user catalogue can be 'refreshed', pulling in updated information from github and the current catalogue"
    (with-running-app+opts {:ui nil}
      ;; start with an up-to-date catalogue and 'old' user catalogue
      ;; call `cli/refresh-user-catalogue`
      ;; provide dummy updates
      ;; expect new values

      (let [;; user-catalogue with a bunch of addons across all hosts that the user has added.
            user-catalogue-fixture (fixture-path "user-catalogue--populated.json")

            ;; default app catalogue, contains newer versions of the addon summaries in the user-catalogue.
            ;; this is because the catalogue is updated periodically and the user-catalogue is not.
            short-catalogue (slurp (fixture-path "user-catalogue--short-catalogue.json"))

            tukui-fixture (slurp (fixture-path "user-catalogue--tukui.json"))
            tukui-classic-fixture (slurp (fixture-path "user-catalogue--tukui-classic.json"))
            tukui-classic-tbc-fixture (slurp (fixture-path "user-catalogue--tukui-classic-tbc.json"))
            curseforge-fixture (slurp (fixture-path "user-catalogue--curseforge.json"))
            wowinterface-fixture (slurp (fixture-path "user-catalogue--wowinterface.json"))
            github-fixture (slurp (fixture-path "user-catalogue--github.json"))
            github-contents-fixture (slurp (fixture-path "user-catalogue--github-contents.json"))
            github-toc-fixture (slurp (fixture-path "user-catalogue--github-toc.json"))

            fake-routes {"https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json"
                         {:get (fn [req] {:status 200 :body short-catalogue})}

                         "https://www.tukui.org/api.php?addons"
                         {:get (fn [req] {:status 200 :body tukui-fixture})}

                         "https://www.tukui.org/api.php?classic-addons"
                         {:get (fn [req] {:status 200 :body tukui-classic-fixture})}

                         "https://www.tukui.org/api.php?classic-tbc-addons"
                         {:get (fn [req] {:status 200 :body tukui-classic-tbc-fixture})}

                         "https://api.github.com/repos/Stanzilla/AdvancedInterfaceOptions/releases"
                         {:get (fn [req] {:status 200 :body github-fixture})}

                         "https://api.github.com/repos/Stanzilla/AdvancedInterfaceOptions/contents"
                         {:get (fn [req] {:status 200 :body github-contents-fixture})}

                         "https://raw.githubusercontent.com/Stanzilla/AdvancedInterfaceOptions/master/AdvancedInterfaceOptions.toc"
                         {:get (fn [req] {:status 200 :body github-toc-fixture})}

                         "https://api.mmoui.com/v3/game/WOW/filedetails/24566.json"
                         {:get (fn [req] {:status 200 :body wowinterface-fixture})}

                         "https://addons-ecs.forgesvc.net/api/v2/addon/13501"
                         {:get (fn [req] {:status 200 :body curseforge-fixture})}}]

        (helper/install-dir)
        (fs/copy user-catalogue-fixture (core/paths :user-catalogue-file))
        (with-global-fake-routes-in-isolation fake-routes
          (cli/start {})
          ;; prevents the `*/stop` functions from skipping out on cleaning up (I think)
          (swap! core/state assoc :cli-opts {:ui :cli})

          ;; sanity check, ensure user-catalogue loaded
          (is (= 6 (count (core/get-state :db))))

          ;; we need to load the short-catalogue using newer versions of what is in the user-catalogue
          ;; the user-catalogue is then matched against db, the newer summary returned and written to the user catalogue

          (let [;; I've modified the user-catalogue--populated.json fixture to be 'older' that the short-catalogue fixture by
                ;; decrementing the download counts by 1. when the user-catalogue is refreshed we expect 
                inc-downloads #(update % :download-count inc)
                today (utils/datestamp-now-ymd)
                expected-user-catalogue (-> (core/get-create-user-catalogue)
                                            (update-in [:addon-summary-list] #(mapv inc-downloads %))
                                            (assoc :datestamp today))]
            (cli/refresh-user-catalogue)
            ;; ensure new user-catalogue matches expectations
            (is (= expected-user-catalogue (core/get-create-user-catalogue)))))))))
