(ns wowman.curseforge-test
  (:require
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
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

(deftest scrape-addon-summary-listing
  (testing "an (alphabetical) summary listing page can be scraped"
    (let [fixture (slurp "test/fixtures/curseforge-addon-summary-listing.html")
          scraped (curseforge/extract-addon-summary-list fixture)
          expected-num 20

          expected-first {:uri "https://www.curseforge.com/wow/addons/elonoris_pathfinder",
                          :name "elonoris_pathfinder",
                          :alt-name "elonorispathfinder"
                          :label "!Elonoris_Pathfinder",
                          :description "!Elonoris_Pathfinder is a Addon to get \"broken isles pathfinder flying\" or \"Verheerte Inseln Pfadfinder\" Achievement...",
                          :category-list ["Achievements" "Map & Minimap" "Quests & Leveling" "Tooltip" "Unit Frames"]
                          :created-date "2017-01-29T10:24:59Z",
                          :updated-date "2017-02-27T20:01:59Z",
                          :download-count 4100}

          expected-last {:uri "https://www.curseforge.com/wow/addons/mecs-seals-tempered-fate-broker",
                         :name "mecs-seals-tempered-fate-broker",
                         :alt-name "mecssealsoftemperedfatebroker"
                         :label "[MEC's] Seals of Tempered Fate broker",
                         :description "[MEC's] Seals of Tempered Fate broker",
                         :category-list ["Data Broker"],
                         :created-date "2015-05-13T16:18:35Z",
                         :updated-date "2017-06-14T14:31:56Z",
                         :download-count 1300}]
      (is (= (count scraped) expected-num))
      (is (= (first scraped) expected-first))
      (is (= (last scraped) expected-last))))

  (testing "a (updated) summary listing page can be scraped"
    (let [fixture (slurp "test/fixtures/curseforge-addon-summary-listing-updates.html")
          scraped (curseforge/extract-addon-summary-list-updates fixture)

          expected-first {:created-date "2008-07-10T16:30:06Z",
                          :description "Minimap addon of awesomeness. *chewing sound*. It'll nibble your hay pellets.",
                          :category-list ["Map & Minimap"],
                          :updated-date "2019-06-30T11:21:25Z",
                          :name "chinchilla",
                          :alt-name "chinchillaminimap",
                          :label "Chinchilla Minimap",
                          :download-count 1500000,
                          :uri "https://www.curseforge.com/wow/addons/chinchilla"}

          expected-last {:created-date "2016-07-20T22:50:09Z",
                         :description "GSE is an advanced macro editor that is an alternative to the limits provided by...",
                         :category-list ["Action Bars" "Combat" "Development Tools"],
                         :updated-date "2019-06-30T07:50:51Z",
                         :name "gse-gnome-sequencer-enhanced-advanced-macros",
                         :alt-name "gsegnomesequencerenhancedadvancedmacros",
                         :label "GSE: Gnome Sequencer Enhanced : Advanced Macros",
                         :download-count 1500000,
                         :uri "https://www.curseforge.com/wow/addons/gse-gnome-sequencer-enhanced-advanced-macros"}]

      (is (= (count scraped) 20))
      (is (= (first scraped) expected-first))
      (is (= (last scraped) expected-last)))))

(deftest scrape-addon
  (testing "scraping addon (without a donation link)"
    (let [fixture (slurp "test/fixtures/curseforge-addon-file--no-donation.html")
          summary {:name "datastore" :label "Datastore" :category-list []
                   :uri "https://www.curseforge.com/wow/addons/datastore"
                   :updated-date "2001-01-01T00:00:00Z"
                   :download-count 0}

          fake-routes {"https://www.curseforge.com/wow/addons/datastore/files"
                       {:get (fn [req] {:status 200 :body fixture})}}

          expected (merge summary {:interface-version 60000,
                                   :download-uri "https://www.curseforge.com/wow/addons/datastore/download/841838/file",
                                   :label "Datastore",
                                   :donation-uri nil,
                                   :version "6.0.002"})]
      (with-fake-routes-in-isolation fake-routes
        (is (= (curseforge/expand-summary summary) expected)))))

  (testing "scraping addon (with a donation link)"
    (let [fixture (slurp "test/fixtures/curseforge-addon-file--w.donation.html")
          summary {:name "details" :label "Details" :category-list []
                   :uri "https://www.curseforge.com/wow/addons/details"
                   :updated-date "2001-01-01T00:00:00Z"
                   :download-count 0}

          fake-routes {"https://www.curseforge.com/wow/addons/details/files"
                       {:get (fn [req] {:status 200 :body fixture})}}

          expected (merge summary
                          {:interface-version 80200,
                           :download-uri "https://www.curseforge.com/wow/addons/details/download/2730880/file",
                           :donation-uri "https://www.paypal.com/cgi-bin/webscr?return=https://www.curseforge.com/projects/61284?gameCategorySlug=addons&projectSlug=details&cn=Add+special+instructions+to+the+addon+author()&business=terciob19%40hotmail.com&bn=PP-DonationsBF:btn_donateCC_LG.gif:NonHosted&cancel_return=https://www.curseforge.com/projects/61284?gameCategorySlug=addons&projectSlug=details&lc=US&item_name=Details!+Damage+Meter+(from+curseforge.com)&cmd=_donations&rm=1&no_shipping=1&currency_code=USD",
                           :version "v8.2.0-v1.13.2-7135.139"})]
      (with-fake-routes-in-isolation fake-routes
        (is (= (curseforge/expand-summary summary) expected)))))

  (testing "scraping addon with no files"
    (let [fixture (slurp "test/fixtures/curseforge-addon-file--no-files.html")
          summary {:name "details" :label "Details" :category-list []
                   :uri "https://www.curseforge.com/wow/addons/details"
                   :updated-date "2001-01-01T00:00:00Z"
                   :download-count 0}

          fake-routes {"https://www.curseforge.com/wow/addons/details/files"
                       {:get (fn [req] {:status 200 :body fixture})}}

          expected nil]

      (with-fake-routes-in-isolation fake-routes
        (is (= (curseforge/expand-summary summary) expected))))))

(deftest num-summary-pages
  (testing "extraction of the number of summary pages"
    (let [fixture (slurp "test/fixtures/curseforge-addon-summary-listing.html")
          fake-routes {"https://www.curseforge.com/wow/addons?filter-sort=name&page=1"
                       {:get (fn [req] {:status 200 :body fixture})}}
          expected 346]
      (with-fake-routes-in-isolation fake-routes
        (is (= (curseforge/num-summary-pages) expected))))))
