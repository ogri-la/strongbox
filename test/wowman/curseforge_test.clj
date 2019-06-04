(ns wowman.curseforge-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [wowman
    [core :as core]
    [curseforge :as curseforge]
    [utils :as utils :refer [join]]]
   [me.raynes.fs :as fs]
   [taoensso.timbre :as log :refer [debug info warn error spy]]))

(def ^:dynamic temp-dir-path "")

(defn tempdir-fixture
  "each test has a temporary dir available to it"
  [f]
  (binding [temp-dir-path (str (fs/temp-dir "wowman.curseforge-test."))]
    (debug "created temp dir" temp-dir-path)
    (f)
    (debug "destroying temp dir" temp-dir-path)
    (fs/delete-dir temp-dir-path)))

(use-fixtures :each tempdir-fixture)

;; local
(def toc
  {:name "everyaddon",
   :description "Does what no other addon does, slightly differently"
   :dirname "EveryAddon",
   :label "EveryAddon 1.2.3",
   :interface-version 70000,
   :installed-version "1.2.3"})

;; remote
(def addon
  {:name  "everyaddon",
   :created-date  "2009-02-08T13:30:30Z",
   :updated-date  "2016-09-08T14:18:33Z",
   :download-count 1
   :description  "Does what no other addon does, slightly differently"
   :category-list  ["Auction & Economy", "Data Broker"],
   :interface-version  70000,
   :download-uri  "https://www.example.org/wow/addons/everyaddon/download/123456/file",
   :label  "EveryAddon",
   :donation-uri  nil,
   :uri  "https://www.example.org/wow/addons/everyaddon",
   :version  "1.2.3"})

(def toc-addon (core/merge-addons toc addon))

;; TODO: move to core_test.clj
(deftest install-addon
  (testing "installing an addon"
    (let [install-dir temp-dir-path
          ;; move dummy addon file into place so there is no cache miss
          fname (core/downloaded-addon-fname (:name addon) (:version addon))
          _ (utils/cp (join "test" "fixtures" fname) install-dir)
          file-list (core/install-addon addon install-dir)]

      (testing "addon directory created, single file written (.wowman.json nfo file)"
        (is (= (count file-list) 1))
        (is (fs/exists? (first file-list)))))))

(deftest install-bad-addon
  (testing "installing a bad addon"
    (let [install-dir temp-dir-path
          fname (core/downloaded-addon-fname (:name addon) (:version addon))
          _ (fs/copy "test/fixtures/bad-truncated.zip" (join install-dir fname))] ;; hoho, so evil
      (is (= (core/install-addon addon install-dir) nil))
      (is (= (count (fs/list-dir install-dir)) 0))))) ;; bad zip file deleted

(comment "why disabled?"
         (deftest install-addon-from-toc-addon
           (let [install-dir temp-dir-path
        ;; move dummy addon file into place so there is no cache miss
                 fname (core/downloaded-addon-fname (:name addon) (:version addon))
                 _ (utils/cp (join "./resources" fname) temp-dir-path)]
             (testing "installing from a toc-addon (merged toc-file + addon) type"
               (let [nfo-file-list (core/install-addon install-dir toc-addon)]
                 (testing "addon directory created, single nfo file written"
                   (is (= (count nfo-file-list) 1))
                   (is (fs/exists? (first nfo-file-list)))))))))

(deftest scrape-addon-summary
  (let [fixture (slurp "test/fixtures/addon-summary-listing.html")
        scraped (curseforge/extract-addon-summary-list fixture)
        expected-first {:uri "https://www.curseforge.com/wow/addons/auto-toast"
                        :name "auto-toast"
                        :label "Achievement Broadcaster"
                        :description "Alert your friends when you ding, get an achivement, or get phat lewts!"
                        :category-list ["Chat & Communication" "Mail" "Quests & Leveling" "Achievements"]
                        :created-date "2010-07-15T20:55:54Z"
                        :updated-date "2016-04-19T17:00:28Z"
                        :download-count 7357
                        :alt-name "achievementbroadcaster"}]
    (is (= 20 (count scraped)))
    (is (= expected-first (first scraped)))))

(deftest scrape-contrived-summary
  (let [fixture "<ul class='listing'><li class='project-list-item'>
    <div class='list-item__details'>
        <a href='/wow/addons/arl'>
            <h2 class='list-item__title'>
                Ackis Recipe List
            </h2>
        </a>
        <p class='list-item__stats'>
            <span class='count--download'>230,257,314 </span>
            <span class='date--updated'>Updated <abbr data-epoch='1504050180'>Aug 29, 2017</abbr></span>
            <span class='date--created'>Created <abbr data-epoch='1207377654'>Apr 4, 2008</abbr></span>
        </p>
        <div class='list-item__description'>
            <p>Ackis Recipe List is an addon which will scan your trade skills and provide information...</p>
        </div>
    </div>

    <div class='list-item__categories'>
        <div class='list--item--categories-container'>
            <a title='Data Export' class='category__item'></a>
            <a title='Professions' class='category__item'></a>
        </div>
    </div>

</li></ul>"

        scraped (curseforge/extract-addon-summary-list fixture)
        expected '({:uri "https://www.curseforge.com/wow/addons/arl",
                    :name "arl"
                    :label "Ackis Recipe List"
                    :description "Ackis Recipe List is an addon which will scan your trade skills and provide information..."
                    :category-list ["Data Export" "Professions"]
                    :updated-date "2017-08-29T23:43:00Z"
                    :created-date "2008-04-05T06:40:54Z"
                    :download-count 230257314
                    :alt-name "ackisrecipelist"})]

    (is (= expected scraped))))
