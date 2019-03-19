(ns wowman.fs-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [wowman.fs]
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

(deftest -parse-addon-toc
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
      (is (= expected (wowman.fs/-read-toc-file toc-file-contents)))))

  (testing "parsing of scraped toc-file key-vals"
    (let [expected {:name "addon-name"
                    :dirname "SomeAddon"
                    :label "Addon Name"
                    :description "Description of the addon here"
                    :interface-version 80000
                    :installed-version "1.6.1"}]

      (is (= expected (wowman.fs/parse-addon-toc (addon-path)))))))
