(ns wowman.toc-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [wowman
    [utils :as utils]
    [toc :as toc]]
   [wowman.utils :refer [join]]
   [me.raynes.fs :as fs]))

(def ^:dynamic temp-dir-path "")

(defn addon-path [] (join temp-dir-path "SomeAddon"))

(def toc-file-contents "## Interface: 80000
## Version: 1.6.1
## Title: Addon Name
## Notes: Description of the addon here
## Description: Another description here??
## Author: John Doe

## Multi: Colon: Madness:

# Ignored: Regular comment
## Ignored, no colon
 ## Ignored: Leading whitespace

##Foo: Bar
## Bar:Baz

############## Bup: Foo

## Over: to dinner
## Over: written

SomeAddon.lua")

(defn temp-addon-fixture
  "each test has a temporary addon available to it"
  [f]
  (binding [temp-dir-path (str (fs/temp-dir "cljtest-"))]
    (fs/mkdir (addon-path))
    (spit (join (addon-path) "SomeAddon.toc") toc-file-contents)
    (f)
    (fs/delete-dir temp-dir-path)))

(use-fixtures :once temp-addon-fixture)

(deftest parse-addon-toc
  (testing "scrape of toc-file contents"
    (let [expected {:interface "80000"
                    :version "1.6.1"
                    :title "Addon Name"
                    :notes "Description of the addon here"
                    :description "Another description here??"
                    :author "John Doe"

                    :multi "Colon: Madness:"

                    :foo "Bar" ;; no gap between comment and attribute
                    :bar "Baz" ;; no gap between attribute and value

                    :bup "Foo" ;; n-comments

                    :over "written" ;; duplicate attributes are overwritten
                    }]
      (is (= expected (toc/-read-toc-file toc-file-contents)))))

  (testing "parsing of scraped toc-file key-vals"
    (let [expected {:name "addon-name"
                    :dirname "SomeAddon"
                    :label "Addon Name"
                    :description "Description of the addon here"
                    :interface-version 80000
                    :installed-version "1.6.1"}]
      (is (= expected (toc/parse-addon-toc-guard (addon-path))))))

  (testing "parsing scraped keyvals in .toc value yields expected values"
    (let [cases [[{"title" ""} {:name "everyaddon-*", :dirname "EveryAddon", :label "EveryAddon *", :description nil, :interface-version 80200, :installed-version nil}]
                 [{"Title" ""} {:name "everyaddon-*", :dirname "EveryAddon", :label "EveryAddon *", :description nil, :interface-version 80200, :installed-version nil}]
                 [{"Title" nil} {:name "everyaddon-*", :dirname "EveryAddon", :label "EveryAddon *", :description nil, :interface-version 80200, :installed-version nil}]]
          install-dir fs/*cwd*
          addon-dir (utils/join install-dir "EveryAddon")]
      (fs/mkdir addon-dir)
      (doseq [[given expected] cases]
        (is (= expected (toc/parse-addon-toc addon-dir given)))))))

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
