(ns strongbox.toc-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [strongbox
    [zip :as zip]
    [constants :as constants]
    [utils :as utils :refer [join]]
    [toc :as toc]
    [test-helper :as helper]]
   [me.raynes.fs :as fs]))

(use-fixtures :each helper/fixture-tempcwd)

(def toc-file-contents "## Version: 1.6.1
## Title: Addon Name
## Notes: Description of the addon here
## Description: Another description here??
## Author: John Doe
## X-WoWI-ID: 12345
## X-Curse-Project-ID: 54321

## Multi: Colon: Madness:

# Ignored: Regular comment
## Ignored, no colon
 ## Ignored: Leading whitespace

##Foo: Bar
## Bar:Baz

############## Bup: Foo

## Over: to dinner
## Over: written

# # Comment1: ignored, must be bang-space-double-bang
# ## Comment2: comment2
# ##Comment3:comment3

#@retail@
## Interface: 80205
#@end-retail@
#@non-retail@
# ## Interface: 11302
#@end-non-retail@
  
SomeAddon.lua")

(deftest parse-toc-file
  (testing "scrape of toc-file contents"
    (let [expected {:version "1.6.1"
                    :title "Addon Name"
                    :notes "Description of the addon here"
                    :description "Another description here??"
                    :author "John Doe"

                    :x-curse-project-id "54321"
                    :x-wowi-id "12345"

                    :multi "Colon: Madness:"

                    :foo "Bar" ;; no gap between comment and attribute
                    :bar "Baz" ;; no gap between attribute and value

                    :bup "Foo" ;; n-comments

                    :over "written" ;; duplicate attributes are overwritten

                    ;; comment-comments
                    ;; used in templating .toc files
                    ;; https://github.com/Ravendwyr/Chinchilla/blob/0bbaec055d978c2082aa703efe7770dc7b796846/Chinchilla.toc#L1-L6

                    :#comment2 "comment2"
                    :#comment3 "comment3"

                    :interface "80205"
                    :#interface "11302"}]
      (is (= expected (toc/parse-toc-file toc-file-contents))))))

(deftest parse-toc-file--empty
  (testing "empty toc data returns `nil`"
    (is (nil? (toc/parse-toc-file "")))))

(deftest parse-addon-toc-guard
  (testing "parsing of scraped toc-file key-vals"
    (let [addon-path (join fs/*cwd* "SomeAddon")
          toc-file-path (join addon-path "SomeAddon.toc")
          expected [{:name "addon-name"
                     :dirname "SomeAddon"
                     :dirsize 0
                     :label "Addon Name"
                     :description "Description of the addon here"
                     :interface-version-list [80205 11302]
                     :-toc/game-track-list [:retail :classic]
                     :supported-game-tracks [:classic :retail]
                     :installed-version "1.6.1"
                     :source "wowinterface"
                     :source-id 12345
                     :source-map-list [{:source "wowinterface" :source-id 12345}]}]]
      (fs/mkdir addon-path)
      (spit toc-file-path toc-file-contents)
      (is (= expected (toc/parse-addon-toc-guard addon-path))))))

(deftest parse-addon-toc
  (testing "parsing scraped keyvals in .toc yields expected values"
    (let [;; all of this can be derived from the directory name and sensible defaults
          base-case {:name "everyaddon"
                     :dirname "EveryAddon"
                     :label "EveryAddon *"
                     :description nil
                     :interface-version-list [constants/default-interface-version]
                     :-toc/game-track-list [:retail]
                     :supported-game-tracks [:retail]
                     :installed-version nil}

          cases [;; empty/no title
                 [{:title ""} base-case]
                 [{:title nil} base-case]

                 ;; classic interface version gets a :classic game-track
                 [{:interface constants/default-interface-version-classic}
                  (merge base-case {:interface-version-list [constants/default-interface-version-classic]
                                    :supported-game-tracks [:classic]
                                    :-toc/game-track-list [:classic]})]

                 ;; addon is in development
                 [{:version "@project-version@"} (merge base-case
                                                        {:installed-version "@project-version@"
                                                         :ignore? true})]]
          install-dir fs/*cwd*
          addon-dir (utils/join install-dir "EveryAddon")]
      (doseq [[toc-data expected] cases]
        (is (= expected (toc/parse-addon-toc toc-data addon-dir)))))))

(deftest parse-addon-toc--x-source
  (testing "addons whose toc files have a 'x-<host>-id' value will use those as `:source` and `:source-id`"
    (let [addon-dir (utils/join (helper/install-dir) "dirname")
          defaults {:dirname "dirname"
                    :description nil
                    :installed-version nil
                    :interface-version-list [constants/default-interface-version]
                    :supported-game-tracks [:retail]
                    :-toc/game-track-list [:retail]}
          cases [;; wowinterface
                 [{:x-wowi-id "123"} {:label "dirname *" :name "dirname"
                                      :source "wowinterface" :source-id 123
                                      :source-map-list [{:source "wowinterface" :source-id 123}]}]
                 [{:x-wowi-id 123} {:label "dirname *" :name "dirname"
                                    :source "wowinterface" :source-id 123
                                    :source-map-list [{:source "wowinterface" :source-id 123}]}]
                 [{:x-wowi-id "abc"} {:label "dirname *" :name "dirname"}] ;; bad case, non-numeric wowi ID

                 ;; github
                 [{:x-github "https://github.com/foo/bar"} {:label "dirname *" :name "dirname"
                                                            :source "github" :source-id "foo/bar"
                                                            :source-map-list [{:source "github" :source-id "foo/bar"}]}]

                 ;; github via x-website
                 [{:x-website "https://github.com/SFX-WoW/Masque"} {:label "dirname *" :name "dirname"
                                                                    :source "github" :source-id "SFX-WoW/Masque"
                                                                    :source-map-list [{:source "github" :source-id "SFX-WoW/Masque"}]}]

                 ;; github vs github via x-website. when both are present, github is preferred.
                 [{:x-website "https://github.com/SFX-WoW/Masque"
                   :x-github "https://github.com/foo/bar"} {:label "dirname *" :name "dirname"
                                                            :source "github" :source-id "foo/bar"
                                                            :source-map-list [{:source "github" :source-id "foo/bar"}]}]

                 ;; curse
                 ;;[{:x-curse-project-id "123"} {:label "dirname *" :name "dirname"
                 ;;                              :source "curseforge" :source-id 123
                 ;;                              :source-map-list [{:source "curseforge" :source-id 123}]}]
                 ;;[{:x-curse-project-id 123} {:label "dirname *" :name "dirname"
                 ;;                            :source "curseforge" :source-id 123
                 ;;                            :source-map-list [{:source "curseforge" :source-id 123}]}]
                 ;;[{:x-curse-project-id "abc"} {:label "dirname *" :name "dirname"}] ;; bad case, non-numeric curse ID

                 ;; tukui
                 ;;[{:x-tukui-projectid "123"} {:label "dirname *" :name "dirname"
                 ;;                             :source "tukui" :source-id 123
                 ;;                             :source-map-list [{:source "tukui" :source-id 123}]}]
                 ;;[{:x-tukui-projectid "-1"} {:label "dirname *" :name "dirname"
                 ;;                            :source "tukui" :source-id -1
                 ;;                            :source-map-list [{:source "tukui" :source-id -1}]}]
                 ;;[{:x-tukui-projectid 123} {:label "dirname *" :name "dirname"
                 ;;                           :source "tukui" :source-id 123
                 ;;                           :source-map-list [{:source "tukui" :source-id 123}]}]
                 ;;[{:x-tukui-projectid "abc"} {:label "dirname *" :name "dirname"}] ;; bad case

                 ;; mixed
                 [{:x-wowi-id "123"
                   :x-tukui-projectid "123"
                   :x-curse-project-id "123"
                   :x-github "https://github.com/foo/bar"
                   :x-website "https://github.com/bar/foo"}

                  {:label "dirname *" :name "dirname"
                   :source "wowinterface" :source-id 123 ;; todo: this precedence is interesting ...
                   :source-map-list [{:source "wowinterface" :source-id 123}
                                     {:source "github" :source-id "foo/bar"}

                                     ;;{:source "curseforge" :source-id 123}
                                     ;;{:source "tukui" :source-id 123}
                                     ]}]]]

      (fs/mkdir addon-dir)
      (doseq [[given expected] cases
              :let [expected (merge expected defaults)]]
        (is (= expected (toc/parse-addon-toc given addon-dir)))))))

(deftest parse-addon-toc--duplicate-interface-versions-removed
  (let [case {:interface 10000 :#interface 10000}
        expected [10000]
        use-defaults false]
    (is (= expected (:interface-version-list (toc/-parse-addon-toc case use-defaults))))))

(deftest parse-addon-toc--use-defaults
  (testing "with-defaults true"
    (let [case {:title nil
                :interface nil}
          expected {:label " *"
                    :interface-version-list [constants/default-interface-version]}]
      (is (= expected (select-keys (toc/-parse-addon-toc case true) [:label :interface-version-list])))))

  (testing "with-defaults false"
    (let [case {:title nil
                :interface nil}
          expected {:label nil
                    :interface-version-list []}]
      (is (= expected (select-keys (toc/-parse-addon-toc case false) [:label :interface-version-list]))))))

(deftest rm-trailing-version
  (testing "parsing of 'Title' attribute in toc file"
    (let [cases [["Grid" "Grid"] ;; no trailing version? no problems
                 ["Bagnon Void Storage" "Bagnon Void Storage"]

                 ["Grid2" "Grid2"] ;; trailing digit, but not separated by a space
                 ["Bartender4" "Bartender4"]

                 ["Grid 2" "Grid"] ;; trailing digit is removed ...
                 ["WeakAuras 2" "WeakAuras"] ;; ...even when we don't want them to

                 ;; encoded colours are preserved
                 ["AtlasLoot |cFF0099FF[Battle for Azeroth]|r" "AtlasLoot |cFF0099FF[Battle for Azeroth]|r"]

                 ;; string of trailing digits and dots are removed
                 ["Carbonite Maps 8.2.0" "Carbonite Maps"]
                 ;; even if they are prefixed with a 'v'
                 ["Carbonite Maps v8.2.0" "Carbonite Maps"]

                 ;; foreign glyphs are preserved
                 ["GatherMate2 採集助手" "GatherMate2 採集助手"]

                 ;; contrived examples
                 ["foo ..." "foo"]
                 ["foo v.0." "foo"]
                 ["foo v..." "foo"]
                 ["foo ...v" "foo ...v"]
                 ["foo v2019.01.01" "foo"]] ;; haven't seen this case yet, doesn't seem unreasonable
          ]
      (doseq [[given expected] cases]
        (is (= expected (toc/rm-trailing-version given)))))))

(deftest find-toc-files
  (let [expected [[:classic-tbc "EveryAddon-BCC.toc"]
                  [:classic-cata "EveryAddon-Cata.toc"]
                  [:classic "EveryAddon-Classic.toc"]
                  [:retail "EveryAddon-Mainline.toc"]
                  [:classic-tbc "EveryAddon-TBC.toc"]
                  [:classic "EveryAddon-Vanilla.toc"]
                  [:classic-wotlk "EveryAddon-Wrath.toc"]
                  [nil "EveryAddon.toc"]]
        fixture (helper/fixture-path "everyaddon--1-2-3--multi-toc.zip")
        addon-dir (join (helper/install-dir) "EveryAddon")]
    (zip/unzip-file fixture (helper/install-dir))
    (is (= expected (toc/find-toc-files addon-dir)))))

(deftest parse-addon-toc--invalid-toc-questie
  (testing "invalid toc data is discarded"
    (let [fixture (helper/fixture-path "questie--invalid.toc")
          raw-data (toc/read-toc-file fixture)]
      (is (nil? (toc/parse-addon-toc raw-data))))))

(deftest parse-addon-toc--multiple-interface-versions
  (testing "multiple interface versions are supported"
    (let [fixture {:title "foo"
                   :label "Foo"
                   :description "Foo Bar"
                   :dirname "Baz"
                   :interface "100206, 40400, 11502"
                   :installed-version "1.2.3"
                   :supported-game-tracks []}

          expected {:description "Foo Bar",
                    :dirname "Baz",
                    :installed-version nil,
                    :interface-version-list [100206 40400 11502],
                    :label "foo",
                    :name "foo",
                    :supported-game-tracks [:retail :classic-cata :classic],
                    :-toc/game-track-list [:retail :classic-cata :classic]}]
      (is (= expected (toc/parse-addon-toc fixture))))))

(deftest parse-interface-value
  (testing "interface values can be parsed into a set of game tracks"
    (let [cases [[nil []]

                 ;; ---

                 ["", []]
                 ["asdf", []]
                 ["1", [1]]
                 ["1,2", [1,2]]
                 ["1, 2", [1,2]]
                 ["1,2, 3", [1,2,3]]
                 ["1,2,3,", [1,2,3]]
                 [",1,2,3,", [1,2,3]]
                 ["100206, 40400, 11502", [100206, 40400, 11502]]

                 ;; integers are used by tests but not encouraged.
                 [0, [0]]
                 [1, [1]]
                 [100206, [100206]]

                 ;; dupes
                 ["1,1", [1]],
                 ["1, 1, 1", [1]],
                 ["1,1,2,2,3", [1,2,3]]]]

      (doseq [[given expected] cases]
        (is (= expected (toc/parse-interface-value given)))))))
