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

(deftest parse-addon-toc-guard
  (testing "parsing of scraped toc-file key-vals"
    (let [addon-path (join fs/*cwd* "SomeAddon")
          toc-file-path (join addon-path "SomeAddon.toc")
          expected [{:name "addon-name"
                     :dirname "SomeAddon"
                     :label "Addon Name"
                     :description "Description of the addon here"
                     :interface-version 80205
                     :-toc/game-track :retail
                     :supported-game-tracks [:retail]
                     :installed-version "1.6.1"
                     ;; wowi is edged out in favour of curseforge ...
                     :source "curseforge"
                     :source-id 54321
                     ;; ... however both are captured here in `:source-map-list`
                     :source-map-list [{:source "wowinterface" :source-id 12345}
                                       {:source "curseforge" :source-id 54321}]}]]
      (fs/mkdir addon-path)
      (spit toc-file-path toc-file-contents)
      (is (= expected (toc/parse-addon-toc-guard addon-path))))))

(deftest parse-addon-toc
  (testing "parsing scraped keyvals in .toc yields expected values"
    (let [;; all of this can be derived from the directory name and sensible defaults
          base-case {:name "everyaddon-*"
                     :dirname "EveryAddon"
                     :label "EveryAddon *"
                     :description nil
                     :interface-version constants/default-interface-version
                     :-toc/game-track :retail
                     :supported-game-tracks [:retail]
                     :installed-version nil}

          cases [;; empty/no title
                 [{:title ""} base-case]
                 [{:title nil} base-case]

                 ;; classic interface version gets a :classic game-track
                 [{:interface constants/default-interface-version-classic}
                  (merge base-case {:interface-version constants/default-interface-version-classic
                                    :supported-game-tracks [:classic]
                                    :-toc/game-track :classic})]

                 ;; addon is in development
                 [{:version "@project-version@"} (merge base-case
                                                        {:installed-version "@project-version@"
                                                         :ignore? true})]]
          install-dir fs/*cwd*
          addon-dir (utils/join install-dir "EveryAddon")]
      (doseq [[toc-data expected] cases]
        (is (= expected (toc/parse-addon-toc toc-data addon-dir)))))))

(deftest parse-addon-toc--aliased
  (testing "addons whose toc files have a `:title` value that matches an alias get a hardcoded source and source-id value"
    (let [addon-dir (utils/join (helper/install-dir) "dirname")
          defaults {:dirname "dirname"
                    :description nil
                    :installed-version nil
                    :interface-version constants/default-interface-version
                    :-toc/game-track :retail
                    :supported-game-tracks [:retail]}
          cases [[{:title "Plater"} {:label "Plater" :name "plater" :source "curseforge" :source-id 100547}]
                 [{:title "|cffffd200Deadly Boss Mods|r |cff69ccf0Core|r"}
                  {:label "|cffffd200Deadly Boss Mods|r |cff69ccf0Core|r"
                   :name "|cffffd200deadly-boss-mods|r-|cff69ccf0core|r" ;; gibberish :(
                   :source "curseforge" :source-id 8814}]]]
      (fs/mkdir addon-dir)
      (doseq [[given expected] cases
              :let [expected (merge expected defaults)]]
        (is (= expected (toc/parse-addon-toc given addon-dir)))))))

(deftest parse-addon-toc--x-source
  (testing "addons whose toc files have a 'x-<host>-id' value will use those as `:source` and `:source-id`"
    (let [addon-dir (utils/join (helper/install-dir) "dirname")
          defaults {:dirname "dirname"
                    :description nil
                    :installed-version nil
                    :interface-version constants/default-interface-version
                    :supported-game-tracks [:retail]
                    :-toc/game-track :retail}
          cases [;; wowinterface
                 [{:x-wowi-id "123"} {:label "dirname *" :name "dirname-*"
                                      :source "wowinterface" :source-id 123
                                      :source-map-list [{:source "wowinterface" :source-id 123}]}]
                 [{:x-wowi-id 123} {:label "dirname *" :name "dirname-*"
                                    :source "wowinterface" :source-id 123
                                    :source-map-list [{:source "wowinterface" :source-id 123}]}]
                 [{:x-wowi-id "abc"} {:label "dirname *" :name "dirname-*"}] ;; bad case, non-numeric wowi ID

                 ;; curse
                 [{:x-curse-project-id "123"} {:label "dirname *" :name "dirname-*"
                                               :source "curseforge" :source-id 123
                                               :source-map-list [{:source "curseforge" :source-id 123}]}]
                 [{:x-curse-project-id 123} {:label "dirname *" :name "dirname-*"
                                             :source "curseforge" :source-id 123
                                             :source-map-list [{:source "curseforge" :source-id 123}]}]
                 [{:x-curse-project-id "abc"} {:label "dirname *" :name "dirname-*"}] ;; bad case, non-numeric curse ID

                 ;; tukui
                 [{:x-tukui-projectid "123"} {:label "dirname *" :name "dirname-*"
                                              :source "tukui" :source-id 123
                                              :source-map-list [{:source "tukui" :source-id 123}]}]
                 [{:x-tukui-projectid "-1"} {:label "dirname *" :name "dirname-*"
                                             :source "tukui" :source-id -1
                                             :source-map-list [{:source "tukui" :source-id -1}]}]
                 [{:x-tukui-projectid 123} {:label "dirname *" :name "dirname-*"
                                            :source "tukui" :source-id 123
                                            :source-map-list [{:source "tukui" :source-id 123}]}]
                 [{:x-tukui-projectid "abc"} {:label "dirname *" :name "dirname-*"}] ;; bad case

                 ;; mixed
                 [{:x-wowi-id "123"
                   :x-tukui-projectid "123"
                   :x-curse-project-id "123"} {:label "dirname *" :name "dirname-*"
                                               :source "tukui" :source-id 123 ;; todo: this precedence is interesting ...
                                               :source-map-list [{:source "wowinterface" :source-id 123}
                                                                 {:source "curseforge" :source-id 123}
                                                                 {:source "tukui" :source-id 123}]}]]]

      (fs/mkdir addon-dir)
      (doseq [[given expected] cases
              :let [expected (merge expected defaults)]]
        (is (= expected (toc/parse-addon-toc given addon-dir)))))))

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
                  [:classic "EveryAddon-Classic.toc"]
                  [:retail "EveryAddon-Mainline.toc"]
                  [:classic-tbc "EveryAddon-TBC.toc"]
                  [:classic "EveryAddon-Vanilla.toc"]
                  [nil "EveryAddon.toc"]]
        fixture (helper/fixture-path "everyaddon--1-2-3--multi-toc.zip")
        addon-dir (join (helper/install-dir) "EveryAddon")]
    (zip/unzip-file fixture (helper/install-dir))
    (is (= expected (toc/find-toc-files addon-dir)))))
