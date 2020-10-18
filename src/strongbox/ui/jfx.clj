(ns strongbox.ui.jfx
  (:require
   [me.raynes.fs :as fs]
   [clojure.string :refer [lower-case join]]
   [taoensso.timbre :as timbre :refer [spy info]] ;; debug info warn error spy]] 
   [cljfx.ext.table-view :as fx.ext.table-view]
   [cljfx.lifecycle :as fx.lifecycle]
   [cljfx.component :as fx.component]
   [cljfx
    [api :as fx]]
   [cljfx.css :as css]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [strongbox.ui.cli :as cli]
   [strongbox
    [specs :as sp]
    [logging :as logging]
    [utils :as utils :refer [no-new-lines]]
    [core :as core]])
  (:import
   [java.util List]
   [javafx.util Callback]
   [javafx.scene.control TableRow TextInputDialog Alert Alert$AlertType ButtonType]
   [javafx.stage FileChooser FileChooser$ExtensionFilter DirectoryChooser Window WindowEvent]
   [javafx.application Platform]
   [javafx.scene Node]))

;; javafx hack, fixes combobox that sometimes goes blank:
;; https://github.com/cljfx/cljfx/issues/76#issuecomment-645563116
(def ext-recreate-on-key-changed
  "Extension lifecycle that recreates its component when lifecycle's key is changed
  
  Supported keys:
  - `:key` (required) - a value that determines if returned component should be recreated
  - `:desc` (required) - a component description with additional lifecycle semantics"
  (reify fx.lifecycle/Lifecycle
    (create [_ {:keys [key desc]} opts]
      (with-meta {:key key
                  :child (fx.lifecycle/create fx.lifecycle/dynamic desc opts)}
        {`fx.component/instance #(-> % :child fx.component/instance)}))
    (advance [this component {:keys [key desc] :as this-desc} opts]
      (if (= (:key component) key)
        (update component :child #(fx.lifecycle/advance fx.lifecycle/dynamic % desc opts))
        (do (fx.lifecycle/delete this component opts)
            (fx.lifecycle/create this this-desc opts))))
    (delete [_ component opts]
      (fx.lifecycle/delete fx.lifecycle/dynamic (:child component) opts))))

(defn-spec style map?
  "generates javafx css definitions for the different themes.
  if editor is connected to a running repl session then modifying
  the css will reload the running GUI for immediate feedback."
  []
  (css/register
   ::style
   (let [generate-style
         (fn [theme-kw]
           (let [colour-map (theme-kw  core/themes)
                 colour #(name (get colour-map % "green"))]
             {"#about-dialog "

              {:-fx-spacing "3"

               "#about-pane-hyperlink"
               {:-fx-font-size "1.1em"
                :-fx-padding "0 0 4 -1"

                ":hover" {:-fx-text-fill "blue"}}

               "#about-pane-title"
               {:-fx-font-size "1.5em"
                :-fx-padding "10em"}}

              (format "#%s.root " (name theme-kw))
              {:-fx-padding 0
               :-fx-base (colour :base)


               ;; backgrounds


               :-fx-accent (colour :accent) ;; selection colour of backgrounds

               ".table-placeholder-text"
               {:-fx-font-size "1.5em"
                :-fx-fill (colour :table-font-colour)}

               "#main-menu" {:-fx-background-color (colour :base)}

               ".context-menu" {:-fx-effect "None"}
               ".combo-box-base"
               {:-fx-padding "1px"
                :-fx-background-radius "0"
                ;; truncation happens from the left. thanks to:
                ;; https://stackoverflow.com/questions/36264656/scalafx-javafx-how-can-i-change-the-overrun-style-of-a-combobox
                " > .list-cell" {:-fx-text-overrun "leading-ellipsis"}}

               ".button" {:-fx-background-radius "0"
                          :-fx-padding ["6px" "17px"]
                          ":hover" {:-fx-text-fill (colour :button-text-hovering)}}

               ;; tabber
               ".tab-pane > .tab-header-area > .headers-region > .tab "
               {:-fx-background-radius "0"}

               ;; common tables


               ".table-view"
               {:-fx-table-cell-border-color (colour :table-border)
                :-fx-font-size ".9em"}

               ".table-view .column-header"
               {;;:-fx-background-color "#ddd" ;; flat colour vs gradient
                :-fx-font-size "1em"}

               ".table-view .table-row-cell"
               {:-fx-border-insets "-1 -1 0 -1"
                :-fx-border-color (colour :table-border)
                " .table-cell" {:-fx-text-fill (colour :table-font-colour)}

                ;; even
                :-fx-background-color (colour :row)
                ":hover" {:-fx-background-color (colour :row-hover)}
                ":selected" {:-fx-background-color (colour :unsteady)
                             " .table-cell" {:-fx-text-fill "-fx-focused-text-base-color"}
                             :-fx-table-cell-border-color (colour :table-border)}

                ":odd" {:-fx-background-color (colour :row)}
                ":odd:hover" {:-fx-background-color (colour :row-hover)}
                ":odd:selected" {:-fx-background-color (colour :unsteady)}
                ":odd:selected:hover" {:-fx-background-color (colour :unsteady)}

                ".unsteady" {;; '!important' so that it takes precedence over .updateable addons
                             :-fx-background-color (str (colour :unsteady) " !important")}}


               ;; installed-addons menu

               ;; prevent truncation and ellipses
               "#update-all-button "
               {:-fx-min-width "101px"}
               "#game-track-combo-box "
               {:-fx-min-width "100px"}


               ;; installed-addons table


               ".table-view#installed-addons"
               {" .updateable"
                {:-fx-background-color (colour :row-updateable)

                 ;; selected updateable addons are do not look any different
                 ":selected" {:-fx-background-color "-fx-selection-bar"}}

                " .ignored .table-cell"
                {:-fx-text-fill (colour :installed/ignored-fg)}

                " .wow-column" {:-fx-alignment "center"}}


               ;; notice-logger


               ".table-view#notice-logger"
               {" .warn" {:-fx-background-color (colour :row-warning)
                          ":selected" {:-fx-background-color "-fx-selection-bar"}}

                " .error" {:-fx-background-color (colour :row-error)
                           ":selected" {:-fx-background-color "-fx-selection-bar"}}

                " #level" {:-fx-alignment "center"
                           :-fx-border-width "0"}

                ;; hide column headers


                " > .column-header-background"
                {:-fx-max-height 0
                 :-fx-pref-height 0
                 :-fx-min-height 0}

                :-fx-font-family "monospace"}

               "#splitter .split-pane-divider"
               {:-fx-padding "8px"}

               ;; search

               "#search-text-field "
               {:-fx-text-fill (colour :table-font-colour)}

               ".table-view#search-addons"
               {" .downloads-column" {:-fx-alignment "center-right"}
                " .installed" {" > .table-cell" {} ;;:-fx-text-fill "black"
                               :-fx-background-color (colour :already-installed-row-colour)}}

               "#status-bar"
               {:-fx-font-size ".9em"
                :-fx-padding "5px"

                " > .text"
                {;; omg, wtf does 'fx-fill' work and not 'fx-text-fill' ???
                 :-fx-fill (colour :table-font-colour)}}


               ;; common table fields


               ".table-view .source-column"
               {:-fx-alignment "center-left"
                :-fx-padding "-2 0 0 0" ;; hyperlinks are just a little bit off .. weird.
                " .hyperlink:visited" {:-fx-underline "false"}
                " .hyperlink, .hyperlink:hover" {:-fx-underline "false"
                                                 :-fx-text-fill (colour :jfx-hyperlink)
                                                 :-fx-font-weight (colour :jfx-hyperlink-weight)}}}}))]

     (merge
      (generate-style :light)
      (generate-style :dark)))))


;;


(defn get-window
  "returns the application `Window` object."
  []
  (first (Window/getWindows)))

(defn select
  [node-id]
  (-> (get-window) .getScene .getRoot (.lookupAll node-id)))

(defn extension-filter
  [x]
  ;; taken from here:
  ;; - https://github.com/cljfx/cljfx/pull/40/files
  (cond
    (instance? FileChooser$ExtensionFilter x) x
    (map? x) (FileChooser$ExtensionFilter. ^String (:description x) ^List (seq (:extensions x)))
    :else (throw (Exception. (format "cannot coerce '%s' to `FileChooser$ExtensionFilter`" (type x))))))

(defn file-chooser
  "prompt user to select a file"
  [event & [opt-map]]
  (let [opt-map (or opt-map {})
        default-open-type :open
        open-type (get opt-map :type default-open-type)
        ;; valid for a menu-item
        ;;window (-> event .getTarget .getParentPopup .getOwnerWindow .getScene .getWindow)
        window (get-window)
        chooser (doto (FileChooser.)
                  (.setTitle "Open File"))]
    (when-let [ext-filters (:filters opt-map)]
      (-> chooser .getExtensionFilters (.addAll (mapv extension-filter ext-filters))))
    (when-let [file-obj @(fx/on-fx-thread
                          (case open-type
                            :save (.showSaveDialog chooser window)
                            (.showOpenDialog chooser window)))]
      (-> file-obj .getAbsolutePath str))))

(defn dir-chooser
  "prompt user to select a directory"
  [event]
  (let [;; valid for a menu-item
        window (-> event .getTarget .getParentPopup .getOwnerWindow .getScene .getWindow)
        chooser (doto (DirectoryChooser.)
                  (.setTitle "Select Directory"))]
    (when-let [dir @(fx/on-fx-thread
                     (.showDialog chooser window))]
      (-> dir .getAbsolutePath str))))

(defn-spec text-input (s/or :ok string? :noop nil?)
  "prompt user to enter text"
  [prompt string?]
  @(fx/on-fx-thread
    (let [widget (doto (TextInputDialog.)
                   (.setTitle prompt)
                   (.setHeaderText nil)
                   (.setContentText prompt)
                   (.initOwner (get-window)))
          optional-val (.showAndWait widget)]
      (when (and (.isPresent optional-val)
                 (not (empty? (.get optional-val))))
        (.get optional-val)))))

(defn alert
  "displays an alert dialog to the user with varying button combinations they can press.
  the result object is a weirdo `java.util.Optional` https://docs.oracle.com/javase/8/docs/api/java/util/Optional.html
  whose `.get` return value is equal to the button type clicked"
  [event msg & [opt-map]]
  @(fx/on-fx-thread
    (let [window (-> event .getTarget .getParentPopup .getOwnerWindow .getScene .getWindow)
          alert-type-map {:warning Alert$AlertType/WARNING
                          :error Alert$AlertType/ERROR
                          :confirm Alert$AlertType/CONFIRMATION
                          :info Alert$AlertType/INFORMATION}
          alert-type-key (get opt-map :type, :info)
          alert-type (get alert-type-map alert-type-key)
          widget (doto (Alert. alert-type)
                   (.setTitle (:title opt-map))
                   (.setHeaderText (:header opt-map))
                   (.setContentText msg)
                   (.initOwner window))]
      (when (:content opt-map)
        (.setContent (.getDialogPane widget) (:content opt-map)))
      (.showAndWait widget))))

;;

(def INSTALLED-TAB 0)
(def SEARCH-TAB 1)

(defn menu-item
  [label handler & [opt-map]]
  (merge
   {:fx/type :menu-item
    :text label
    :mnemonic-parsing true
    :on-action handler}
   (when-let [key (:key opt-map)]
     {:accelerator key})))

(defn menu
  [label items & [opt-map]]
  (merge
   {:fx/type :menu
    :text label
    :mnemonic-parsing true
    :items items}
   (when-let [key (:key opt-map)]
     {:accelerator key})))

(defn async
  "execute given function and it's optional argument list asynchronously.
  for example: `(async println [1 2 3])` prints \"1 2 3\" on another thread."
  ([f]
   (async f []))
  ([f arg-list]
   (future
     (try
       (apply f arg-list)
       (catch RuntimeException re
         ;;(error re "unhandled exception in thread"))))))
         (println "unhandled exception in thread" re))))))

;; handlers

(defn-spec async-event-handler fn?
  "wraps `f`, calling it with any given `args` later"
  [f fn?]
  (fn [& args]
    (async f args)))

(defn-spec async-handler fn?
  "same as `async-handler` but calls `f` and ignores `args`.
  useful for calling functions asynchronously that don't accept an `event` object."
  [f fn?]
  (fn [& _]
    (async f)))

(defn-spec event-handler fn?
  "wraps `f`, calling it with any given `args`.
  useful for debugging, otherwise just use the function directly"
  [f fn?]
  (fn [& args]
    (apply f args)))

(defn-spec handler fn?
  "wraps `f`, calling it but ignores any args.
  useful for calling functions that don't accept an `event` object."
  [f fn?]
  (fn [& _]
    (f)))

(def donothing
  "accepts any args, does nothing, returns nil.
  good for placeholder event handlers."
  (constantly nil))

(defn wow-dir-picker
  "prompts the user to select an addon dir. 
  if a valid addon directory is selected, calls `cli/set-addon-dir!`"
  [event]
  (when-let [dir (dir-chooser event)]
    (when (fs/directory? dir)
      ;; unlike swing, it doesn't appear possible to select a non-directory with javafx (good)
      (cli/set-addon-dir! dir))))

(defn exit-handler
  "exit the application. if running while testing or within a repl, it just closes the window"
  [& [_]]
  (cond
    ;; fresh repl => (restart) => (:in-repl? @state) => nil
    ;; because the app hasn't been started and there is no state yet, the app will exit.
    ;; when hitting ctrl-c while the gui is running, `(utils/in-repl?) => false` because it's running on the JavaFX thread,
    ;; and so will exit again there :( the double-check here seems to work though.
    (or (:in-repl? @core/state)
        (utils/in-repl?)) (swap! core/state assoc :gui-showing? false)
    (-> timbre/*config* :testing?) (swap! core/state assoc :gui-showing? false)
    ;; 2020-08-08: `ss/invoke-later` was keeping the old window around when running outside of repl.
    ;; `ss/invoke-soon` seems to fix that.
    ;;  - http://daveray.github.io/seesaw/seesaw.invoke-api.html
    ;; 2020-09-27: similar issue in javafx
    :else (Platform/runLater (fn []
                               (Platform/exit)
                               (System/exit 0)))))

(defn-spec switch-tab-handler fn?
  "returns a function that will switch the tab-pane to the tab at the given index when called"
  [tab-idx int?]
  (fn [event]
    (some-> (select "#tabber") first .getSelectionModel (.select tab-idx))))

(defn import-addon-handler
  "imports an addon by parsing a URL"
  [event]
  (let [addon-url (text-input "Enter URL of addon")
        fail-msg "Failed. URL must be:
  * valid
  * originate from github.com
  * addon uses 'releases'
  * latest release has a packaged 'asset'
  * asset must be a .zip file
  * zip file must be structured like an addon"
        failure #(alert event fail-msg {:type :error})
        warn-msg "Failed. Addon successfully added to catalogue but could not be installed."
        warning #(alert event warn-msg {:type :warning})]
    (when addon-url
      (if-let [result (core/add+install-user-addon! addon-url)]
        (when-not (contains? result :download-url)
          (warning))
        (failure))))
  nil)

(def json-files-extension-filters
  [{:description "JSON files" :extensions ["*.json"]}])

(defn import-addon-list-handler
  "prompts user with a file selection dialogue then imports a list of addons from the selected file"
  [event]
  (when-let [abs-path (file-chooser event {:filters json-files-extension-filters})]
    (core/import-exported-file abs-path)
    (core/refresh))
  nil)

(defn export-addon-list-handler
  "prompts user with a file selection dialogue then writes the current directory of addons to the selected file"
  [event]
  (when-let [abs-path (file-chooser event {:type :save
                                           :filters json-files-extension-filters})]
    (core/export-installed-addon-list-safely abs-path))
  nil)

(defn export-user-catalogue-handler
  "prompts user with a file selection dialogue then writes the user catalogue to selected file"
  [event]
  (when-let [abs-path (file-chooser event {:type :save
                                           :filters json-files-extension-filters})]
    (core/export-user-catalogue-addon-list-safely abs-path))
  nil)

(defn-spec -about-strongbox-dialog map?
  "returns a description of the 'about' dialog contents.
  separated here for testing and test coverage."
  []
  {:fx/type :v-box
   :id "about-dialog"
   :children [{:fx/type :text
               :id "about-pane-title"
               :text "strongbox"}
              {:fx/type :text
               :text (format "version %s" (core/strongbox-version))}
              {:fx/type :text
               :text (format "version %s is now available to download!" (core/latest-strongbox-release))
               :managed (not (core/latest-strongbox-version?))
               :visible (not (core/latest-strongbox-version?))}
              {:fx/type :hyperlink
               :text "https://github.com/ogri-la/strongbox"
               :on-action (handler #(utils/browse-to "https://github.com/ogri-la/strongbox"))
               :id "about-pane-hyperlink"}
              {:fx/type :text
               :text "AGPL v3"}]})

(defn about-strongbox-dialog
  "displays an informational dialog to the user about strongbox"
  [event]
  (alert event "" {:type :info
                   :content (-> (-about-strongbox-dialog) fx/create-component fx/instance)})
  nil)

(defn remove-selected-confirmation-handler
  "prompts the user to confirm if they *really* want to delete those addons they just selected and clicked 'delete' on"
  [event]
  (when-let [selected (core/get-state :selected-installed)]
    (if (utils/any (mapv :ignore? selected))
      (alert event "Selection contains ignored addons. Stop ignoring them and then delete." {:type :error})

      (let [label-list (mapv (fn [row]
                               {:fx/type :text
                                :text (str " - " (:name row))}) selected)
            content (fx/create-component {:fx/type :v-box
                                          :children (into [{:fx/type :text
                                                            :text (format "Deleting %s:" (count selected))}] label-list)})
            result (alert event "" {:content (fx/instance content)
                                    :type :confirm})]
        (when (= (.get result) ButtonType/OK)
          (core/remove-selected)))))
  nil)

(defn search-results-install-handler
  "this switches to the 'installed' tab, then, for each addon selected, expands summary, installs addon, calls load-installed-addons and finally refreshes;
  this presents as a plodding step-wise update but is better than a blank screen and apparent hang"
  [event]
  ;; original approach. efficient but no feedback for user
  ;; note: still true as of 2020-09?
  ;; todo: stick this in `ui.cli`
  ;;(core/-install-update-these (map curseforge/expand-summary (get-state :selected-search))) 
  ((switch-tab-handler INSTALLED-TAB) event)
  (doseq [selected (core/get-state :selected-search)]
    (some-> selected core/expand-summary-wrapper vector core/-install-update-these)
    (core/load-installed-addons))
  ;; deselect rows in search table
  ;; note: don't know how to do this in jfx
  ;;(ss/selection! (select-ui :#tbl-search-addons) nil) 
  (core/refresh))

;;

(def separator
  "horizontal rule to logically separate menu items"
  {:fx/type fx/ext-instance-factory
   :create #(javafx.scene.control.SeparatorMenuItem.)})

(defn-spec build-catalogue-menu (s/or :ok ::sp/list-of-maps, :no-catalogues nil?)
  "returns a list of radio button descriptions that can toggle through the available catalogues"
  [selected-catalogue :catalogue/name, catalogue-location-list :catalogue/location-list]
  (when catalogue-location-list
    (let [rb (fn [{:keys [label name]}]
               {:fx/type :radio-menu-item
                :text label
                :selected (= selected-catalogue name)
                :toggle-group {:fx/type fx/ext-get-ref
                               :ref ::catalogue-toggle-group}
                :on-action (async-handler #(cli/set-catalogue-location! name))})]
      (mapv rb catalogue-location-list))))

(defn-spec build-theme-menu ::sp/list-of-maps
  "returns a list of radio button descriptions that can toggle through the available themes defined in `core/themes`"
  [selected-theme ::sp/gui-theme, theme-map map?]
  (let [rb (fn [theme-key]
             {:fx/type :radio-menu-item
              :text (format "%s theme" (-> theme-key name clojure.string/capitalize))
              :selected (= selected-theme theme-key)
              :toggle-group {:fx/type fx/ext-get-ref
                             :ref ::theme-toggle-group}
              :on-action (fn [_]
                           (swap! core/state assoc-in [:cfg :gui-theme] theme-key)
                           (core/save-settings))})]
    (mapv rb (keys theme-map))))

(defn menu-bar
  "returns a description of the menu at the top of the application"
  [{:keys [fx/context]}]
  (let [file-menu [(menu-item "_New addon directory" (event-handler wow-dir-picker) {:key "Ctrl+N"})
                   (menu-item "Remove addon directory" (async-handler cli/remove-addon-dir!))
                   separator
                   (menu-item "E_xit" exit-handler {:key "Ctrl+Q"})]

        view-menu (into
                   [(menu-item "Refresh" (async-handler core/refresh) {:key "F5"})
                    separator
                    (menu-item "_Installed" (switch-tab-handler INSTALLED-TAB) {:key "Ctrl+I"})
                    (menu-item "Searc_h" (switch-tab-handler SEARCH-TAB) {:key "Ctrl+H"})
                    separator]
                   (build-theme-menu
                    (fx/sub-val context get-in [:app-state :cfg :gui-theme])
                    core/themes))

        catalogue-menu (into (build-catalogue-menu
                              (fx/sub-val context get-in [:app-state :cfg :selected-catalogue])
                              (fx/sub-val context get-in [:app-state :cfg :catalogue-location-list]))
                             [separator
                              (menu-item "Refresh user catalogue" (async-handler core/refresh-user-catalogue))])

        addon-menu [(menu-item "_Update all" (async-handler core/install-update-all) {:key "Ctrl+U"})
                    (menu-item "Re-install all" (async-handler core/re-install-all))]

        impexp-menu [(menu-item "Import addon from Github" (async-event-handler import-addon-handler))
                     separator
                     (menu-item "Import addon list" (async-event-handler import-addon-list-handler))
                     (menu-item "Export addon list" (async-event-handler export-addon-list-handler))
                     (menu-item "Export Github addon list" (async-event-handler export-user-catalogue-handler))]

        cache-menu [(menu-item "Clear http cache" (async-handler core/delete-http-cache!))
                    (menu-item "Clear addon zips" (async-handler core/delete-downloaded-addon-zips!))
                    (menu-item "Clear catalogues" (async-handler (juxt core/db-reload-catalogue core/delete-catalogue-files!)))
                    (menu-item "Clear log files" (async-handler core/delete-log-files!))
                    (menu-item "Clear all" (async-handler core/clear-all-temp-files!))
                    separator
                    (menu-item "Delete WowMatrix.dat files" (async-handler core/delete-wowmatrix-dat-files!))
                    (menu-item "Delete .wowman.json files" (async-handler (comp core/refresh core/delete-wowman-json-files!)))
                    (menu-item "Delete .strongbox.json files" (async-handler (comp core/refresh core/delete-strongbox-json-files!)))]

        help-menu [(menu-item "About strongbox" about-strongbox-dialog)]]

    {:fx/type fx/ext-let-refs
     :refs {::catalogue-toggle-group {:fx/type :toggle-group}
            ::theme-toggle-group {:fx/type :toggle-group}}
     :desc {:fx/type :menu-bar
            :id "main-menu"
            :menus [(menu "_File" file-menu)
                    (menu "_View" view-menu)
                    (menu "Catalogue" catalogue-menu)
                    (menu "_Addons" addon-menu)
                    (menu "_Import/Export" impexp-menu)
                    (menu "Cache" cache-menu)
                    (menu "Help" help-menu)]}}))

(defn wow-dir-dropdown
  [{:keys [fx/context]}]
  (let [config (fx/sub-val context get-in [:app-state :cfg])
        selected-addon-dir (:selected-addon-dir config)
        addon-dir-map-list (get config :addon-dir-list [])]
    {:fx/type ext-recreate-on-key-changed
     :key (sort-by :addon-dir addon-dir-map-list)
     :desc {:fx/type :combo-box
            :value selected-addon-dir
            :on-value-changed (async-event-handler
                               (fn [new-addon-dir]
                                 (cli/set-addon-dir! new-addon-dir)))
            :items (mapv :addon-dir addon-dir-map-list)}}))

(defn game-track-dropdown
  [{:keys [fx/context]}]
  (let [selected-addon-dir (fx/sub-val context get-in [:app-state :cfg :selected-addon-dir])]
    {:fx/type :combo-box
     :id "game-track-combo-box"
     :value (-> selected-addon-dir core/get-game-track (or "") name)
     :on-value-changed (async-event-handler
                        (fn [new-game-track]
                          (core/set-game-track! (keyword new-game-track))
                          (core/refresh)))
     :items ["retail" "classic"]}))

(defn installed-addons-menu-bar
  "returns a description of the installed-addons tab-pane menu"
  [_]
  {:fx/type :h-box
   :padding 10
   :spacing 10
   :children [{:fx/type :button
               :text "Update all"
               :id "update-all-button"
               :on-action (async-handler core/install-update-all)}
              {:fx/type wow-dir-dropdown}
              {:fx/type game-track-dropdown}
              {:fx/type :button
               :text (str "Update Available: " (core/latest-strongbox-release))
               :on-action (handler #(utils/browse-to "https://github.com/ogri-la/strongbox/releases"))
               :visible (not (core/latest-strongbox-version?))
               :managed (not (core/latest-strongbox-version?))}]})

(defn-spec table-column map?
  "returns a description of a table column that lives within a table"
  [column-data :gui/column-data]
  (let [column-name (:text column-data)
        default-cvf (fn [row] (get row (keyword column-name)))
        final-cvf {:cell-value-factory (get column-data :cell-value-factory default-cvf)}

        final-style {:style-class (into ["table-cell"
                                         (lower-case (str column-name "-column"))]
                                        (get column-data :style-class))}

        default {:fx/type :table-column
                 :min-width 80}]
    (merge default column-data final-cvf final-style)))

(defn-spec -href-to-hyperlink map?
  "returns a hyperlink description or an empty text description"
  [row (s/keys :opt-un [::sp/url])]
  (if-let [label (utils/source-to-href-label-fn (:url row))]
    {:fx/type :hyperlink
     :on-action (handler #(utils/browse-to (:url row)))
     :text (str "↪ " label)}
    {:fx/type :text
     :text ""}))

;;(defn-spec href-to-hyperlink (s/or :ok :javafx/hyperlink-component, :noop :javafx/text-component)
(defn href-to-hyperlink
  "returns a hyperlink instance or empty text"
  [row]
  (-> row -href-to-hyperlink fx/create-component fx/instance))

(defn installed-addons-table
  [{:keys [fx/context]}]
  ;; subscribe to re-render table when addons become unsteady
  (fx/sub-val context get-in [:app-state :unsteady-addons])
  (let [row-list (fx/sub-val context get-in [:app-state :installed-addon-list])

        iface-version (fn [row]
                        (some-> row :interface-version str utils/interface-version-to-game-version))

        column-list [{:text "source" :min-width 110 :pref-width 120 :max-width 160 :cell-value-factory href-to-hyperlink}
                     {:text "name" :min-width 150 :pref-width 300 :max-width 500 :cell-value-factory (comp no-new-lines :label)}
                     {:text "description" :pref-width 700 :cell-value-factory (comp no-new-lines :description)}
                     {:text "installed" :max-width 150 :cell-value-factory :installed-version}
                     {:text "available" :max-width 150 :cell-value-factory :version}
                     {:text "WoW" :max-width 100 :cell-value-factory iface-version}]]
    {:fx/type fx.ext.table-view/with-selection-props
     :props {:selection-mode :multiple
             ;; unlike gui.clj, we have access to the original data here. no need to re-select addons.
             :on-selected-items-changed core/select-addons*}
     :desc {:fx/type :table-view
            :id "installed-addons"
            :placeholder {:fx/type :text
                          :style-class ["table-placeholder-text"]
                          :text "No addons found."}
            :column-resize-policy javafx.scene.control.TableView/CONSTRAINED_RESIZE_POLICY
            :pref-height 999.0
            :row-factory {:fx/cell-type :table-row
                          :describe (fn [row]
                                      {:style-class
                                       (remove nil?
                                               ["table-row-cell" ;; `:style-class` will actually *replace* the list of classes
                                                (when (:update? row) "updateable")
                                                (when (:ignore? row) "ignored")
                                                (when (and row (core/unsteady? (:name row))) "unsteady")])})}
            :columns (mapv table-column column-list)
            :context-menu {:fx/type :context-menu
                           :items [(menu-item "Update" (async-handler core/install-update-selected))
                                   (menu-item "Re-install" (async-handler core/re-install-selected))
                                   separator
                                   (menu-item "Ignore" (async-handler core/ignore-selected))
                                   (menu-item "Stop ignoring" (async-handler core/clear-ignore-selected))
                                   separator
                                   (menu-item "Delete" remove-selected-confirmation-handler)]}
            :items (or row-list [])}}))

(defn notice-logger
  [{:keys [fx/context]}]
  (let [log-message-list (fx/sub-val context :log-message-list)
        log-message-list (reverse log-message-list) ;; nfi how to programmatically change column sort order
        column-list [{:id "level" :text "level" :max-width 100 :cell-value-factory (comp name :level)}
                     {:id "time" :text "time" :max-width 100 :cell-value-factory :time}
                     {:text "message" :pref-width 500 :cell-value-factory :message}]]
    {:fx/type :table-view
     :id "notice-logger"
     :selection-mode :multiple
     :row-factory {:fx/cell-type :table-row
                   :describe (fn [row]
                               {:style-class ["table-row-cell" (name (:level row))]})}
     :column-resize-policy javafx.scene.control.TableView/CONSTRAINED_RESIZE_POLICY
     :columns (mapv table-column column-list)
     :items (or log-message-list [])}))

(defn installed-addons-pane
  [_]
  {:fx/type :v-box
   :children [{:fx/type installed-addons-menu-bar}
              {:fx/type installed-addons-table}]})

(defn search-addons-table
  [{:keys [fx/context]}]
  (let [idx-key #(select-keys % [:source :source-id])
        installed-addon-idx (mapv idx-key (fx/sub-val context get-in [:app-state :installed-addon-list]))

        addon-list (fx/sub-val context get-in [:app-state :search-results])
        column-list [{:text "source" :min-width 110 :pref-width 120 :max-width 160 :cell-value-factory href-to-hyperlink}
                     {:text "name" :min-width 150 :pref-width 300 :max-width 450 :cell-value-factory (comp no-new-lines :label)}
                     {:text "description" :pref-width 700 :cell-value-factory (comp no-new-lines :description)}
                     {:text "tags" :pref-width 380 :min-width 230 :max-width 450 :cell-value-factory (comp str :tag-list)}
                     {:text "updated" :min-width 85 :max-width 120 :pref-width 100 :cell-value-factory (comp #(utils/safe-subs % 10)  :updated-date)}
                     {:text "downloads" :min-width 100 :max-width 120 :cell-value-factory :download-count}]]
    {:fx/type fx.ext.table-view/with-selection-props
     :props {:selection-mode :multiple
             ;; unlike gui.clj, we have access to the original data here. no need to re-select addons.
             :on-selected-items-changed core/select-addons-search*}
     :desc {:fx/type :table-view
            :id "search-addons"
            :placeholder {:fx/type :text
                          :style-class ["table-placeholder-text"]
                          :text "No search results."}
            :row-factory {:fx/cell-type :table-row
                          :describe (fn [row]
                                      {:style-class ["table-row-cell"
                                                     (when (utils/in? (idx-key row) installed-addon-idx)
                                                       "installed")]})}
            :column-resize-policy javafx.scene.control.TableView/CONSTRAINED_RESIZE_POLICY
            :pref-height 999.0
            :columns (mapv table-column column-list)
            :items addon-list}}))

(defn search-addons-search-field
  [_]
  {:fx/type :h-box
   :padding 10
   :spacing 10
   :children [{:fx/type :text-field
               :id "search-text-field"
               :prompt-text "search"
               :on-text-changed (fn [v]
                                  (swap! core/state assoc :search-field-input v))}
              {:fx/type :button
               :text "install selected"
               :on-action (async-event-handler search-results-install-handler)}
              {:fx/type :button
               :text "random"
               :on-action (fn [_]
                            (swap! core/state assoc :search-field-input
                                   (if (nil? (:search-field-input @core/state)) "" nil)))}]})

(defn search-addons-pane
  [_]
  {:fx/type :v-box
   :children [{:fx/type search-addons-search-field}
              {:fx/type search-addons-table}]})

(defn tabber
  [_]
  {:fx/type :tab-pane
   :id "tabber"
   :tabs [{:fx/type :tab
           :text "installed"
           :closable false
           :content {:fx/type installed-addons-pane}}
          {:fx/type :tab
           :text "search"
           :closable false
           :on-selection-changed (fn [ev]
                                   (when (-> ev .getTarget .isSelected)
                                     (let [text-field (-> ev .getTarget .getTabPane (.lookupAll "#search-text-field") first)]
                                       (Platform/runLater
                                        (fn []
                                          (-> text-field .requestFocus))))))
           :content {:fx/type search-addons-pane}}]})

(defn status-bar
  "this is the litle strip of text at the bottom of the application."
  [{:keys [fx/context]}]
  (let [num-matching-template "%s of %s installed addons found in catalogue."
        all-matching-template "all installed addons found in catalogue."
        catalogue-count-template "%s addons in catalogue."

        ia (fx/sub-val context get-in [:app-state :installed-addon-list])

        uia (filter :matched? ia)

        a-count (count (fx/sub-val context get-in [:app-state :db]))
        ia-count (count ia)
        uia-count (count uia)

        strings [(format catalogue-count-template a-count)
                 (if (= ia-count uia-count)
                   all-matching-template
                   (format num-matching-template uia-count ia-count))]]

    {:fx/type :h-box
     :id "status-bar"
     :children [{:fx/type :text
                 :style-class ["text"]
                 :text (join " " strings)}]}))

;;

(defn app
  "returns a description of the javafx Stage, Scene and the 'root' node.
  the root node is the top-most node from which all others are descendents of."
  [{:keys [fx/context]}]
  (let [;; re-render gui whenever style state changes
        style (fx/sub-val context get :style)
        showing? (fx/sub-val context get-in [:app-state :gui-showing?])
        theme (fx/sub-val context get-in [:app-state :cfg :gui-theme])]
    {:fx/type :stage
     :showing showing?
     :on-close-request exit-handler
     :title "strongbox"
     :width 1024
     :height 768
     :scene {:fx/type :scene
             :stylesheets [(::css/url style)]
             :root {:fx/type :v-box
                    :id (name theme)
                    :children [{:fx/type menu-bar}
                               {:fx/type :split-pane
                                :id "splitter"
                                :orientation :vertical
                                :divider-positions [0.7]
                                :items [{:fx/type tabber}
                                        {:fx/type notice-logger}]}
                               {:fx/type status-bar}]}}}))

;; absolutely no logging in here
(defn init-notice-logger!
  "log handler for the gui. incoming log messages update the gui"
  [gui-state]
  (let [gui-logger (fn [log-data]
                     (let [{:keys [timestamp_ msg_ level]} log-data
                           this-msg (force msg_)
                           prev-msg (-> (fx/sub-val @gui-state :log-message-list) last :message)
                           log-line {:level level :time (force timestamp_) :message this-msg}]
                       ;; infinite recursion protection.
                       ;; logging updates the `log-message-list` ->
                       ;;   that causes the notice-logger to update it's rows ->
                       ;;   that requires re-rendering the GUI ->
                       ;;   that calls *all* of the component functions ->
                       ;;   that may trigger log messages ->
                       ;;   that causes the notice-logger to update it's rows ->
                       ;;   ...
                       ;; the trade off is duplicate messages are not displayed
                       (when-not (= prev-msg this-msg)
                         (swap! gui-state fx/swap-context update-in [:log-message-list] conj log-line))))]
    (logging/add-appender! :gui gui-logger {:timestamp-opts {:pattern "HH:mm:ss"}})))

(defn start
  []
  (println "starting gui")
  (let [;; the gui uses a copy of the application state because the state atom needs to be wrapped
        state-template {:app-state nil,
                        :log-message-list []
                        :style (style)}
        gui-state (atom (fx/create-context state-template)) ;; cache/lru-cache-factory))
        update-gui-state (fn [new-state]
                           (swap! gui-state fx/swap-context assoc :app-state new-state))
        _ (core/state-bind [] update-gui-state)

        _ (init-notice-logger! gui-state)

        ;; css watcher for live coding
        _ (add-watch #'style :refresh-app (fn [_ _ _ _]
                                            (swap! gui-state fx/swap-context assoc :style (style))))
        _ (core/add-cleanup-fn #(remove-watch core/state :refresh-app))

        ;; asynchronous searching. as the user types, update the state with search results asynchronously
        ;; todo: stick this in core/cli?
        ;; todo: cancel any current searching going on?
        save-search-results (fn [new-state]
                              (future
                                (swap! core/state assoc :search-results (core/db-search (:search-field-input new-state)))))
        _ (core/state-bind [:search-field-input] save-search-results)

        renderer (fx/create-renderer
                  :middleware (comp
                               fx/wrap-context-desc
                               (fx/wrap-map-desc (fn [_] {:fx/type app})))

                  ;; magic :(

                  :opts {:fx.opt/type->lifecycle #(or (fx/keyword->lifecycle %)
                                                      ;; For functions in `:fx/type` values, pass
                                                      ;; context from option map to these functions
                                                      (fx/fn->lifecycle-with-context %))})

        ;; don't do this, renderer has to be unmounted and the app closed before further state changes happen during cleanup
        ;;_ (core/add-cleanup-fn #(fx/unmount-renderer gui-state renderer))
        _ (swap! core/state assoc :disable-gui (fn []
                                                 (fx/unmount-renderer gui-state renderer)
                                                 ;; the slightest of delays allows any final rendering to happen before the exit-handler is called.
                                                 ;; only affects testing from the repl apparently and not `./run-tests.sh`
                                                 (Thread/sleep 25)))

        ;; on first load, because the catalogue hasn't been loaded
        ;; and because the search-field-input doesn't change,
        ;; and because the search component isn't re-rendered,
        ;; fake a change to get something to appear
        bump-search (fn []
                      (when-not (:search-field-input core/state)
                        (swap! core/state assoc :search-field-input "")))]

    (swap! core/state assoc :gui-showing? true)
    (fx/mount-renderer gui-state renderer)

    ;; `refresh` the app but kill the `refresh` if app is closed before it finishes.
    ;; happens during testing and causes a few weird windows to hang around.
    ;; see `(mapv (fn [_] (test :jfx)) (range 0 100))`
    (let [kick (future
                 (core/refresh)
                 (bump-search))]
      (core/add-cleanup-fn #(future-cancel kick)))

    ;; calling the `renderer` will re-render the GUI.
    ;; useful apparently, but not being used.
    ;;renderer
    ))

(defn stop
  []
  (println "stopping gui")
  (when-let [unmount-renderer (:disable-gui @core/state)]
    ;; only affects tests running from repl apparently
    (unmount-renderer))
  (exit-handler)
  nil)
