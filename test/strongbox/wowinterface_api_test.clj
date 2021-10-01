(ns strongbox.wowinterface-api-test
  (:require
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [strongbox
    [test-helper :refer [fixture-path]]
    [wowinterface-api :as wowinterface-api]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   ))

(deftest expand-summary
  (testing "expand-summary correctly extracts and adds additional properties"
    (let [given {:url "https://www.wowinterface.com/downloads/info25079",
                 :name "rotation-master",
                 :label "Rotation Master",
                 :updated-date "2019-07-29T21:37:00Z",
                 :download-count 80,
                 :tag-list [:dummy]
                 :source "wowinterface"
                 :source-id 25079
                 :game-track-list [:retail]}

          game-track :retail

          expected [{:download-url "https://cdn.wowinterface.com/downloads/getfile.php?id=25079"
                     :version "1.2.3"
                     :game-track game-track}]

          fixture (slurp (fixture-path "wowinterface-api--addon-details.json"))
          fake-routes {"https://api.mmoui.com/v3/game/WOW/filedetails/25079.json"
                       {:get (fn [_] {:status 200 :body fixture})}}]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (wowinterface-api/expand-summary given game-track)))))))

(deftest download-addon-404
  (testing "regular addon fetch that yields a 404 returns nil"
    (let [given {:url "https://www.wowinterface.com/downloads/info25079",
                 :name "rotation-master",
                 :label "Rotation Master",
                 :updated-date "2019-07-29T21:37:00Z",
                 :download-count 80,
                 :tag-list [:dummy]
                 :source "wowinterface"
                 :source-id 25079
                 :game-track-list [:retail]}

          game-track :retail

          fake-routes {"https://api.mmoui.com/v3/game/WOW/filedetails/25079.json"
                       {:get (fn [req] {:status 404 :reason-phrase "Not Found" :body "<h1>Not Found</h1>"})}}]
      (with-fake-routes-in-isolation fake-routes
        (is (nil? (wowinterface-api/expand-summary given game-track)))))))

(deftest parse-user-string
  (testing "parsing wowinterface urls extracts the source-id"
    (let [cases [["https://www.wowinterface.com/downloads/info8882" 8882]
                 ["https://www.wowinterface.com/downloads/info8882-MasqueLiteStep.html" 8882]
                 ["https://www.wowinterface.com/downloads/download8882-MasqueLiteStep" 8882]

                 ["https://www.wowinterface.com/downloads/info23921-GSEGnomeSequencer-Enhanced-Advanc....html" 23921]

                 ;; path to an alternate download.
                 ;; would be nice to support but has nothing linking it to the catalogue
                 ["https://www.wowinterface.com/downloads/dlfile1536/Masque_LiteStep-9.0.6-bcc.zip?1621368578" nil]]]
      (doseq [[given expected] cases]
        (is (= expected (wowinterface-api/parse-user-string given)))))))

(deftest extract-aid
  (testing "the 'aid' query parameter can be extracted from a url if present"
    (let [cases [[nil nil]
                 ["" nil]
                 ["https://cdn.wowinterface.com/downloads/getfile.php?id=19662&d=1631009549&minion" nil]
                 ["https://cdn.wowinterface.com/downloads/getfile.php?id=19662&aid=119903&d=1631009549&minion" "119903"]
                 ["&aid=asdf" nil]]]
      (doseq [[given expected] cases]
        (is (= expected (wowinterface-api/extract-aid given)))))))
