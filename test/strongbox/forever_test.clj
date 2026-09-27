(ns strongbox.forever-test
  "WoW Forever addons, from `release.json` files, Github releases and installed addons."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [me.raynes.fs :as fs]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
   [strongbox
    [utils :as utils :refer [join]]
    [toc :as toc]
    [addon :as addon]
    [release-json :as release-json]
    [github-api :as github-api]
    [test-helper :as helper]]))

(use-fixtures :each helper/fixture-tempcwd)

(defn toc-contents
  [title interface]
  (format "## Interface: %s\n## Title: %s\n## Version: 1.2.3\n\nEveryAddon.lua\n" interface title))

(defn mock-addon!
  "creates an addon directory `dirname` in the install dir containing each `{filename contents}` in `toc-map`.
  returns the path to the addon directory."
  [dirname toc-map]
  (let [addon-dir (join (helper/install-dir) dirname)]
    (fs/mkdir addon-dir)
    (spit (join addon-dir "EveryAddon.lua") "")
    (doseq [[filename contents] toc-map]
      (spit (join addon-dir filename) contents))
    addon-dir))

(defn asset
  [filename]
  {:browser_download_url (str "https://example.org/" filename)
   :content_type (if (= filename "release.json") "application/json" "application/zip")
   :state "uploaded"
   :name filename})

;; release.json

(deftest release-json--forever-flavour
  (testing "the 'forever' flavour becomes the `:forever` game track"
    (let [given [{:filename "Foo.zip" :metadata [{:flavor "mainline"} {:flavor "forever"}]}
                 {:filename "Foo-forever.zip" :metadata [{:flavor "forever"}]}]
          expected {"Foo.zip" [:forever :retail]
                    "Foo-forever.zip" [:forever]}]
      (is (= expected (release-json/release-json-game-tracks given))))))

(deftest github--release-json--forever-asset
  (testing "an asset only for 'forever' in release.json is classified as `:forever`"
    (let [release-json {:releases [{:filename "Foo-1.2.3.zip" :nolib false :metadata [{:flavor "mainline" :interface 120001}]}
                                   {:filename "Foo-1.2.3-forever.zip" :nolib false :metadata [{:flavor "forever" :interface 16001}]}]}
          release {:name "Release 1.2.3"
                   :assets [(asset "Foo-1.2.3.zip")
                            (asset "Foo-1.2.3-forever.zip")
                            (asset "release.json")]}
          fake-routes {"https://example.org/release.json"
                       {:get (fn [_] {:status 200 :body (utils/to-json release-json)})}}
          expected [{:download-url "https://example.org/Foo-1.2.3.zip", :game-track :retail, :version "Release 1.2.3"}
                    {:download-url "https://example.org/Foo-1.2.3-forever.zip", :game-track :forever, :version "Release 1.2.3"}]]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (github-api/parse-assets release [])))))))

(deftest github--classic-and-forever-assets
  (testing "a classic + forever release with no retail asset: the forever asset is not classified as retail"
    (let [release {:name "Release 1.2.3"
                   :assets [(asset "Foo-1.2.3-classic.zip")
                            (asset "Foo-1.2.3-forever.zip")]}
          expected [{:download-url "https://example.org/Foo-1.2.3-classic.zip", :game-track :classic, :version "Release 1.2.3"}
                    {:download-url "https://example.org/Foo-1.2.3-forever.zip", :game-track :forever, :version "Release 1.2.3"}]]
      (is (= expected (github-api/parse-assets release []))))))

;; installed addons

(deftest installed--forever-only-addon
  (testing "an addon with only a forever interface version is a forever addon"
    (let [addon-dir (mock-addon! "EveryAddon" {"EveryAddon.toc" (toc-contents "EveryAddon" 16001)})
          actual (first (toc/parse-addon-toc-guard addon-dir))]
      (is (= [16001] (:interface-version-list actual)))
      (is (= [:forever] (:supported-game-tracks actual)))
      (is (= "1.60.1" (utils/interface-version-to-game-version 16001))))))

(deftest installed--retail-and-forever-addon
  (testing "an addon supporting retail and forever in a single .toc file supports retail and forever"
    (let [addon-dir (mock-addon! "EveryAddon" {"EveryAddon.toc" (toc-contents "EveryAddon" "120001, 16001")})
          actual (first (toc/parse-addon-toc-guard addon-dir))]
      (is (= [:forever :retail] (:supported-game-tracks actual))))))

(deftest installed--multi-toc-with-camelot
  (let [addon-dir (mock-addon! "EveryAddon" {"EveryAddon.toc" (toc-contents "EveryAddon Mainline" 120001)
                                             "EveryAddon_Vanilla.toc" (toc-contents "EveryAddon Vanilla" 11507)
                                             "EveryAddon_Camelot.toc" (toc-contents "EveryAddon Camelot" 16001)})]

    (testing "the '_Camelot' suffix is the forever game track"
      (let [expected [[nil "EveryAddon.toc"]
                      [:forever "EveryAddon_Camelot.toc"]
                      [:classic "EveryAddon_Vanilla.toc"]]]
        (is (= expected (toc/find-toc-files addon-dir)))))

    (testing "forever is among the supported game tracks"
      (is (= [:classic :forever :retail] (-> addon-dir toc/parse-addon-toc-guard first :supported-game-tracks))))

    (testing "with the 'classic' game track selected, the Vanilla .toc data is used"
      (let [actual (addon/load-installed-addon addon-dir :classic)]
        (is (= "EveryAddon Vanilla" (:label actual)))
        (is (= [11507] (:interface-version-list actual)))))

    (testing "with the 'forever' game track selected, the Camelot .toc data is used"
      (let [actual (addon/load-installed-addon addon-dir :forever)]
        (is (= "EveryAddon Camelot" (:label actual)))
        (is (= [16001] (:interface-version-list actual)))))))

(deftest installed--multi-toc-without-camelot
  (testing "with the 'forever' game track selected and no Camelot .toc file, the Mainline .toc data is used"
    (let [addon-dir (mock-addon! "EveryAddon" {"EveryAddon_Mainline.toc" (toc-contents "EveryAddon Mainline" 120001)
                                               "EveryAddon_Vanilla.toc" (toc-contents "EveryAddon Vanilla" 11507)})
          actual (addon/load-installed-addon addon-dir :forever)]
      (is (= "EveryAddon Mainline" (:label actual)))
      (is (= [120001] (:interface-version-list actual)))
      (is (= ["EveryAddon"] (->> (addon/load-all-installed-addons (str (fs/parent addon-dir)) :forever) (map :dirname)))))))
