(defproject ogri-la/wowman "0.10.0-unreleased"
  :description "World Of Warcraft Addon Manager"
  :url "https://github.com/ogri-la/wowman"
  :license {:name "GNU Affero General Public License (AGPL)"
            :url "https://www.gnu.org/licenses/agpl-3.0.en.html"}

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/spec.alpha "0.2.176"]
                 [org.clojure/tools.cli "0.4.2"] ;; cli arg parsing
                 [org.clojure/tools.namespace "0.2.11"] ;; reload code
                 [org.clojure/data.codec "0.1.1"] ;; base64 encoding
                 [org.clojure/data.json "0.2.6"] ;; better json decoding
                 [orchestra "2018.12.06-2"] ;; improved clojure.spec instrumentation
                 ;; see lein deps :tree
                 [metosin/spec-tools "0.9.2" :exclusions [com.fasterxml.jackson.core/jackson-core]] ;; more improvements to clojure.spec handling
                 [com.taoensso/timbre "4.10.0"] ;; logging
                 [enlive "1.1.6"] ;; html parsing
                 [clj-http "3.10.0"] ;; better http slurping
                 [cheshire "5.8.1"] ;; nicer json serialisation (indents)
                 [seesaw "1.5.0"] ;; swing
                 [clj-commons/fs "1.5.0"] ;; file system wrangling
                 [slugify "0.0.1"]
                 [trptcolin/versioneer "0.2.0"] ;; version number wrangling. it's more involved than you might suspect
                 [org.flatland/ordered "1.5.7"] ;; better ordered map
                 [clojure.java-time "0.3.2"] ;; date/time handling library
                 [envvar "1.1.0"] ;; environment variable wrangling
                 [clj-http-fake "1.0.3"] ;; fake http responses for testing

                 ;; remember to update the LICENCE.txt
                 ;; remember to update pom file (`lein pom`)

                 [com.h2database/h2 "1.4.199"]
                 [seancorfield/next.jdbc "1.0.6"]

                 ]

  :profiles {:uberjar {:aot :all}}

  :main wowman.main

  :plugins [[lein-cljfmt "0.6.4"]
            [jonase/eastwood "0.3.5"]
            [lein-cloverage "1.1.1"]]
  :eastwood {:exclude-linters [:constant-test]
             :add-linters [:unused-namespaces
                           ;;:unused-locals :unused-fn-args ;; too many false positives to always be enabled
                           ;; :non-clojure-file  ;; just noise
                           ;; :keyword-typos ;; bugged with spec?
                           ]}
  )
