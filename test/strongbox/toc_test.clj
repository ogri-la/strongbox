(ns strongbox.toc-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [strongbox
    [utils :as utils]
    [toc :as toc]
    [test-helper :as helper]]
   [strongbox.utils :refer [join]]
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

(deftest parse-addon-toc
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

      (is (= expected (toc/-parse-toc-file toc-file-contents)))))

  (testing "parsing of scraped toc-file key-vals"
    (let [addon-path (join fs/*cwd* "SomeAddon")
          toc-file-path (join addon-path "SomeAddon.toc")
          expected {:name "addon-name"
                    :dirname "SomeAddon"
                    :label "Addon Name"
                    :description "Description of the addon here"
                    :interface-version 80205
                    :installed-version "1.6.1"
                    ;; wowi is edged out in favour of curseforge unfortunately
                    :source "curseforge"
                    :source-id 54321}]
      (fs/mkdir addon-path)
      (spit toc-file-path toc-file-contents)
      (is (= expected (toc/parse-addon-toc-guard addon-path)))))

  (testing "parsing scraped keyvals in .toc yields expected values"
    (let [;; all of this can be derived from the directory name and sensible defaults
          base-case {:name "everyaddon-*"
                     :dirname "EveryAddon"
                     :label "EveryAddon *"
                     :description nil
                     :interface-version 80200
                     :installed-version nil}

          cases [;; empty/no title
                 [{:title ""} base-case]
                 [{:title nil} base-case]

                 ;; addon is in development
                 [{:version "@project-version@"} (merge base-case
                                                        {:installed-version "@project-version@"
                                                         :ignore? true})]]
          install-dir fs/*cwd*
          addon-dir (utils/join install-dir "EveryAddon")]
      (fs/mkdir addon-dir)
      (doseq [[toc-data expected] cases]
        (is (= expected (toc/parse-addon-toc addon-dir toc-data))))))

  (comment "toc and nfo modules are now separate. this test needs to live in core"
           (testing "parsing scraped keyvals in .toc with an explicitly set ignore flag in nfo file"
             (let [base-case {:name "everyaddon-*"
                              :dirname "EveryAddon"
                              :label "EveryAddon *"
                              :description nil
                              :interface-version 80200}

          ;; addon is in development
                   toc-data {:version "@project-version@"}

                   nfo-data {;; update me! destroy any changes to my work!
                    ;; this is only ever set by the user, not by the app.
                             :ignore? false}

                   expected (merge base-case nfo-data {:installed-version "@project-version@"})

                   install-dir fs/*cwd*
                   addon-dir (utils/join install-dir "EveryAddon")]

               (fs/mkdir addon-dir)
               (spit (utils/join addon-dir nfo/nfo-filename) (utils/to-json nfo-data))
               (is (= expected (toc/parse-addon-toc addon-dir toc-data)))))))

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
