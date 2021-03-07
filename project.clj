(defproject ogri-la/strongbox "4.0.0-unreleased"
  :description "World Of Warcraft Addon Manager"
  :url "https://github.com/ogri-la/strongbox"
  :license {:name "GNU Affero General Public License (AGPL)"
            :url "https://www.gnu.org/licenses/agpl-3.0.en.html"}

  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/spec.alpha "0.2.176"]
                 [org.clojure/tools.cli "1.0.194"] ;; cli arg parsing
                 [org.clojure/tools.namespace "1.0.0"] ;; reload code
                 [org.clojure/data.json "1.0.0"] ;; json handling
                 [orchestra "2018.12.06-2"] ;; improved clojure.spec instrumentation
                 ;; see lein deps :tree
                 [com.taoensso/timbre "4.10.0"] ;; logging
                 [enlive "1.1.6"] ;; html parsing
                 [clj-http "3.12.1"] ;; better http slurping
                 [clj-commons/fs "1.5.2"] ;; file system wrangling
                 [slugify "0.0.1"]
                 [trptcolin/versioneer "0.2.0"] ;; version number wrangling. it's more involved than you might suspect
                 [org.flatland/ordered "1.5.9"] ;; better ordered map
                 [clojure.java-time "0.3.2"] ;; date/time handling library, https://github.com/dm3/clojure.java-time
                 [envvar "1.1.0"] ;; environment variable wrangling
                 [gui-diff "0.6.7"] ;; pops up a graphical diff for test results
                 [com.taoensso/tufte "2.1.0"]
                 [cljfx "1.7.13" :exclusions [org.openjfx/javafx-web
                                             org.openjfx/javafx-media]]
                 [cljfx/css "1.1.0"]

                 [org.openjfx/javafx-base "15.0.1"]
                 [org.openjfx/javafx-base "15.0.1" :classifier "linux"]
                 [org.openjfx/javafx-base "15.0.1" :classifier "mac"]

                 [org.openjfx/javafx-controls "15.0.1"]
                 [org.openjfx/javafx-controls "15.0.1" :classifier "linux"]
                 [org.openjfx/javafx-controls "15.0.1" :classifier "mac"]

                 [org.openjfx/javafx-graphics "15.0.1"]
                 [org.openjfx/javafx-graphics "15.0.1" :classifier "linux"]
                 [org.openjfx/javafx-graphics "15.0.1" :classifier "mac"]

                 ;; remember to update the LICENCE.txt
                 ;; remember to update pom file (`lein pom`)

                 ]

  :resource-paths ["resources"]

  :profiles {:dev {:dependencies [;; fake http responses for testing
                                  [clj-http-fake "1.0.3"]
                                  ]}
             :uberjar {:aot :all
                       ;; fixes hanging issue:
                       ;; - https://github.com/cljfx/cljfx/issues/17
                       :injections [(javafx.application.Platform/exit)]}}

  :jvm-opts ["-Djdk.gtk.verbose=true" ;; debug output from JavaFX about which GTK it is looking for
             ]

  :main strongbox.main

  :plugins [[lein-cljfmt "0.6.4"]
            [jonase/eastwood "0.3.13"]
            [lein-cloverage "1.2.2"]]
  :eastwood {:exclude-linters [:constant-test]
             ;; linters that are otherwise disabled
             :add-linters [:unused-namespaces
                           :unused-private-vars
                           ;;:unused-locals ;; prefer to keep for readability
                           ;;:unused-fn-args ;; prefer to keep for readability
                           ;;:keyword-typos ;; bugged with spec?
                           ]
             :only-modified true}
  )
