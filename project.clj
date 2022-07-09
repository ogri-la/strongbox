(defproject ogri-la/strongbox "5.3.0"
  :description "World Of Warcraft Addon Manager"
  :url "https://github.com/ogri-la/strongbox"
  :license {:name "GNU Affero General Public License (AGPL)"
            :url "https://www.gnu.org/licenses/agpl-3.0.en.html"}

  ;;:global-vars {*warn-on-reflection* true}

  ;; https://github.com/technomancy/leiningen/issues/1914
  ;; https://github.com/technomancy/leiningen/issues/2763
  ;; https://github.com/technomancy/leiningen/issues/2769
  :pedantic? false

  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/tools.cli "1.0.206"] ;; cli arg parsing
                 [org.clojure/tools.namespace "1.1.0"] ;; reload code
                 [org.clojure/data.json "2.4.0"] ;; json handling
                 [org.clojure/data.csv "1.0.0"] ;; csv handling
                 [orchestra "2021.01.01-1"] ;; improved clojure.spec instrumentation
                 ;; see lein deps :tree
                 [com.taoensso/timbre "5.1.2"] ;; logging
                 [enlive "1.1.6"] ;; html parsing
                 [clj-http "3.12.3"] ;; better http slurping
                 [clj-commons/fs "1.6.307"] ;; file system wrangling
                 [slugify "0.0.1"]
                 [trptcolin/versioneer "0.2.0"] ;; version number wrangling. it's more involved than you might suspect
                 [org.flatland/ordered "1.5.9"] ;; better ordered map
                 [clojure.java-time "0.3.3"] ;; date/time handling library, https://github.com/dm3/clojure.java-time
                 [envvar "1.1.2"] ;; environment variable wrangling
                 [gui-diff "0.6.7" :exclusions [net.cgrant/parsley]] ;; pops up a graphical diff for test results
                 [com.taoensso/tufte "2.2.0"] ;; profiling
                 [tolitius/lasync "0.1.23"] ;; better parallel processing

                 [cljfx "1.7.19" :exclusions [org.openjfx/javafx-web
                                              org.openjfx/javafx-media]]
                 [cljfx/css "1.1.0"]

                 [org.openjfx/javafx-base "17.0.2"]
                 [org.openjfx/javafx-base "17.0.2" :classifier "linux"]
                 [org.openjfx/javafx-base "17.0.2" :classifier "mac"]

                 [org.openjfx/javafx-controls "17.0.2"]
                 [org.openjfx/javafx-controls "17.0.2" :classifier "linux"]
                 [org.openjfx/javafx-controls "17.0.2" :classifier "mac"]

                 [org.openjfx/javafx-graphics "17.0.2"]
                 [org.openjfx/javafx-graphics "17.0.2" :classifier "linux"]
                 [org.openjfx/javafx-graphics "17.0.2" :classifier "mac"]

                 ;; GPLv3 compatible dependencies.
                 ;; these don't need an exception in LICENCE.txt
                 [org.apache.commons/commons-compress "1.21"] ;; Apache 2.0 licenced, bz2 compression/decompression of static catalogue
                 [org.ocpsoft.prettytime/prettytime "5.0.2.Final"] ;; Apache 2.0 licenced, pretty date formatting
                 [org.controlsfx/controlsfx "11.1.1"] ;; BSD-3
                 
                 ;; remember to update the LICENCE.txt
                 ;; remember to update pom file (`lein pom`)

                 ;;[org.clojure/core.cache "1.0.207"] ;; jfx context caching
                 
                 ]

  :managed-dependencies [;; fixes the annoying:
                         ;; "WARNING: cat already refers to: #'clojure.core/cat in namespace: net.cgrand.parsley.fold, being replaced by: #'net.cgrand.parsley.fold/cat"
                         ;; https://github.com/cgrand/parsley/issues/15
                         ;; see `gui-diff` exclusion
                         [net.cgrand/parsley "0.9.3"]]

  :resource-paths ["resources"]

  :profiles {:dev {:plugins [[lein-ancient "0.7.0"]]
                   :resource-paths ["dev-resources" "resources"] ;; dev-resources take priority
                   :dependencies [[clj-http-fake "1.0.3"] ;; fake http responses for testing
                                  ]}

             :uberjar {:aot :all
                       ;; fixes hanging issue:
                       ;; - https://github.com/cljfx/cljfx/issues/17
                       :injections [(javafx.application.Platform/exit)]
                       }}

  ;; debug output from JavaFX about which GTK it is looking for. 
  ;; was useful in figuring out why javafx was failing to initialise even with xvfb.
  ;;:jvm-opts ["-Djdk.gtk.verbose=true"]

  :main strongbox.main

  :plugins [[lein-cljfmt "0.6.4"]
            [jonase/eastwood "0.9.9"]
            [lein-cloverage "1.2.2"]]
  :eastwood {:exclude-linters [:constant-test
                               :reflection]
             ;; linters that are otherwise disabled
             :add-linters [:unused-namespaces
                           :unused-private-vars
                           ;;:unused-locals ;; prefer to keep for readability
                           ;;:unused-fn-args ;; prefer to keep for readability
                           ;;:keyword-typos ;; bugged with spec?
                           ]
             :only-modified true}
  )
