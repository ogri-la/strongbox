(ns wowman.wowinterface-test
  (:require
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [wowman
    [core :as core]
    [wowinterface :as wowinterface]
    [utils :as utils :refer [join]]]
   [me.raynes.fs :as fs]
   [taoensso.timbre :as log :refer [debug info warn error spy]]))

(deftest format-wowinterface-dt
  (testing "conversion"
    (is (= "2018-09-07T13:27:00Z" (wowinterface/format-wowinterface-dt "09-07-18 01:27 PM")))))

(deftest scrape-category-list
  (let [fixture (slurp "test/fixtures/wowinterface-category-list.html")
        fake-routes {"https://www.wowinterface.com/downloads/foobar"
                     {:get (fn [req] {:status 200 :body fixture})}}
        num-categories 52
        first-category {:label "Action Bar Mods",
                        :url "https://www.wowinterface.com/downloads/index.php?cid=19&sb=dec_date&so=desc&pt=f&page=1"}
        last-category {:label "Discontinued and Outdated Mods",
                       :url "https://www.wowinterface.com/downloads/index.php?cid=44&sb=dec_date&so=desc&pt=f&page=1"}]
    (testing "a page of categories can be scraped"
      (with-fake-routes-in-isolation fake-routes
        (let [results (wowinterface/parse-category-list "foobar")]
          (is (= num-categories (count results)))
          (is (= first-category (first results)))
          (is (= last-category (last results))))))))

(deftest scrape-category-page-range
  (testing "number of pages in a category is extracted correctly"
    (let [category {:label "dummy" :url "https://www.wowinterface.com/downloads/cat19.html"}
          fixture (slurp "test/fixtures/wowinterface-category-page.html")
          fake-routes {#".*" {:get (fn [req] {:status 200 :body fixture})}}
          expected (range 1 10)]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (wowinterface/scrape-category-page-range category)))))))

(deftest scrape-addon-page
  (testing "a single page of results from a category can be scraped"
    (let [category {:label "dummy" :url "https://www.wowinterface.com/downloads/cat19.html"}
          fixture (slurp "test/fixtures/wowinterface-category-page.html")
          fake-routes {"https://www.wowinterface.com/downloads/cat19.html"
                       {:get (fn [req] {:status 200 :body fixture})}}
          page 1
          expected []
          num-addons 25
          first-addon {:uri "https://www.wowinterface.com/downloads/info25079", :name "rotation-master", :label "Rotation Master", :updated-date "2019-07-29T21:37:00Z", :download-count 80, :category-list #{"dummy"}}
          last-addon  {:uri "https://www.wowinterface.com/downloads/info24805", :name "mattbars-mattui", :label "MattBars (MattUI)", :updated-date "2018-10-30T17:56:00Z", :download-count 1911, :category-list #{"dummy"}}]
      (with-fake-routes-in-isolation fake-routes
        (let [results (wowinterface/scrape-addon-page category page)]
          (is (= num-addons (count results)))
          (is (= first-addon (first results)))
          (is (= last-addon (last results))))))))
