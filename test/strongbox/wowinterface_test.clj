(ns strongbox.wowinterface-test
  (:require
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [strongbox
    [wowinterface :as wowinterface]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   ))

(deftest format-wowinterface-dt
  (testing "conversion"
    (is (= "2018-09-07T13:27:00Z" (wowinterface/format-wowinterface-dt "09-07-18 01:27 PM")))))

(deftest scrape-category-list
  (let [fixture (slurp "test/fixtures/wowinterface-category-list.html")
        fake-routes {"https://www.wowinterface.com/downloads/foobar"
                     {:get (fn [_] {:status 200 :body fixture})}}
        num-categories 52
        first-category {:label "Action Bar Mods",
                        :url "https://www.wowinterface.com/downloads/index.php?cid=19&sb=dec_date&so=desc&pt=f&page=1"}
        last-category {:label "Discontinued and Outdated Mods",
                       :url "https://www.wowinterface.com/downloads/index.php?cid=44&sb=dec_date&so=desc&pt=f&page=1"}]
    (testing "a page of categories can be scraped"
      (with-fake-routes-in-isolation fake-routes
        (let [results (wowinterface/scrape-category-group-page "foobar")]
          (is (= num-categories (count results)))
          (is (= first-category (first results)))
          (is (= last-category (last results))))))))

(deftest scrape-category-page-range
  (testing "number of pages in a category is extracted correctly"
    (let [category {:label "dummy" :url "https://www.wowinterface.com/downloads/cat19.html"}
          fixture (slurp "test/fixtures/wowinterface-category-page.html")
          fake-routes {#".*" {:get (fn [_] {:status 200 :body fixture})}}
          expected (range 1 10)]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (wowinterface/scrape-category-page-range category)))))))

(deftest scrape-addon-list
  (testing "a single page of results from a category can be scraped"
    (let [category {:label "dummy" :url "https://www.wowinterface.com/downloads/cat19.html"}
          fixture (slurp "test/fixtures/wowinterface-category-page.html")
          fake-routes {"https://www.wowinterface.com/downloads/cat19.html"
                       {:get (fn [_] {:status 200 :body fixture})}}
          page 1
          num-addons 25
          first-addon {:url "https://www.wowinterface.com/downloads/info25079",
                       :name "rotation-master",
                       :label "Rotation Master",
                       :updated-date "2019-07-29T21:37:00Z",
                       :download-count 80,
                       :category-list #{"dummy"}
                       :source "wowinterface"
                       :source-id 25079}
          last-addon  {:url "https://www.wowinterface.com/downloads/info24805",
                       :name "mattbars-mattui",
                       :label "MattBars (MattUI)",
                       :updated-date "2018-10-30T17:56:00Z",
                       :download-count 1911,
                       :category-list #{"dummy"}
                       :source "wowinterface"
                       :source-id 24805}]
      (with-fake-routes-in-isolation fake-routes
        (let [results (wowinterface/scrape-addon-list category page)]
          (is (= num-addons (count results)))
          (is (= first-addon (first results)))
          (is (= last-addon (last results))))))))

(deftest addon-game-tracks-detected
  (testing "retail, classic and classic-tbc are successfully detected"
    (let [filelist {24876 [{:UIDownloadTotal "1223",
                            :UID 24876,
                            :UIDir ["TSM_StringConverter"],
                            :UIName "TradeSkillMaster String Converter",
                            :UISiblings nil,
                            :UIFileInfoURL "https://www.wowinterface.com/downloads/info24876-TradeSkillMasterStringConverter.html",
                            :UIDate 1619013116000,
                            :UIDonationLink "https://www.wowinterface.com/downloads/info24876#donate",
                            :UIIMGs ["https://cdn-wow.mmoui.com/preview/pvw70766.jpg" "https://cdn-wow.mmoui.com/preview/pvw70767.jpg" "https://cdn-wow.mmoui.com/preview/pvw70768.jpg"],
                            :UIDownloadMonthly "30",
                            :UICompatibility [{:version "2.5.1", :name "The Burning Crusade Classic"}
                                              {:version "9.0.5", :name "Shadowlands patch"}
                                              {:version "1.13.7", :name "Classic Patch"}],
                            :UIAuthorName "myrroddin",
                            :UIVersion "2.0.7",
                            :UIIMG_Thumbs ["https://cdn-wow.mmoui.com/preview/tiny/pvw70766.jpg" "https://cdn-wow.mmoui.com/preview/tiny/pvw70767.jpg" "https://cdn-wow.mmoui.com/preview/tiny/pvw70768.jpg"],
                            :UICATID "40",
                            :UIFavoriteTotal "1"}]}

          addon  {:url "https://www.wowinterface.com/downloads/info24876",
                  :name "tradeskillmaster-string-converter",
                  :label "TradeSkillMaster String Converter",
                  :source "wowinterface",
                  :source-id 24876,
                  :updated-date "2021-04-21T07:51:00Z",
                  :download-count 1223,
                  :category-list #{"Bags, Bank, Inventory"}}

          expected {:game-track-list [:classic :classic-tbc :retail],
                    :url "https://www.wowinterface.com/downloads/info24876"
                    :name "tradeskillmaster-string-converter",
                    :label "TradeSkillMaster String Converter",
                    :source "wowinterface",
                    :source-id 24876,
                    :updated-date "2021-04-21T07:51:00Z",
                    :download-count 1223,
                    :category-list #{"Bags, Bank, Inventory"}}]

      (is (= expected (wowinterface/expand-addon-with-filelist filelist addon))))))

(deftest addon-game-tracks-detected--null-compatibility
  (testing "when 'UICompatibility' is `null` we default to `:retail`."
    (let [expected [:retail]]
      (is (= expected (wowinterface/ui-compatibility-to-gametrack-list nil))))))
