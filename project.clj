(defproject ogri-la/strongbox "7.6.0-unreleased"
  :description "World Of Warcraft Addon Manager"
  :url "https://github.com/ogri-la/strongbox"
  :license {:name "GNU Affero General Public License (AGPL)"
            :url "https://www.gnu.org/licenses/agpl-3.0.en.html"}

  ;;:global-vars {*warn-on-reflection* true}

  ;; https://github.com/technomancy/leiningen/issues/1914
  ;; https://github.com/technomancy/leiningen/issues/2763
  ;; https://github.com/technomancy/leiningen/issues/2769
  :pedantic? false

  :dependencies [[org.clojure/clojure "1.11.3"]
                 [org.clojure/tools.cli "1.1.230"] ;; cli arg parsing
                 [org.clojure/tools.namespace "1.5.0"] ;; reload code
                 [org.clojure/data.json "2.5.0"] ;; json handling
                 [orchestra "2021.01.01-1"] ;; improved clojure.spec instrumentation
                 ;; see lein deps :tree
                 [com.taoensso/timbre "6.5.0"] ;; logging
                 [clj-http "3.13.0"] ;; better http slurping
                 [clj-commons/fs "1.6.311"] ;; file system wrangling
                 [slugify "0.0.1"]
                 [trptcolin/versioneer "0.2.0"] ;; version number wrangling. it's more involved than you might suspect
                 [org.flatland/ordered "1.15.12"] ;; better ordered map
                 [clojure.java-time "1.4.2"] ;; date/time handling library, https://github.com/dm3/clojure.java-time
                 [envvar "1.1.2"] ;; environment variable wrangling
                 [tolitius/lasync "0.1.25"] ;; better parallel processing

                 [cljfx "1.9.0" :exclusions [org.openjfx/javafx-web
                                             org.openjfx/javafx-media]]
                 [cljfx/css "1.1.0"]

                 [org.openjfx/javafx-base "20-ea+1"]
                 [org.openjfx/javafx-base "20-ea+1" :classifier "linux"]
                 [org.openjfx/javafx-base "20-ea+1" :classifier "mac"]

                 [org.openjfx/javafx-controls "20-ea+1"]
                 [org.openjfx/javafx-controls "20-ea+1" :classifier "linux"]
                 [org.openjfx/javafx-controls "20-ea+1" :classifier "mac"]

                 [org.openjfx/javafx-graphics "20-ea+1"]
                 [org.openjfx/javafx-graphics "20-ea+1" :classifier "linux"]
                 [org.openjfx/javafx-graphics "20-ea+1" :classifier "mac"]

                 ;; GPLv3 compatible dependencies.
                 ;; these don't need an exception in LICENCE.txt
                 [org.ocpsoft.prettytime/prettytime "5.0.9.Final"] ;; Apache 2.0 licenced, pretty date formatting
                 [org.controlsfx/controlsfx "11.2.1"] ;; BSD-3
                 
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

  :profiles {:repl {:source-paths ["repl"]}
             :dev {:resource-paths ["dev-resources" "resources"] ;; dev-resources take priority
                   :dependencies [[clj-http-fake "1.0.4"] ;; fake http responses for testing
                                  [gui-diff "0.6.7" :exclusions [net.cgrant/parsley]] ;; pops up a graphical diff for test results
                                  ]}

             :uberjar {:aot :all
                       ;; fixes hanging issue:
                       ;; - https://github.com/cljfx/cljfx/issues/17
                       :injections [(javafx.application.Platform/exit)]
                       :jvm-opts ["-Dclojure.compiler.disable-locals-clearing=true"
                                  "-Dclojure.compiler.elide-meta=[:doc :file :line :added]"
                                  "-Dclojure.compiler.direct-linking=true"]}}

  :repl-options {:init-ns strongbox.user} ;; see repl/strongbox/user.clj

  ;; debug output from JavaFX about which GTK it is looking for. 
  ;; was useful in figuring out why javafx was failing to initialise even with xvfb.
  ;;:jvm-opts ["-Djdk.gtk.verbose=true"]

  :main strongbox.main

  :plugins [[lein-ancient "0.7.0"]
            [lein-cljfmt "0.9.0"]
            [jonase/eastwood "1.3.0"]
            [lein-cloverage "1.2.4"]
            [venantius/yagni "0.1.7"]]

  :eastwood {:exclude-linters [:constant-test
                               :reflection]
             ;; linters that are otherwise disabled
             :add-linters [:unused-namespaces
                           :unused-private-vars
                           ;;:unused-locals ;; prefer to keep for readability
                           ;;:unused-fn-args ;; prefer to keep for readability
                           ;;:keyword-typos ;; bugged with spec?
                           ]
             :only-modified true
             }
  )
