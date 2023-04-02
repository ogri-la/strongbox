(ns strongbox.jfx
  (:require
   [me.raynes.fs :as fs]
   [clojure.pprint]
   [clojure.set]
   [clojure.java.io :as io]
   ;;[clojure.core.cache :as cache]
   [clojure.string :refer [lower-case join capitalize replace] :rename {replace str-replace}]
   ;; logging in the gui should be avoided as it can lead to infinite loops
   [taoensso.timbre :as timbre :refer [spy]] ;; info debug warn error]] 
   [cljfx.ext.table-view :as fx.ext.table-view]
   [cljfx.ext.tree-table-view :as fx.ext.tree-table-view]
   [cljfx.lifecycle :as fx.lifecycle]
   [cljfx.component :as fx.component]
   [cljfx.api :as fx]
   [cljfx.ext.node :as fx.ext.node]
   [cljfx.css :as css]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [strongbox.check-combo-box :as controlsfx.check-combo-box]
   [strongbox
    [cli :as cli]
    [nfo :as nfo]
    [constants :as constants]
    [joblib :as joblib]
    [logging :as logging]
    [addon :as addon]
    [specs :as sp]
    [utils :as utils :refer [no-new-lines message-list]]
    [core :as core]])
  (:import
   [javafx.scene.text Font]
   [java.util List Calendar Locale]
   [javafx.util Callback]
   [javafx.scene.control TreeTableRow TableRow TextInputDialog Alert Alert$AlertType ButtonType]
   [javafx.scene.input MouseButton MouseEvent KeyEvent KeyCode]
   [javafx.stage Stage FileChooser FileChooser$ExtensionFilter DirectoryChooser Window WindowEvent]
   [javafx.application Platform]
   [javafx.scene Node]
   [javafx.event Event]
   [java.text NumberFormat]))

(defn load-font-from-resources
  [resource]
  (-> resource io/resource str (Font/loadFont 40.0)))

(def embedded-font (load-font-from-resources "fontawesome-4.7.0.ttf"))

;; javafx hack, fixes combobox that sometimes goes blank:
;; - https://github.com/cljfx/cljfx/issues/76#issuecomment-645563116
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

(def user-locale (Locale/getDefault))
(def ^java.text.NumberFormat number-formatter (NumberFormat/getNumberInstance user-locale))

(defn format-number
  [^Integer n]
  (.format number-formatter n))

(def major-theme-map
  {:light
   {:base "#ececec"
    :accent "lightsteelblue"
    :table-border "#bbb"
    :row "-fx-control-inner-background"
    :row-hover "derive(-fx-control-inner-background,-10%)"
    :row-selected "lightsteelblue"
    :unsteady "lightsteelblue"
    :row-updateable "lemonchiffon"
    :row-updateable-selected "#fdfd96" ;; "Lemon Meringue" (brighter yellow)
    :row-updateable-text "black"
    :row-warning "lemonchiffon"
    :row-warning-text "black"
    :row-error "tomato"
    :row-error-text "black"
    :row-report-text "blue"
    :hyperlink "blue"
    :hyperlink-updateable "blue"
    :hyperlink-weight "normal"
    :table-font-colour "-fx-text-base-color"
    :row-alt "-fx-control-inner-background-alt"
    :uber-button-tick "darkseagreen"
    :uber-button-warn "orange"
    :uber-button-error "red"
    :star-hover "#aaa"
    :star-unstarred "#ddd"
    :star-starred "#ffbf00" ;; bright yellow
    }

   :dark ;; "'dracula' theme: https://github.com/dracula/dracula-theme"
   {:base "#1e1f29"
    :accent "#44475a"
    :table-border "#333"
    :row "#1e1f29" ;; same as :base
    :row-hover "derive(-fx-control-inner-background,-50%)"
    :row-selected "derive(-fx-control-inner-background,-30%)"
    :unsteady "#bbb"
    :row-updateable "#6272a4" ;; (blue)
    :row-updateable-selected "#6272c3" ;; (brighter blue) ;; todo: can this be derived from :row-updateable?
    :row-updateable-text "white"
    :row-warning "#ffb86c"
    :row-warning-text "black"
    :row-error "#ff5555"
    :row-error-text "black"
    :row-report-text "#bd93f9"
    :hyperlink "#f8f8f2"
    :hyperlink-updateable "white"
    :hyperlink-weight "bold"
    :table-font-colour "-fx-text-base-color"
    :row-alt "#22232e"
    :uber-button-tick "aquamarine"
    :uber-button-warn "#ffb86c"
    :uber-button-error "red"
    :star-hover "#6272c3"
    :star-unstarred "#555"
    :star-starred "#6495ed"}})

(def sub-theme-map
  {:dark
   {:green
    {:row-updateable "#50a67b" ;; (green)
     :row-updateable-selected "#40c762" ;; (brighter green)
     :row-updateable-text "black"
     :hyperlink-updateable "black"
     :star-hover "#50a67b"
     :star-starred "#40c762"}

    :orange
    {:row-updateable "#df8750" ;; (orange)
     :row-updateable-selected "#df722e" ;; (brighter orange)
     :row-updateable-text "black"
     :hyperlink-updateable "black"
     :uber-button-error "brown"
     :star-hover "#df8750"
     :star-starred "#df722e"}}})

(def themes
  (into major-theme-map
        (for [[major-theme-key sub-theme-val] sub-theme-map
              [sub-theme-key sub-theme] sub-theme-val
              :let [major-theme (get major-theme-map major-theme-key)
                    ;; "dark-green", "dark-orange"
                    theme-name (keyword (format "%s-%s" (name major-theme-key) (name sub-theme-key)))]]
          {theme-name (merge major-theme sub-theme)})))

(defn expand*
  [acc [key val]]
  (cond
    (vector? key) (reduce expand* acc (zipmap key (repeat val)))
    (map? val) (assoc acc key (reduce expand* {} val))
    :else
    (assoc acc key val)))

(defn expand
  "not sure how javafx, css and cljfx-css are handling '.class1, .class2 { ... }' type specifiers, but I can't get them to work.
  instead, I specify the affected classes as a list and this duplicates the rules."
  [m]
  (reduce expand* {} m))

(defn-spec style map?
  "generates javafx css definitions for the different themes.
  if editor is connected to a running repl session then modifying
  the css will reload the running GUI for immediate feedback."
  []
  (css/register
   ::style
   (let [generate-style
         (fn [theme-kw]
           (let [colour-map (get themes theme-kw)
                 colour #(name (get colour-map % "pink"))]
             {;;
              ;; 'about' dialog
              ;; lives outside of main styling for some reason
              ;;

              ".dialog-pane"
              {:-fx-min-width "500px"}

              ".dialog-pane .content"
              {:-fx-line-spacing "3"}

              "#about-dialog #about-pane-hyperlink"
              {:-fx-font-size "1.1em"
               :-fx-padding "0 0 4 -1"}

              "#about-dialog #about-pane-hyperlink:hover"
              {:-fx-text-fill "blue"}

              "#about-dialog #about-pane-title"
              {:-fx-font-size "1.6em"
               :-fx-font-weight "bold"
               :-fx-padding ".5em 0"}

              ;;
              ;; main app styling
              ;; 

              (format "#%s.root " (name theme-kw))
              {:-fx-padding 0
               :-fx-base (colour :base)
               :-fx-accent (colour :accent) ;; selection colour of backgrounds

               "#main-menu"
               {:-fx-background-color (colour :base)} ;; removes gradient from 'File' menu

               ".combo-box-base"
               {:-fx-background-radius "0"
                ;; truncation now happens from the left. thanks to:
                ;; https://stackoverflow.com/questions/36264656/scalafx-javafx-how-can-i-change-the-overrun-style-of-a-combobox
                " > .list-cell" {:-fx-text-overrun "leading-ellipsis"}}

               ".button"
               {:-fx-background-radius "0"
                :-fx-padding "5px 17px" ;; makes buttons same height as dropdowns
                }

               ;;
               ;; hyperlinks
               ;;

               ".hyperlink"
               {:-fx-underline "false"
                :-fx-font-weight (colour :hyperlink-weight)
                :-fx-text-fill (colour :hyperlink)}

               ;;
               ;; tabber
               ;;

               ".tab-pane > .tab-header-area "
               {:-fx-padding ".7em 0 0 .6em"

                "> .headers-region > .tab"
                {:-fx-background-radius "0"
                 :-fx-padding ".25em 1em"
                 :-fx-focus-color "transparent" ;; disables the 'blue box' of selected widgets
                 :-fx-faint-focus-color "transparent" ;; literally, a very faint box remains
                 }}

               ;;
               ;; common styling for all tables
               ;;

               ".table-view "
               {:-fx-table-cell-border-color (colour :table-border)
                :-fx-font-size ".9em"

                ".hyperlink" {:-fx-padding "-2 0 0 0"}

                ".table-placeholder-text" {:-fx-font-size "3em"}

                ".column-header" {:-fx-font-size "1em"}

                [".table-row-cell" ".tree-table-row-cell"]
                {:-fx-border-insets "-1 -1 0 -1"
                 :-fx-border-color (colour :table-border)}

                ;;
                ;; common column styling
                ;;

                ;; 'wide' buttons, like "[  install  ]" buttons

                ".wide-button-column.table-cell"
                {:-fx-padding "0px"
                 :-fx-alignment "center"}

                ".wide-button-column .button"
                {:-fx-pref-width 100
                 :-fx-padding "2px 0"
                 :-fx-background-radius "4"}

                ;; columns with buttons that don't look like buttons (star, uber-button, etc)
                ".invisible-button-column"
                {:-fx-padding 0
                 :-fx-alignment "top-center"}

                ".invisible-button-column > .button"
                {:-fx-padding 0
                 :-fx-background-color nil
                 ;; invisible button should fill width of column
                 :-fx-max-width "10em"}

                ;; cells in ignored rows are semi-transparent
                ".ignored .table-cell" {:-fx-opacity "0.5"
                                        :-fx-font-style "italic"}

                ;; wide buttons in ignored cells get slightly different styling
                ".ignored .wide-button-column.table-cell"
                {:-fx-opacity "1" ;; a disabled button already has a greying effect applied
                 :-fx-font-style "normal"}

                [".version-column" ".installed-column" ".available-version-column"]
                {:-fx-alignment "center-right"
                 :-fx-text-overrun "leading-ellipsis"}

                ".id-column"
                {:-fx-alignment "center"
                 :-fx-text-overrun "leading-ellipsis"}

                ".created-column"
                {:-fx-alignment "center"}

                ".updated-column"
                {:-fx-alignment "center"}}

               ;;
               ;; common styling for tree-tables
               ;;

               ".tree-table-row-cell > .tree-disclosure-node"
               {;; default is "4 6 4 8" but this makes the hitbox a tiny bit easier to hit
                :-fx-padding "9"
                " > .arrow" {:-fx-background-color (colour :table-font-colour)}}

               ;;
               ;; common styling for install + search tables
               ;;

               ["#installed-addons .table-view " "#search-addons .table-view "]
               {[".table-row-cell" ".tree-table-row-cell"]
                {:-fx-background-color (colour :row) ;; even rows

                 " .table-cell" {:-fx-text-fill (colour :table-font-colour)}
                 ":hover" {:-fx-background-color (colour :row-hover)}
                 ":selected" {:-fx-background-color (colour :row-selected)
                              :-fx-table-cell-border-color (colour :table-border)
                              " .table-cell" {:-fx-text-fill "-fx-focused-text-base-color"}}
                 ":selected:hover" {:-fx-background-color (colour :row-hover)}

                 ":odd" {;;:-fx-background-color (colour :row)
                         ":hover" {:-fx-background-color (colour :row-hover)}
                         ":selected" {:-fx-background-color (colour :row-selected)}
                         ":selected:hover" {:-fx-background-color (colour :row-hover)}}}

                ".tag-button-column"
                {:-fx-padding "-1 0 0 0"
                 " .button" {:-fx-min-width 50
                             :-fx-font-size ".9em"
                             :-fx-padding "4px 5px"
                             :-fx-background-color "none"
                             :-fx-opacity "1"
                             :-fx-border-width "0 1 0 0"
                             :-fx-border-color (colour :table-border)
                             :-fx-text-overrun "word-ellipsis"
                             ":hover" {:-fx-background-color (colour :row-updateable-selected)}}}}

               ;;
               ;; installed-addons tab
               ;;

               "#installed-addons "
               {".table-view #placeholder "
                {:-fx-alignment "center"

                 ".big-welcome-text"
                 {:-fx-font-size "5em"
                  :-fx-font-weight "bold"
                  :-fx-padding ".3em 1em"
                  :-fx-spacing "1em"}

                 ".big-welcome-subtext"
                 {:-fx-font-size "1.6em"
                  :-fx-font-family "monospace"
                  :-fx-padding ".8em 0 1em 0"}}

                "#update-all-button"
                {:-fx-min-width "110px"}

                "#game-track-container "
                {:-fx-alignment "center"

                 "#game-track-check-box"
                 {:-fx-padding "0 0 0 .65em"
                  :-fx-min-width "70px"}}

                ".table-view#installed-addons-table "
                {".wow-column"
                 {:-fx-alignment "center"}

                 ;; 2023-03-04: 'WoW' column text was black in dark mode, missing styling from 'table-cell'.
                 ;; added 'table-cell' to the :game-version `:desc`, but then it had a border-right-width of 1,
                 ;; which is only present in buttons. not sure what is going on but this fixes that.
                 ".wow-column .table-cell"
                 {:-fx-border-width "0"}

                 ".uber-button"
                 {:-fx-font-size "1.3em"
                  :-fx-padding "1 0"
                  :-fx-text-fill (colour :uber-button-tick) ;; green tick
                  :-fx-font-family "'FontAwesome'"}

                 ".table-row-cell.warnings .invisible-button-column > .uber-button"
                 {;; orange bar
                  :-fx-text-fill (colour :uber-button-warn)}

                 ".table-row-cell.errors .invisible-button-column > .uber-button"
                 {;; red cross
                  :-fx-text-fill (colour :uber-button-error)}

                 ".child-row .table-cell"
                 {:-fx-opacity 0.6}

                 ".updateable"
                 {:-fx-background-color (str (colour :row-updateable) " !important")

                  " .table-cell"
                  {:-fx-text-fill (colour :row-updateable-text)}

                  " .hyperlink"
                  {:-fx-text-fill (colour :hyperlink-updateable)}

                  " .version-column"
                  {:-fx-font-weight "bold"}

                  ;; selected+updateable addons look *slightly* different
                  ":selected"
                  {;; !important so that hovering over a selected+updateable row doesn't change it's colour
                   :-fx-background-color (str (colour :row-updateable-selected) " !important")}}

                 ".ignored"
                 {" .invisible-button-column > .button"
                  ;; !important because an orange warning colour is being inherited from somewhere
                  {:-fx-text-fill "gray !important"}}}}

               ;;
               ;; notice-logger
               ;;

               "#notice-logger "
               {".table-view "
                {:-fx-font-family "monospace"

                 ".warn .table-cell"
                 {:-fx-text-fill (colour :row-warning-text)
                  :-fx-background-color (colour :row-warning)}

                 ".warn:selected"
                 {:-fx-background-color "-fx-selection-bar"}

                 ".error .table-cell"
                 {:-fx-text-fill (colour :row-error-text)
                  :-fx-background-color (colour :row-error)}

                 ".error:selected"
                 {:-fx-background-color "-fx-selection-bar"}

                 ".report .table-cell"
                 {:-fx-text-fill (colour :row-report-text)}

                 ".report #message"
                 {:-fx-font-style "italic"}

                 "#level"
                 {:-fx-alignment "center"}

                 "#source"
                 {:-fx-alignment "center"}

                 "#time"
                 {:-fx-alignment "center"}

                 "#message.column-header .label"
                 {:-fx-alignment "center-left"}

                 ".table-row-cell .message-text"
                 {:-fx-fill "-fx-text-background-color"}}

               ;;
               ;; notice-logger-nav
               ;;

                "#notice-logger-nav"
                {:-fx-padding "1.1em .75em" ;; 1.1em so installed, search and log pane tables all start at the same height
                 :-fx-font-size ".9em"

                 " .radio-button"
                 {:-fx-padding "0 .5em"}}}

               ;;
               ;; search
               ;;

               "#search-addons "
               {".star-column:hover > .button"
                {:-fx-text-fill (colour :star-hover)}

                ".star-column > .button"
                {:-fx-padding "1 0"
                 :-fx-font-size "1.3em"
                 :-fx-text-fill (colour :star-unstarred)

                 ".starred"
                 {:-fx-text-fill (colour :star-starred)}}

                "#search-install-button"
                {:-fx-min-width "90px"}

                "#search-random-button"
                {:-fx-min-width "80px"}

                "#search-user-catalogue-button"
                {:-fx-font-weight "bold"
                 :-fx-font-size "1.2em"
                 :-fx-padding "2 7 "

                 ".starred" {:-fx-text-fill (colour :star-starred)
                             ;; the yellow of the star doesn't stand out from the gray gradient behind it.
                             ;; this gives the text a border (stroke) and a very faint glow.
                             " .text" {:-fx-stroke (colour :table-font-colour)
                                       :-fx-stroke-width ".2"
                                       :-fx-effect (str "dropshadow( gaussian , " (colour :star-starred) " , 10, 0.0 , 0 , 0 )")}}}

                "#search-prev-button"
                {:-fx-min-width "80px"}

                "#search-next-button"
                {:-fx-min-width "70px"}

                "#search-text-field "
                {:-fx-min-width "100px"
                 :-fx-text-fill (colour :table-font-colour)}

                "#search-selected-tag-bar"
                {:-fx-padding "0 0 10 10"
                 :-fx-spacing "10"
                 " > .button" {:-fx-padding "2.5 8"
                               :-fx-background-radius "4"}}

                ".table-view "
                {".downloads-column" {:-fx-alignment "center-right"}
                 ".updated-column" {:-fx-alignment "center"}}}

               ;;
               ;; status bar (bottom of app)
               ;; 

               "#status-bar "
               {:-fx-font-size ".9em"
                :-fx-padding "0"
                :-fx-alignment "center-left"

                "#status-bar-left"
                {:-fx-padding "0 10"
                 :-fx-alignment "center-left"
                 :-fx-pref-width 9999.0
                 " > .text" {;; omg, wtf does 'fx-fill' work and not 'fx-text-fill' ???
                             :-fx-fill (colour :table-font-colour)}}

                "#status-bar-right"
                {:-fx-min-width "130px" ;; long enough to render "warnings (999)"
                 :-fx-padding "5px 12px 5px 0"
                 :-fx-alignment "center-right"}

                "#status-bar-right .button"
                {:-fx-padding "4 10"
                 ;; doesn't look right when button is coloured.
                 ;;:-fx-background-radius "4"
                 :-fx-font-size "11px"

                 ;; this isn't great but it's better than nothing. revisit when it makes more sense.
                 ":armed"
                 {:-fx-background-insets "1 1 0 0,  1,  2,  3"}}

                ".toggle-button.with-warning"
                {:-fx-text-fill (colour :row-warning-text)
                 :-fx-base (colour :row-warning)}

                ".toggle-button.with-error"
                {:-fx-text-fill (colour :row-error-text)
                 :-fx-base (colour :row-error)}}

               ;;
               ;; addon-detail
               ;;

               "#addon-detail-pane "
               {".table-row-cell.installed"
                {:-fx-background-color (colour :row-updateable)}
                ".table-row-cell.updateable"
                {:-fx-background-color (colour :row-updateable-selected)}

                ".title"
                {:-fx-font-size "2.5em"
                 :-fx-padding "1em 0 .5em 1em"
                 :-fx-text-fill "-fx-text-base-color"}

                ".subtitle"
                {:-fx-font-size "1.1em"
                 :-fx-text-fill "-fx-text-base-color"
                 :-fx-padding "0 0 .5em 1.75em"}

                ".subtitle .installed-version"
                {:-fx-text-fill "-fx-text-base-color"
                 :-fx-padding "0 1em 0 0"}

                ".subtitle .version"
                {:-fx-text-fill (colour :row-updateable-text)
                 :-fx-background-color (colour :row-updateable-selected)
                 :-fx-padding "0 .75em"
                 :-fx-background-radius ".4em"}

                ".subtitle .hyperlink"
                {:-fx-padding "0 1.5em 0 0"
                 :-fx-font-size ".9em"}

                ".section-title"
                {:-fx-font-size "1.3em"
                 :-fx-padding "0 0 .5em 1em"
                 :-fx-min-width "200px"
                 :-fx-text-fill "-fx-text-base-color"}

                ".section-description"
                {:-fx-font-style "italic"
                 :-fx-padding "0 0 .7em 1em"}

                ".disabled-text"
                {:-fx-opacity "0.3"}

                ".description"
                {:-fx-font-size "1.4em"
                 :-fx-padding "1em .5em 2em 1.5em"
                 :-fx-wrap-text true
                 :-fx-font-style "italic"
                 :-fx-text-fill "-fx-text-base-color"}

                "#addon-detail-button-menu"
                {:-fx-alignment "center"}

                 ;; keep the ignore and delete buttons very separate from the others
                ".separator"
                {:-fx-padding "0 .25em"}

                ".table-view#notice-logger-table"
                {:-fx-pref-height "10pc"}

                 ;; hide column headers in notice-logger in addon-detail pane
                ".table-view#notice-logger-table .column-header-background"
                {:-fx-max-height 0
                 :-fx-pref-height 0
                 :-fx-min-height 0}

                "#notice-logger-nav"
                {:-fx-padding ".6em 0 .7em 0"
                 :-fx-alignment "bottom-right"
                 :-fx-pref-width 9999.0}

                ".table-view#key-vals .column-header .label"
                {:-fx-font-style "normal"} ;; column *values*, not the column *header* should be italic

                ".table-view#key-vals .key-column"
                {:-fx-alignment "center-right"
                 :-fx-padding "0 1em 0 0"
                 :-fx-font-style "italic"}

                ".table-view#key-vals .key-column.column-header .label"
                {:-fx-alignment "center-right"}

                ".table-view#key-vals .val-column.column-header .label"
                {:-fx-alignment "center-left"}

                "#addon-detail-big-buttons"
                {:-fx-padding "2em 0"
                 " .toggle-button" {:-fx-pref-width "100pc"
                                    :-fx-padding "1.7em 0"
                                    :-fx-background-radius "0"
                                    :-fx-font-size "1.1em"
                                    ":selected" {:-fx-background-color (colour :row-updateable)}}}} ;; ends #addon-detail-pane

               ;; ---
               }}))]

     ;; return a single map with all themes in it.
     ;; themes are separated by their top-level 'root' key.

     (into {} (for [[theme-key _] themes]
                (expand (generate-style theme-key)))))))

(def INSTALLED-TAB 0)
(def SEARCH-TAB 1)
(def LOG-TAB 2)

(def NUM-STATIC-TABS 3)

;;

(defn ^Stage get-window
  "returns the application `Window` object."
  []
  (first (Window/getWindows)))

(defn set-icon
  "adds the strongbox icon to the application window"
  []
  @(fx/on-fx-thread
    (.add (.getIcons (get-window))
          (javafx.scene.image.Image. (.openStream (clojure.java.io/resource "strongbox.png"))))))

(defn select
  [node-id]
  (-> (get-window) .getScene .getRoot (.lookupAll node-id)))

(defn find-installed-addons-table
  []
  (first (select "#installed-addons-table")))

(defn-spec clear-table-selected-items nil?
  "the context menu isn't being refreshed with new data when selected or installed addons change. 
  It should be, but isn't.
  A way around this is to clear the selected items in the table after a context menu action forcing a re-selection."
  []
  (.clearSelection (.getSelectionModel (find-installed-addons-table))))

(defn find-tabber
  []
  (first (select "#tabber")))

(defn-spec tab-index int?
  "returns the index of the currently selected tab"
  []
  (-> (find-tabber) .getSelectionModel .getSelectedIndex))

(defn-spec tab-list-tab-index int?
  "returns the index of the currently selected tab within `:tab-list`, which doesn't include the static tabs"
  []
  (- (tab-index) NUM-STATIC-TABS))

(defn extension-filter
  [x]
  ;; taken from here:
  ;; - https://github.com/cljfx/cljfx/pull/40/files
  (cond
    (instance? FileChooser$ExtensionFilter x) x
    (map? x) (FileChooser$ExtensionFilter. ^String (:description x) ^List (seq (:extensions x)))
    :else (throw (Exception. (format "cannot coerce '%s' to `FileChooser$ExtensionFilter`" (type x))))))

(defn file-chooser
  "prompt user to select a file, always returns a vector of absolute paths to files or `nil`.
  use `{:type :open}` to choose a single file.
  use `{:type :open-multi}` to choose multiple files.
  use `{:type :save}` to choose a single file to write/replace."
  [& [opt-map]]
  (let [opt-map (or opt-map {})
        open-type (get opt-map :type :open)
        window (get-window)
        chooser (doto (FileChooser.)
                  (.setTitle "Open File"))]
    (when-let [ext-filters (:filters opt-map)]
      (-> chooser .getExtensionFilters (.addAll (mapv extension-filter ext-filters))))
    (when-let [initial-dir (:initial-dir opt-map)]
      (.setInitialDirectory chooser (java.io.File. initial-dir)))
    (when-let [file-obj @(fx/on-fx-thread
                          (case open-type
                            :save (.showSaveDialog chooser window)
                            :open-multi (.showOpenMultipleDialog chooser window)
                            (.showOpenDialog chooser window)))]
      (mapv #(str (.getAbsolutePath %)) (if (instance? java.util.Collection file-obj)
                                          file-obj
                                          [file-obj])))))

(defn-spec dir-chooser (s/or :ok ::sp/dir, :noop nil?)
  "prompt user to select a directory, returning an absolute path to a directory or `nil`."
  []
  (let [chooser (doto (DirectoryChooser.)
                  (.setTitle "Select Directory"))]
    (when-let [^java.io.File dir @(fx/on-fx-thread
                                   (.showDialog chooser (get-window)))]
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
  [alert-type-key msg & [opt-map]]
  @(fx/on-fx-thread
    (let [alert-type-map {:warning Alert$AlertType/WARNING
                          :error Alert$AlertType/ERROR
                          :confirm Alert$AlertType/CONFIRMATION
                          :info Alert$AlertType/INFORMATION}
          alert-type (get alert-type-map alert-type-key)
          widget (doto (Alert. alert-type)
                   (.setTitle (:title opt-map))
                   (.setHeaderText (:header opt-map))
                   (.setContentText msg)
                   (.initOwner (get-window)))
          wait? (get opt-map :wait? true)]
      (when (:content opt-map)
        (.setContent (.getDialogPane widget) (:content opt-map)))
      (if wait?
        (.showAndWait widget)
        (.show widget)))))

(defn-spec confirm boolean?
  "displays a confirmation prompt with the given `heading` and `message`.
  `heading` may be `nil`.
  returns `true` if the 'ok' button is clicked, else `false`."
  [heading (s/nilable string?), message string?]
  (let [^java.util.Optional result (alert :confirm message {:title "confirm" :header heading})]
    (= (.get result) ButtonType/OK)))

(defn-spec confirm->action nil?
  "displays a confirmation prompt with the given `heading` and `message` and then calls given `callback` on success."
  [heading (s/nilable string?), message string?, callback fn?]
  (when (confirm heading message)
    (callback))
  nil)

;; https://github.com/cljfx/cljfx/blob/babc2f09e4827efb29f859a442a1658d82169a62/examples/e25_radio_buttons.clj
(defn radio-group
  [{:keys [options value on-action label-coercer container-id container-type]}]
  {:fx/type fx/ext-let-refs
   :refs {::toggle-group {:fx/type :toggle-group}}
   :desc {:fx/type (or container-type :h-box)
          :id (or container-id (utils/unique-id))
          :children (for [option options]
                      {:fx/type :radio-button
                       :toggle-group {:fx/type fx/ext-get-ref
                                      :ref ::toggle-group}
                       :selected (= option value)
                       :text ((or label-coercer str) option)
                       :on-action (partial on-action option)})}})

(defn-spec component-instance :javafx/node
  "given a cljfx component `description` map, returns a JavaFX instance of it."
  [description map?]
  (-> description fx/create-component fx/instance))

(defn parent-row
  "if given `row` has a parent, return that or just return given `row`."
  [row]
  (or (:-parent row) row))

(defn-spec child-row? boolean?
  "returns `true` if given `row` has a parent."
  [row map?]
  (-> row :-parent nil? not))

;;

(defn button
  "generates a simple button with a means to check to see if it should be disabled and an optional tooltip"
  [label on-action & [{:keys [disabled?, tooltip tooltip-delay, style-class id]}]]
  (let [btn (cond->
             {:fx/type :button
              :text label
              :on-action on-action}

              (boolean? disabled?)
              (merge {:disable disabled?})

              (some? id)
              (merge {:id id})

              (some? style-class)
              (merge {:style-class ["button" style-class]}))]

    (if (some? tooltip)
      {:fx/type fx.ext.node/with-tooltip-props
       :props {:tooltip {:fx/type :tooltip
                         :text tooltip
                         :show-delay (or tooltip-delay 200)}}
       :desc btn}

      btn)))

(defn menu-item
  [label handler & [opt-map]]
  (merge
   {:fx/type :menu-item
    :text label
    :mnemonic-parsing true
    :on-action handler}
   (when-let [key (:key opt-map)]
     {:accelerator key})
   (dissoc opt-map :key)))

(defn menu
  [label items & [opt-map]]
  (merge
   {:fx/type :menu
    :text label
    :mnemonic-parsing true
    :items items}
   (when-let [key (:key opt-map)]
     {:accelerator key})
   (dissoc opt-map :key)))

(defn async
  "execute given function and it's optional argument list asynchronously.
  exceptions are caught and printed to stdout but otherwise swallowed."
  ([f]
   (async f []))
  ([f arg-list]
   (future
     (try
       (apply f arg-list)
       (catch RuntimeException re
         ;;(error re "unexpected exception in thread"))))))
         (println "unexpected error in thread" re))))))

;; handlers

(defn-spec async-event-handler fn?
  "wraps `f`, calling it with any given `args` later"
  [f fn?]
  (fn [& args]
    (async f args)))

(defn-spec async-handler fn?
  "same as `async-event-handler` but just calls `f` and ignores any args.
  useful for calling functions asynchronously that don't accept an `event` object."
  [f fn?]
  (fn [& _]
    (async f)))

#_(defn-spec event-handler fn?
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

(def do-nothing donothing)

;; ---

(defn wow-dir-picker
  "prompts the user to select an addon dir. 
  if a valid addon directory is selected, calls `cli/set-addon-dir!`"
  []
  (when-let [dir (dir-chooser)]
    (when (fs/directory? dir)
      ;; unlike swing, it doesn't appear possible to select a non-directory with javafx (good)
      (cli/set-addon-dir! dir))))

(defn zip-file-picker
  "file chooser dialog for .zip files, opened to current addon directory."
  []
  (when-let [abs-path-list (file-chooser {:filters [{:description "ZIP files" :extensions ["*.zip"]}]
                                          :type :open-multi
                                          :initial-dir (core/selected-addon-dir)})]
    (doseq [{:keys [error-messages label]} (cli/install-addons-from-file-in-parallel abs-path-list)]
      (when-not (empty? error-messages)
        (let [msg (message-list (format "warnings/errors while installing \"%s\"" label) error-messages)]
          (alert :warning msg {:wait? false})))))
  nil)

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

    ;; set with a `with-redef` in main.clj or cloverage.clj
    core/*testing?* (swap! core/state assoc :gui-showing? false)

    ;; 2020-08-08: `ss/invoke-later` was keeping the old window around when running outside of repl.
    ;; `ss/invoke-soon` seems to fix that.
    ;;  - http://daveray.github.io/seesaw/seesaw.invoke-api.html
    ;; 2020-09-27: similar issue in javafx
    :else (Platform/runLater (fn []
                               (Platform/exit)
                               (System/exit 0)))))

(defn-spec switch-tab! nil?
  "switches the tab-pane to the tab at the given index"
  [tab-idx int?]
  (-> (find-tabber) .getSelectionModel (.select tab-idx))
  nil)

(defn-spec switch-tab-idx! nil?
  "switches the tab-pane to the tab at the given `idx` *on the JavaFX event thread*.
  the dynamic tabs seem to require a `runLater` unlike static tabs.
  multiple instances of strongbox will still interfere with this behaviour and the switching won't occur."
  [idx int?]
  (Platform/runLater
   (fn []
     (switch-tab! idx))))

(defn-spec switch-tab-latest! nil?
  "switches the tab pane to the right-most tab."
  []
  (switch-tab-idx! (-> (core/get-state :tab-list) count (+ NUM-STATIC-TABS) dec)))

(defn-spec switch-tab-event-handler fn?
  "returns an event handler that switches to the given `tab-idx` when called."
  [tab-idx int?]
  (fn [_]
    (switch-tab! tab-idx)))

(defn import-addon-handler
  "imports an addon by parsing a URL."
  []
  (let [addon-url (text-input "URL of addon:")]
    (when addon-url
      (let [error-messages
            (logging/buffered-log
             :warn
             (cli/import-addon addon-url))]

        (when-not (empty? error-messages)
          (let [msg (message-list "warnings/errors while importing addon:" error-messages)]
            (alert :warning msg {:wait? true})))

        (core/refresh)))))

(def json-files-extension-filters
  [{:description "JSON files" :extensions ["*.json"]}])

(defn import-addon-list-handler
  "prompts user with a file selection dialogue then imports a list of addons from the selected file."
  []
  (when-let [abs-path-list (file-chooser {:filters json-files-extension-filters})]
    (core/import-exported-file (first abs-path-list))
    (core/refresh))
  nil)

(defn export-addon-list-handler
  "prompts user with a file selection dialogue then writes the current directory of addons to the selected file."
  []
  (when-let [abs-path-list (file-chooser {:type :save
                                          :filters json-files-extension-filters})]
    (core/export-installed-addon-list-safely (first abs-path-list)))
  nil)

(defn export-user-catalogue-handler
  "prompts user with a file selection dialogue then writes the user catalogue to selected file."
  []
  (when-let [abs-path-list (file-chooser {:type :save
                                          :filters json-files-extension-filters})]
    (core/export-user-catalogue-addon-list-safely (first abs-path-list)))
  nil)

(defn-spec -about-strongbox-dialog map?
  "returns a description of the 'about' dialog contents.
  separated here for testing and test coverage."
  []
  {:fx/type :v-box
   :id "about-dialog"
   :children [{:fx/type :label
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
  "displays an informational dialog about strongbox."
  [_]
  (alert :info "" {:content (component-instance (-about-strongbox-dialog))})
  nil)

(defn delete-selected-confirmation-handler
  "prompts the user to confirm if they *really* want to delete those addons they just selected and clicked 'delete' on."
  []
  (when-let [selected (core/get-state :selected-addon-list)]
    (if (utils/any (mapv :ignore? selected))
      (alert :error "Selection contains ignored addons. Stop ignoring them and then delete.")
      (let [msg (message-list (format "Deleting %s:" (count selected)) (map :label selected))
            ^java.util.Optional result (alert :confirm msg)]
        (when (= (.get result) ButtonType/OK)
          (cli/delete-selected)))))
  nil)

(defn search-results-install-handler
  "switches to the 'installed' tab then installs each of the selected addons in parallel."
  [addon-list]
  (switch-tab! INSTALLED-TAB)
  (let [results-list (cli/install-many addon-list)]
    (doseq [{:keys [error-messages label]} results-list]
      (when-not (empty? error-messages)
        (let [msg (message-list (format "warnings/errors while installing \"%s\"" label) error-messages)]
          (alert :warning msg {:wait? false}))))
    (cli/half-refresh)))

;;

(defn remove-addon-dir
  []
  (when (confirm "Confirm" ;; soft touch here, it's not a big deal.
                 "You can add this directory back at any time.")
    (cli/remove-addon-dir!)))

;; column handling

(defn uber-button
  "returns a widget describing the current state of the given addon."
  [row]
  (let [row (parent-row row)
        [text, tooltip]
        (cond
          (:ignore? row) [(:ignored constants/glyph-map), "ignoring"]
          (:pinned-version row) [(:pinned constants/glyph-map) (str "pinned to " (:pinned-version row))]
          (core/unsteady? (:name row)) [(:unsteady constants/glyph-map) "in flux"]
          (cli/addon-has-errors? row) [(:errors constants/glyph-map) (format "%s error(s)" (cli/addon-num-errors row))]
          (cli/addon-has-warnings? row) [(:warnings constants/glyph-map) (format "%s warning(s)" (cli/addon-num-warnings row))]
          :else
          [(:tick constants/glyph-map) "no problems"])

        text (if (:update? row) (str text " " (:update constants/glyph-map)) text)
        tooltip (if (:update? row) (str tooltip ", updates available") tooltip)]

    {:fx/type fx.ext.node/with-tooltip-props
     :props {:tooltip {:fx/type :tooltip
                       :text tooltip
                       :show-delay 200}}
     :desc {:fx/type :button
            :text text
            :style-class ["button" "uber-button"]
            :on-action (fn [_]
                         (cli/add-addon-tab row)
                         (switch-tab-latest!))}}))

(defn addon-progress-bar
  "returns a progress-bar widget for the given `row`, using the jobs in the given `queue` filtered by `keyset`."
  [row queue keyset]
  (let [sub-queue (filter (joblib/by-keyset keyset) queue)
        progress (:progress (joblib/queue-info sub-queue))]
    {:fx/type :progress-bar
     :progress progress}))

(defn-spec href-to-hyperlink map?
  "returns a hyperlink description for the given `row` if a URL can be found, or an empty text description."
  [row (s/nilable (s/keys :opt-un [::sp/url]))]
  (let [url (:url row)
        fallback-url (:group-id row)
        label (:source row)]
    (if (or (and url label)
            (and fallback-url label))
      {:fx/type :hyperlink
       :on-action (handler #(utils/browse-to (or url fallback-url)))
       :text (str "↪ " label)}
      {:fx/type :text
       :text ""})))

(defn-spec addon-fs-link (s/or :hyperlink map?, :nothing nil?)
  "returns a hyperlink that opens a file browser to a path on the filesystem."
  [dirname (s/nilable ::sp/dirname)]
  (when dirname
    {:fx/type :hyperlink
     :on-action (handler #(utils/browse-to (format "%s/%s" (core/selected-addon-dir) dirname)))
     :text "↪ browse local files"}))

(defn gui-column-map
  "overrides and additional column information for the GUI. see `cli/column-map`."
  [queue]
  (let [-gui-column-map
        {:expand-group {:label "" :min-width 25 :pref-width 25 :max-width 25 :cell-value-factory (constantly "")}
         :browse-local {:min-width 135 :pref-width 143 :max-width 150
                        :cell-factory {:fx/cell-type :tree-table-cell
                                       :describe (fn [row]
                                                   {:graphic (or (addon-fs-link (:dirname row))
                                                                 {:fx/type :label
                                                                  :text (get row :dirname "")})})}
                        :cell-value-factory identity}
         :source {:min-width 130 :pref-width 135 :max-width 145
                  :cell-factory {:fx/cell-type :tree-table-cell
                                 :describe (fn [row]
                                             {:graphic (href-to-hyperlink row)})}
                  :cell-value-factory identity}
         :source-id {:min-width 60 :pref-width 150}
         :source-map-list {:cell-factory {:fx/cell-type :tree-table-cell
                                          :describe (fn [row]
                                                      (let [urls (for [source-map (:source-map-list row)
                                                                       :let [url (cli/addon-source-map-to-url row source-map)]
                                                                       :when (and url
                                                                                  (not (= (:source row) (:source source-map))))]
                                                                   (href-to-hyperlink (assoc source-map :url url)))
                                                            urls (utils/nilable (vec urls))]
                                                        (if urls
                                                          {:graphic {:fx/type :h-box
                                                                     :children urls}}
                                                          {:graphic {:fx/type :label
                                                                     :text ""}})))}
                           :cell-value-factory identity}

         :name {:min-width 100 :pref-width 300}
         :description {:min-width 150 :pref-width 450}
         :tag-list {:min-width 200 :pref-width 300 :style-class ["tag-button-column"]
                    :cell-value-factory identity
                    :cell-factory {:fx/cell-type :tree-table-cell
                                   :describe (fn [row]
                                               {:graphic {:fx/type :h-box
                                                          :children (mapv (fn [tag]
                                                                            (button (name tag)
                                                                                    (async-handler #(do (switch-tab! SEARCH-TAB)
                                                                                                        (cli/search-add-filter :tag tag)))
                                                                                    {:tooltip (name tag)}))
                                                                          (:tag-list row))}})}}
         :created-date {:min-width 90 :pref-width 110 :max-width 120
                        :cell-value-factory :created-date
                        :cell-factory {:fx/cell-type :tree-table-cell
                                       :describe (fn [dt]
                                                   ;; for some reason I'm getting the whole row here ... (:uber button column?)!
                                                   {:text (if-not (string? dt) "" (utils/format-dt dt))})}}
         :updated-date {:min-width 90 :pref-width 110 :max-width 120
                        :cell-value-factory :updated-date
                        :cell-factory {:fx/cell-type :tree-table-cell
                                       :describe (fn [dt]
                                                   {:text (if-not (string? dt) "" (utils/format-dt dt))})}}
         :installed-version {:min-width 100 :pref-width 175 :max-width 250 :style-class ["installed-column"]}
         :available-version {:min-width 100 :pref-width 175 :max-width 250 :style-class ["available-version-column"]}
         :combined-version {:min-width 100 :pref-width 175 :max-width 250 :style-class ["version-column"]}
         :game-version {:min-width 70 :pref-width 70 :max-width 100
                        :cell-factory {:fx/cell-type :tree-table-cell
                                       :describe (fn [text]
                                                   ;; for some reason I'm getting the whole row here
                                                   (let [text (if-not (string? text) "" text)]
                                                     {:graphic {:fx/type fx.ext.node/with-tooltip-props
                                                                :props {:tooltip {:fx/type :tooltip
                                                                                  :text (-> text utils/patch-name (or "?"))
                                                                                  ;; the tooltip will be long and intrusive, make delay longer than typical.
                                                                                  :show-delay 400}}
                                                                :desc {:fx/type :label
                                                                       :style-class ["table-cell"]
                                                                       :text text}}}))}}

         :uber-button {:min-width 80 :pref-width 80 :max-width 120 :style-class ["invisible-button-column"]
                       :cell-value-factory identity
                       :cell-factory {:fx/cell-type :tree-table-cell
                                      :describe (fn [row]
                                                  (if (or (not row)
                                                          (not (map? row)))
                                                    ;; for some reason I'm getting the contents of the :created-date column here
                                                    {:text ""}

                                                    (let [job-id (joblib/addon-id row)]
                                                      {:graphic (if (and (core/unsteady? (:name row))
                                                                         (joblib/has-job? queue job-id))
                                                                  (addon-progress-bar row queue job-id)
                                                                  (uber-button row))})))}}}

        ;; rename some UI column keys and then merge with the gui columns
        merge-ui-gui-columns (fn [[key val]]
                               [key (merge
                                     (clojure.set/rename-keys val {:label :text, :value-fn :cell-value-factory})
                                     (get -gui-column-map key))])
        column-map (->> cli/column-map (map merge-ui-gui-columns) (into {}))]
    column-map))

(defn-spec make-table-column map?
  "returns a description of a table column that lives within a table."
  [column-data :gui/column-data]
  (let [column-class (if-let [column-id (some utils/nilable [(:id column-data) (:text column-data)])]
                       (lower-case (str column-id "-column"))
                       "column")
        column-name (:text column-data)
        default-cvf (fn [row] (get row (keyword column-name)))
        value-factory (get column-data :cell-value-factory default-cvf)
        safe-value-factory (fn [row]
                             (or (value-factory row) ""))
        final-cvf {:cell-value-factory safe-value-factory}

        final-style {:style-class (into ["table-cell" column-class] (get column-data :style-class))}

        default {:fx/type :table-column
                 :min-width 80}]
    (merge default column-data final-cvf final-style)))

(defn-spec make-tree-table-column map?
  "like `make-table-column` but returns a `tree-table-column` type."
  [column-data :gui/column-data]
  (make-table-column (assoc column-data :fx/type :tree-table-column)))

;;

(def separator
  "horizontal rule to logically separate menu items."
  {:fx/type fx/ext-instance-factory
   :create #(javafx.scene.control.SeparatorMenuItem.)})

(defn-spec build-catalogue-menu (s/or :ok ::sp/list-of-maps, :no-catalogues nil?)
  "returns a list of radio button descriptions that can toggle through the available catalogues."
  [selected-catalogue :catalogue/name, catalogue-location-list :catalogue/location-list]
  (when catalogue-location-list
    (let [rb (fn [{:keys [label name]}]
               {:fx/type :radio-menu-item
                :text label
                :selected (= selected-catalogue name)
                :toggle-group {:fx/type fx/ext-get-ref
                               :ref ::catalogue-toggle-group}
                :on-action (async-handler #(cli/change-catalogue name))})]
      (mapv rb catalogue-location-list))))

(defn-spec build-theme-menu ::sp/list-of-maps
  "returns a list of radio button descriptions that can toggle through the available themes defined in `themes`."
  [selected-theme ::sp/gui-theme, theme-map map?]
  (let [rb (fn [theme-key]
             {:fx/type :radio-menu-item
              ;; "Light theme", "Dark green theme"
              :text (format "%s theme" (-> theme-key name (str-replace #"-" " ") capitalize))
              :selected (= selected-theme theme-key)
              :toggle-group {:fx/type fx/ext-get-ref
                             :ref ::theme-toggle-group}
              :on-action (fn [_]
                           (swap! core/state assoc-in [:cfg :gui-theme] theme-key)
                           ;; todo: this belongs in `cli.clj`
                           (core/save-settings!))})]
    (mapv rb (keys theme-map))))

(defn-spec build-addon-detail-menu ::sp/list-of-maps
  "returns a menu of dynamic tabs with a 'close all' button at the bottom."
  [tab-list :ui/tab-list]
  (let [addon-detail-menuitem
        (fn [idx tab]
          (let [tab-idx (+ idx NUM-STATIC-TABS)]
            (menu-item (:label tab) (async-handler #(switch-tab! tab-idx)))))
        close-all (menu-item "Close all" (async-handler cli/remove-all-tabs))]
    (concat (map-indexed addon-detail-menuitem tab-list)
            [separator close-all])))

(defn menu-item--num-zips-to-keep
  "returns a checkbox menuitem that affects the user preference `addon-zips-to-keep`."
  [{:keys [fx/context]}]
  (let [num-addon-zips-to-keep (fx/sub-val context get-in [:app-state :cfg :preferences :addon-zips-to-keep])
        selected? (not (nil? num-addon-zips-to-keep)) ;; `nil` is 'keep all zips', see `config.clj`
        ]
    {:fx/type :check-menu-item
     :text "Remove addon zip after installation"
     :selected selected?
     :on-action (fn [^Event ev]
                  (let [^javafx.scene.control.CheckMenuItem menu-item (.getSource ev)]
                    (cli/set-preference :addon-zips-to-keep (if (.isSelected menu-item)
                                                              0 nil))))}))

(defn-spec build-column-menu ::sp/list-of-maps
  "returns a list of columns that are 'selected' if present in `selected-column-list`."
  [selected-column-list :ui/column-list]
  (let [column-list (cli/sort-column-list (keys cli/column-map))
        queue nil
        gui-column-map (gui-column-map queue)
        toggle-column-menu-item
        (fn [column-id]
          (let [column (column-id gui-column-map)]
            {:fx/type :check-menu-item
             :text (or (:text column) (name column-id))
             :selected (utils/in? column-id selected-column-list)
             :on-action (async-event-handler #(cli/toggle-ui-column column-id (-> % .getTarget .isSelected)))}))

        preset-menu-item
        (fn [[name-kw column-list]]
          {:fx/type :menu-item
           :text (name name-kw)
           :on-action (async-handler #(cli/set-column-list column-list))})]

    (utils/items [(mapv preset-menu-item sp/column-preset-list)
                  separator
                  (mapv toggle-column-menu-item column-list)])))

(defn menu-bar
  "returns a description of the menu at the top of the application."
  [{:keys [fx/context]}]

  (let [addon-dir (fx/sub-val context get-in [:app-state :cfg :selected-addon-dir])
        no-addon-dir? (nil? addon-dir)
        selected-theme (fx/sub-val context get-in [:app-state :cfg :gui-theme])
        selected-columns (fx/sub-val context get-in [:app-state :cfg :preferences :ui-selected-columns])
        file-menu [(menu-item "Install addon from file" (async-handler zip-file-picker)
                              {:disable no-addon-dir?})
                   (menu-item "Import addon" (async-handler import-addon-handler)
                              {:disable no-addon-dir?})
                   separator
                   (menu-item "_New addon directory" (handler wow-dir-picker) {:key "Ctrl+N"})
                   (menu-item "_Browse addon directory" (async-handler #(utils/browse-to addon-dir))
                              {:disable no-addon-dir?, :key "Ctrl+B"})
                   (menu-item "Remove addon directory" (async-handler remove-addon-dir)
                              {:disable no-addon-dir?})
                   separator
                   (menu-item "_Update all" (async-handler cli/update-all)
                              {:key "Ctrl+U", :disable no-addon-dir?})
                   (menu-item "Re-install all" (async-handler cli/re-install-or-update)
                              {:disable no-addon-dir?})
                   separator
                   (menu-item "Import a list of addons" (async-handler import-addon-list-handler)
                              {:disable no-addon-dir?})
                   (menu-item "Export a list of addons" (async-handler export-addon-list-handler)
                              {:disable no-addon-dir?})
                   (menu-item "Export the user-catalogue" (async-handler export-user-catalogue-handler)
                              {:disable no-addon-dir?})
                   separator
                   (menu-item "E_xit" exit-handler {:key "Ctrl+Q"})]

        prefs-menu [{:fx/type menu-item--num-zips-to-keep}]

        view-menu (into
                   [(menu-item "Refresh" (async-handler cli/hard-refresh) {:key "F5"})
                    separator
                    (menu-item "_Installed" (switch-tab-event-handler INSTALLED-TAB) {:key "Ctrl+I"})
                    (menu-item "Searc_h" (switch-tab-event-handler SEARCH-TAB)
                               {:key "Ctrl+H" :disable no-addon-dir?})
                    (menu-item "_Log" (switch-tab-event-handler LOG-TAB) {:key "Ctrl+L"})
                    (let [tab-list (fx/sub-val context get-in [:app-state :tab-list])]
                      (menu "Ad_don detail" (build-addon-detail-menu tab-list)
                            {:disable (empty? tab-list)}))
                    separator
                    (menu "Columns" (build-column-menu selected-columns))
                    separator]
                   (build-theme-menu selected-theme themes))

        catalogue-menu (into (build-catalogue-menu
                              (fx/sub-val context get-in [:app-state :cfg :selected-catalogue])
                              (fx/sub-val context get-in [:app-state :cfg :catalogue-location-list]))
                             [separator
                              (menu-item "Refresh user catalogue" (async-handler cli/refresh-user-catalogue))])

        cache-menu [(menu-item "Clear http cache" (async-handler core/delete-http-cache!))
                    (menu-item "Clear addon zips" (async-handler core/delete-downloaded-addon-zips!)
                               {:disable no-addon-dir?})
                    (menu-item "Clear catalogues" (async-handler (juxt core/db-reload-catalogue core/delete-catalogue-files!)))
                    (menu-item "Clear log files" (async-handler core/delete-log-files!))
                    (menu-item "Clear all" (async-handler core/clear-all-temp-files!))
                    separator
                    (menu-item "Delete WowMatrix.dat files" (async-handler core/delete-wowmatrix-dat-files!)
                               {:disable no-addon-dir?})
                    (menu-item "Delete .wowman.json files" (async-handler (juxt core/delete-wowman-json-files! core/refresh))
                               {:disable no-addon-dir?})
                    (menu-item "Delete .strongbox.json files" (async-handler (juxt core/delete-strongbox-json-files! core/refresh))
                               {:disable no-addon-dir?})]

        help-menu [(menu-item "About strongbox" about-strongbox-dialog)]]

    {:fx/type fx/ext-let-refs
     :refs {::catalogue-toggle-group {:fx/type :toggle-group}
            ::theme-toggle-group {:fx/type :toggle-group}}
     :desc {:fx/type :menu-bar
            :id "main-menu"
            :menus [(menu "_File" file-menu)
                    (menu "_View" view-menu)
                    (menu "Catalogue" catalogue-menu)
                    (menu "_Preferences" prefs-menu)
                    (menu "Cache" cache-menu)
                    (menu "Help" help-menu)]}}))

(defn wow-dir-dropdown
  "returns a description for the list of addon directories."
  [{:keys [fx/context]}]
  (let [config (fx/sub-val context get-in [:app-state :cfg])
        selected-addon-dir (:selected-addon-dir config)
        addon-dir-map-list (get config :addon-dir-list [])]
    {:fx/type ext-recreate-on-key-changed
     :key (sort-by :addon-dir addon-dir-map-list)
     :desc {:fx/type :combo-box
            :id "addon-dir-dropdown"
            :value selected-addon-dir
            :on-value-changed (async-event-handler
                               (fn [new-addon-dir]
                                 (cli/set-addon-dir! new-addon-dir)))
            :items (mapv :addon-dir addon-dir-map-list)
            :disable (empty? addon-dir-map-list)}}))

(defn game-track-dropdown
  "returns a description for the list of available game tracks (retail, classic, etc) and how strictly they should be applied."
  [{:keys [fx/context]}]
  (let [selected-addon-dir (fx/sub-val context get-in [:app-state :cfg :selected-addon-dir])
        addon-dir-map (core/addon-dir-map selected-addon-dir)
        game-track (:game-track addon-dir-map)
        strict? (get addon-dir-map :strict? core/default-game-track-strictness)
        tooltip "restrict or relax the installation of addons for specific WoW versions"]

    {:fx/type :h-box
     :id "game-track-container"
     :children [{:fx/type :combo-box
                 :id "game-track-combo-box"
                 :min-width 150
                 :value (get sp/game-track-labels-map game-track)
                 :on-value-changed (async-event-handler
                                    (fn [new-game-track]
                                      ;; todo: push to cli or core
                                      (core/set-game-track! (get sp/game-track-labels-map-inv new-game-track) (:addon-dir addon-dir-map))
                                      (core/refresh)))
                 :items (mapv second sp/game-track-labels)
                 :disable (nil? selected-addon-dir)}

                {:fx/type fx.ext.node/with-tooltip-props
                 :props {:tooltip {:fx/type :tooltip
                                   :text tooltip
                                   :show-delay 200}}
                 :desc {:fx/type :check-box
                        :id "game-track-check-box"
                        :text "Strict"
                        :selected strict?
                        :disable (nil? (core/get-game-track-strictness))
                        :on-selected-changed (async-event-handler cli/set-game-track-strictness!)}}]}))

(defn installed-addons-menu-bar
  "returns a description of the installed-addons tab-pane menu."
  [{:keys [fx/context]}]
  (let [selected-addon-dir (fx/sub-val context get-in [:app-state :cfg :selected-addon-dir])]
    {:fx/type :h-box
     :padding 10
     :spacing 10
     :children [{:fx/type :button
                 :text "Update all"
                 :id "update-all-button"
                 :on-action (async-handler cli/update-all)
                 :disable (nil? selected-addon-dir)}
                {:fx/type wow-dir-dropdown}
                {:fx/type game-track-dropdown}
                {:fx/type :button
                 :text (str "Update Available: " (core/latest-strongbox-release))
                 :on-action (handler #(utils/browse-to "https://github.com/ogri-la/strongbox/releases"))
                 :visible (not (core/latest-strongbox-version?))
                 :managed (not (core/latest-strongbox-version?))}]}))

(defn-spec build-release-menu ::sp/list-of-maps
  "returns a list of `:menu-item` maps that will update the given `addon` with 
  the release data for a selected release in `release-list`."
  [addon :addon/expanded]
  (mapv (fn [release]
          (menu-item (or (:release-label release) (:version release))
                     (async-handler (juxt (partial cli/set-version addon release) clear-table-selected-items))))
        (:release-list addon)))

(defn-spec build-addon-source-menu (s/or :ok ::sp/list-of-maps, :no-sources nil?)
  "context sub-menu for addons that have multiple sources."
  [addon map?]
  (let [source-map-list (:source-map-list addon)]
    (when (> (count source-map-list) 1) ;; don't bother if the only source we have is the current one
      (mapv (fn [source-map]
              {:fx/type :check-menu-item
               :text (:source source-map)
               :selected (-> addon :source (= (:source source-map)))
               :on-action (async-handler (fn []
                                           (cli/switch-source addon source-map)
                                           (clear-table-selected-items)))})
            source-map-list))))

(defn singular-context-menu
  "returns a context menu description for when a single addon is selected."
  [{:keys [fx/context]}]
  (let [selected-addon (fx/sub-val context get-in [:app-state :selected-addon-list 0])
        pinned? (some? (:pinned-version selected-addon))
        release-list (:release-list selected-addon)
        releases-available? (and (not (empty? release-list))
                                 (not pinned?))
        ignored? (addon/ignored? selected-addon)
        child? (child-row? selected-addon)

        source-menu (build-addon-source-menu selected-addon)]

    {:fx/type :context-menu
     :items [(menu-item "Update" (async-handler (juxt cli/update-selected clear-table-selected-items))
                        {:disable (or child?
                                      (not (addon/updateable? selected-addon)))})

             (menu-item "Re-install" (async-handler (juxt cli/re-install-or-update clear-table-selected-items))
                        {:disable (or child?
                                      (not (addon/re-installable? selected-addon)))})
             separator
             (menu "Source" (or source-menu []) {:disable (nil? source-menu)})
             (menu-item "Find similar" (async-handler (fn []
                                                        (switch-tab! SEARCH-TAB)
                                                        (cli/search (:label selected-addon)))))
             separator
             (if pinned?
               (menu-item "Unpin release" (async-handler (juxt cli/unpin clear-table-selected-items))
                          {:disable (or child? ignored?)})
               (menu-item "Pin release" (async-handler (juxt cli/pin clear-table-selected-items))
                          {:disable (or child? ignored?)}))
             (if releases-available?
               (menu "Releases" (build-release-menu selected-addon))
               (menu "Releases" [] {:disable true}))
             separator
             (if ignored?
               (menu-item "Stop ignoring" (async-handler (juxt cli/clear-ignore-selected clear-table-selected-items)))
               (menu-item "Ignore" (async-handler (juxt cli/ignore-selected clear-table-selected-items))
                          {:disable child?}))
             separator
             (menu-item "Delete" (async-handler (juxt delete-selected-confirmation-handler clear-table-selected-items))
                        {:disable (or child? ignored?)})]}))

(defn multiple-context-menu
  "returns a context menu for when multiple addons are selected."
  [{:keys [fx/context]}]
  (let [selected-addon-list (fx/sub-val context get-in [:app-state :selected-addon-list])
        selected-addon-list (remove child-row? selected-addon-list)
        num-selected (count selected-addon-list)
        none-selected? (= num-selected 0)
        some-pinned? (->> selected-addon-list (map :pinned-version) (some some?) boolean)
        some-ignored? (->> selected-addon-list (filter :ignore?) (some some?) boolean)]
    {:fx/type :context-menu
     :items [(menu-item (str num-selected " addons selected") donothing {:disable true})
             separator
             (menu-item "Update" (async-handler (juxt cli/update-selected clear-table-selected-items))
                        {:disable none-selected?})
             (menu-item "Re-install" (async-handler (juxt cli/re-install-or-update clear-table-selected-items))
                        {:disable none-selected?})
             separator
             (menu "Source" [] {:disable true})
             (menu-item "Find similar" donothing {:disable true})
             separator
             (if some-pinned?
               (menu-item "Unpin release" (async-handler (juxt cli/unpin clear-table-selected-items)))
               (menu-item "Pin release" (async-handler (juxt cli/pin clear-table-selected-items))
                          {:disable none-selected?}))
             (menu "Releases" [] {:disable true})
             separator
             (if some-ignored?
               (menu-item "Stop ignoring" (async-handler (juxt cli/clear-ignore-selected clear-table-selected-items)))
               (menu-item "Ignore" (async-handler (juxt cli/ignore-selected clear-table-selected-items))
                          {:disable none-selected?}))
             separator
             (menu-item "Delete" (async-handler (juxt delete-selected-confirmation-handler clear-table-selected-items))
                        {:disable none-selected?})]}))

(defn installed-addons-table
  [{:keys [fx/context]}]
  (fx/sub-val context get-in [:app-state :unsteady-addon-list]) ;; re-render table when addons become unsteady
  (fx/sub-val context get-in [:app-state :log-lines]) ;; re-render rows when addons emit warnings or errors
  (let [queue (fx/sub-val context get-in [:app-state :job-queue])
        row-list (fx/sub-val context get-in [:app-state :installed-addon-list])
        selected (fx/sub-val context get-in [:app-state :selected-addon-list])
        selected-addon-dir (fx/sub-val context get-in [:app-state :cfg :selected-addon-dir])
        user-selected-column-list (cli/sort-column-list
                                   (fx/sub-val context get-in [:app-state :cfg :preferences :ui-selected-columns]))

        ;; can't be part of the column map because it's actually attached to the row.
        ;; this is just a spacer so the arrow always has room and isn't overlapped by another column's values.
        arrow-column {:fx/type :tree-table-column :cell-value-factory (constantly "")
                      :min-width 25 :max-width 25 :resizable false}

        selected-columns (or user-selected-column-list sp/default-column-list)
        column-list (utils/select-vals (gui-column-map queue) selected-columns)
        column-list (mapv make-tree-table-column column-list)
        column-list (if-not (empty? column-list) (into [arrow-column] column-list) [])

        ;; wraps the list of addons in a :`tree-item` component to model the parent->child relationship.
        row-list (mapv (fn [row]
                         (if (:group-addons row)
                           {:fx/type :tree-item
                            :value row
                            :children (mapv (fn [subrow]
                                              {:fx/type :tree-item :value (assoc subrow :-parent row)})
                                            (:group-addons row))}
                           {:fx/type :tree-item
                            :value row}))
                       row-list)]

    {:fx/type fx.ext.tree-table-view/with-selection-props
     :props {:selection-mode :multiple
             :on-selected-items-changed (fn [tree-item-list]
                                          (cli/select-addons* (mapv (fn [tree-item] (.getValue tree-item)) tree-item-list)))}
     :desc {:fx/type :tree-table-view
            :id "installed-addons-table"
            ;; replaces "tree-table-view" class and keeps all styling attached to table-view.
            :style-class ["table-view"]
            :show-root false
            :column-resize-policy javafx.scene.control.TreeTableView/CONSTRAINED_RESIZE_POLICY
            :pref-height 999.0
            :placeholder (cond
                           (nil? selected-addon-dir)
                           {:fx/type :v-box
                            :id "placeholder"
                            :children [{:fx/type :label
                                        :style-class ["big-welcome-text"]
                                        :text "STRONGBOX"}
                                       {:fx/type :label
                                        :style-class ["big-welcome-subtext"]
                                        ;; note! glyph is using FontAwesome map but the font-family is 'monospace'.
                                        ;; it could be java is looking for missing glyphs in other loaded fonts?
                                        :text (str "\"File\" " (:right-arrow constants/glyph-map) " \"New addon directory\"")}]}

                           (empty? column-list)
                           {:fx/type :v-box
                            :id "placeholder"
                            :children [{:fx/type :text
                                        :style-class ["table-placeholder-text"]
                                        :text "No columns selected!"}
                                       {:fx/type :text
                                        :text ""}
                                       (button "Reset columns to defaults" (handler cli/reset-ui-columns))]}

                           :else
                           {:fx/type :text
                            :style-class ["table-placeholder-text"]
                            :text "No addons found."})

            :row-factory {:fx/cell-type :tree-table-row
                          :describe (fn [row]
                                      {;; double clicking a tree-item will 'expand' it by default.
                                       ;; this prevents that from happening and opens the addon detail tab instead.
                                       :event-filter (fn [ev]
                                                       (when (and
                                                              (instance? MouseEvent ev)
                                                              (= (.getButton ev) MouseButton/PRIMARY)
                                                              (= (.getClickCount ev) 2))

                                                         ;; the 'expand' logic is triggered by the 'pressed' event which happens just before the 'clicked' event.
                                                         ;; this means the event must be captured with the `:event-filter` and then consumed so it never happens.
                                                         (when (= (.getEventType ev) MouseEvent/MOUSE_PRESSED)
                                                           (.consume ev))

                                                         (when (= (.getEventType ev) MouseEvent/MOUSE_CLICKED)
                                                           (cli/add-addon-tab row)
                                                           (switch-tab-latest!))))

                                       :style-class
                                       (remove nil?
                                               ["table-row-cell" "tree-table-row-cell" ;; `:style-class` *replaces* the list of classes
                                                (when (:update? row) "updateable")
                                                (when (:ignore? row) "ignored")
                                                (when (and row (core/unsteady? (:name row))) "unsteady")
                                                (when (and row (child-row? row)) "child-row")
                                                (cond
                                                  (and row (cli/addon-has-errors? row)) "errors"
                                                  (and row (cli/addon-has-warnings? row)) "warnings")])})}

            :columns column-list

            :context-menu (if (= 1 (count selected))
                            {:fx/type singular-context-menu}
                            {:fx/type multiple-context-menu})

            :root {:fx/type :tree-item :expanded true :children row-list}}}))

(defn notice-logger
  "a log widget that displays a list of log lines.
  used by itself as well as embedded into the addon detail page.
  pass it a `filter-fn` to remove entries in the `:log-lines` list."
  [{:keys [fx/context tab-idx filter-fn section-title]}]
  (let [filter-fn (or filter-fn identity)
        current-log-level (if tab-idx
                            (fx/sub-val context get-in [:app-state :tab-list tab-idx :log-level])
                            (fx/sub-val context get-in [:app-state :gui-log-level]))
        log-level-filter (fn [log-line]
                           (>= (-> log-line :level logging/level-map)
                               (logging/level-map current-log-level)))

        log-message-list (->> (fx/sub-val context get-in [:app-state :log-lines])
                              (filter filter-fn)
                              ;; nfi how to programmatically change column sort order
                              reverse)

        level-occurances (utils/count-occurances log-message-list :level)

        source-label (fn [row]
                       (or (some-> row :source :dirname)
                           (some-> row :source :name)
                           "app"))

        ;; hide 'source' column in notice-logger when embedded in addon-detail pane
        source-width (if section-title 0 150) ;; bit of a hack

        column-list [{:id "source" :text "source" :pref-width source-width :max-width source-width :min-width source-width :cell-value-factory source-label}
                     {:id "level" :text "level" :max-width 80 :cell-value-factory (comp name :level)}
                     {:id "time" :text "time" :max-width 100 :cell-value-factory :time}
                     {:id "message" :text "message" :pref-width 500
                      :cell-factory {:fx/cell-type :table-cell
                                     :describe (fn [row]
                                                 {:graphic {:fx/type :text
                                                            :style-class ["message-text"]
                                                            :text (get row :message "")}})}
                      :cell-value-factory identity}]

        log-level-list [:debug :info :warn :error] ;; :report] ;; 'reports' won't be interesting, no need to filter by them right now.
        log-level-list (if-not (contains? level-occurances :debug)
                         (rest log-level-list)
                         log-level-list)
        selected-log-level (fx/sub-val context get-in (if tab-idx
                                                        [:app-state :tab-list tab-idx :log-level]
                                                        [:app-state :gui-log-level]))
        log-level-changed-handler (fn [log-level _]
                                    (cli/change-notice-logger-level (keyword log-level) tab-idx))

        label-coercer (fn [log-level]
                        (let [num-occurances (level-occurances log-level)]
                          (format "%s (%s)" (name log-level) (or num-occurances 0))))

        log-message-list (filter log-level-filter log-message-list)]
    {:fx/type :border-pane
     :id "notice-logger"
     :top {:fx/type :h-box
           :children (utils/items
                      [(when section-title
                         {:fx/type :label
                          :style-class ["section-title"]
                          :text section-title})

                       {:fx/type radio-group
                        :options log-level-list
                        :value selected-log-level
                        :label-coercer label-coercer
                        :container-id "notice-logger-nav"
                        :on-action log-level-changed-handler}])}

     :center {:fx/type :table-view
              :id "notice-logger-table"
              :style-class ["table-view"]
              :placeholder {:fx/type :text
                            :style-class ["table-placeholder-text"]
                            :text ""}
              :selection-mode :multiple
              :row-factory {:fx/cell-type :table-row
                            :describe (fn [row]
                                        ;; we get a nil row when going up the severity level and some rows get filtered out ... weird.
                                        (when row
                                          {:style-class ["table-row-cell" (name (:level row))]
                                           :on-mouse-clicked
                                           (fn [^MouseEvent ev]
                                             ;; double click handler https://github.com/cljfx/cljfx/issues/118
                                             (when (and (= javafx.scene.input.MouseButton/PRIMARY (.getButton ev))
                                                        (= 2 (.getClickCount ev)))
                                               (if (or (contains? (:source row) :dirname)
                                                       (contains? (:source row) :source-id))
                                                 (do (cli/add-addon-tab (:source row))
                                                     (switch-tab-latest!))
                                                 (let [remaining-seconds (- 60 (-> (Calendar/getInstance) (.get Calendar/SECOND)))]
                                                   (if (> remaining-seconds 1)
                                                     (timbre/warn (format "self destruction in T-minus %s seconds" remaining-seconds))
                                                     (timbre/error "fah-wooosh ... BOOOOOO ... /oh the humanity/ ... OOOOOOHHHMMM"))))))}))}
              :column-resize-policy javafx.scene.control.TableView/CONSTRAINED_RESIZE_POLICY
              :columns (mapv make-table-column column-list)
              :items (or log-message-list [])}}))

(defn installed-addons-pane
  [_]
  {:fx/type :border-pane
   :id "installed-addons"
   :top {:fx/type installed-addons-menu-bar}
   :center {:fx/type installed-addons-table}})

(defn search-addons-table
  [{:keys [fx/context]}]
  (let [installed-addon-idx (mapv utils/source-map (fx/sub-val context get-in [:app-state, :installed-addon-list]))
        installed? #(utils/in? (utils/source-map %) installed-addon-idx) ;; todo: probably pretty slow compared to set membership?

        user-catalogue-idx (mapv utils/source-map (fx/sub-val context get-in [:app-state, :user-catalogue :addon-summary-list]))
        starred? (fn [a]
                   (utils/in? (utils/source-map a) user-catalogue-idx))

        search-state (fx/sub-val context get-in [:app-state :search])
        addon-list (cli/search-results search-state)

        ;; rare case when there are precisely $cap results, the next page is empty
        empty-next-page (and (= 0 (count addon-list))
                             (-> search-state :page (> 0)))

        tag-set (-> search-state :filter-by :tag)
        tag-selected (fn [tag]
                       (some #{tag} tag-set))

        tag-button (fn [tag]
                     (when-not (tag-selected tag)
                       (button (name tag)
                               (async-handler #(cli/search-add-filter :tag tag))
                               {:tooltip (name tag)})))

        column-list [{:text "" :style-class ["invisible-button-column" "star-column"]
                      :min-width 50 :pref-width 50 :max-width 50
                      :cell-value-factory identity
                      :cell-factory {:fx/cell-type :table-cell
                                     :describe (fn [addon-summary]
                                                 (let [starred (starred? addon-summary)
                                                       f (if starred cli/remove-summary-from-user-catalogue cli/add-summary-to-user-catalogue)]
                                                   {:graphic (button (:star constants/glyph-map)
                                                                     (async-handler (partial f addon-summary))
                                                                     {:style-class (if starred "starred" "unstarred")})}))}}

                     {:text "source" :min-width 130 :pref-width 135 :max-width 145
                      :cell-factory {:fx/cell-type :table-cell
                                     :describe (fn [row]
                                                 {:graphic (href-to-hyperlink row)})}
                      :cell-value-factory identity}
                     {:text "name" :min-width 150 :pref-width 250 :cell-value-factory (comp no-new-lines :label)}
                     {:text "description" :min-width 200 :cell-value-factory (comp no-new-lines :description)}
                     {:text "tags" :min-width 300 :style-class ["tag-button-column"]
                      :cell-value-factory identity
                      :cell-factory {:fx/cell-type :table-cell
                                     :describe (fn [row]
                                                 {:graphic {:fx/type :h-box
                                                            :children (remove nil? (map tag-button (:tag-list row)))}})}}
                     {:text "updated" :min-width 90 :pref-width 110 :max-width 120 :resizable false
                      :cell-value-factory :updated-date
                      :cell-factory {:fx/cell-type :table-cell
                                     :describe (fn [dt]
                                                 {:text (if-not (string? dt) "" (utils/format-dt dt))})}}

                     {:text "downloads" :min-width 120 :pref-width 120 :max-width 120 :resizable false
                      :cell-value-factory :download-count
                      :cell-factory {:fx/cell-type :table-cell
                                     :describe (fn [n]
                                                 (when n
                                                   {:text (format-number n)}))}}
                     {:text "" :style-class ["wide-button-column"] :min-width 120 :pref-width 120 :max-width 120 :resizable false
                      :cell-factory {:fx/cell-type :table-cell
                                     :describe (fn [addon]
                                                 {:graphic (button "install" (async-handler #(search-results-install-handler [addon]))
                                                                   {:disabled? (installed? addon)})})}
                      :cell-value-factory identity}]]

    {:fx/type fx.ext.table-view/with-selection-props
     :props {:selection-mode :multiple
             :on-selected-items-changed cli/select-addons-for-search!}
     :desc {:fx/type :table-view
            :id "search-addons-table"
            :placeholder {:fx/type :label
                          :style-class ["table-placeholder-text"]
                          :text (if empty-next-page
                                  constants/mascot
                                  "No search results.")}
            :row-factory {:fx/cell-type :table-row
                          :describe (fn [addon]
                                      {:on-mouse-clicked (fn [^MouseEvent ev]
                                                           ;; double click handler: https://github.com/cljfx/cljfx/issues/118
                                                           (when (and (= javafx.scene.input.MouseButton/PRIMARY (.getButton ev))
                                                                      (= 2 (.getClickCount ev)))
                                                             (cli/add-addon-tab addon)
                                                             (switch-tab-latest!)))
                                       :style-class ["table-row-cell"
                                                     (when (installed? addon)
                                                       "ignored")]})}
            :column-resize-policy javafx.scene.control.TableView/CONSTRAINED_RESIZE_POLICY
            :pref-height 999.0
            :columns (mapv make-table-column column-list)
            :items addon-list}}))

(defn search-addons-search-field
  [{:keys [fx/context]}]
  (let [search-state (fx/sub-val context get-in [:app-state, :search])
        ;;known-host-list (fx/sub-val context get-in [:app-state, :db-stats :known-host-list])
        known-host-list (core/get-state :db-stats :known-host-list)
        disable-host-selector? (= 1 (count known-host-list))

        tag-set (->> search-state :filter-by :tag)

        tag-button (fn [tag]
                     (button (name tag) (async-handler #(cli/search-rm-filter :tag tag))))

        tag-membership ["any of" "all of"]
        tag-any-all {:fx/type :combo-box
                     :id "tag-any-all-of"
                     :value (first tag-membership)
                     :on-value-changed (async-event-handler #(cli/search-add-filter :tag-membership %))
                     :items tag-membership}

        tag-buttons (mapv tag-button tag-set)
        tag-buttons (if-not (empty? tag-buttons)
                      (into [tag-any-all] tag-buttons)
                      [])

        num-selected (count (:selected-result-list search-state))

        row-1 {:fx/type :h-box
               :padding 10
               :spacing 10
               :children
               [{:fx/type :button
                 :id "search-install-button"
                 :text (if (> num-selected 0)
                         (format "install selected (%s)" num-selected)
                         "install selected")
                 :on-action (async-handler #(search-results-install-handler (core/get-state :search :selected-result-list)))
                 :disable (zero? num-selected)}

                {:fx/type :text-field
                 :id "search-text-field"
                 :prompt-text "search"
                 ;;:text (:term search-state) ;; don't do this, it can go spastic
                 :text (core/get-state :search :term) ;; this seems ok, probably has it's own drawbacks
                 :on-text-changed cli/search}

                (button (:star constants/glyph-map) (async-handler #(cli/search-toggle-filter :user-catalogue))
                        {:id "search-user-catalogue-button"
                         :style-class (if (-> search-state :filter-by :user-catalogue) "starred" "unstarred")})

                {:fx/type controlsfx.check-combo-box/lifecycle
                 :title "addon host"
                 :items known-host-list
                 :show-checked-count false
                 :on-checked-items-changed (fn [val]
                                             (cli/search-add-filter :source val))
                 :disable disable-host-selector?}

                ;;{:fx/type :button
                ;; :id "search-random-button"
                ;; :text "random"
                ;; :on-action (handler cli/random-search)}

                {:fx/type :h-box
                 :id "spacer"
                 :h-box/hgrow :ALWAYS}

                {:fx/type :button
                 :id "search-prev-button"
                 :text "previous"
                 :disable (not (cli/search-has-prev? search-state))
                 :on-action (handler cli/search-results-prev-page)}

                {:fx/type :button
                 :id "search-next-button"
                 :text "next"
                 :disable (not (cli/search-has-next? search-state))
                 :on-action (handler cli/search-results-next-page)}]}

        row-2 {:fx/type :h-box
               :id "search-selected-tag-bar"
               :children tag-buttons}]

    (if (empty? tag-set)
      row-1
      {:fx/type :v-box
       :children [row-1 row-2]})))

(defn search-addons-pane
  [_]
  {:fx/type :border-pane
   :id "search-addons"
   :top {:fx/type search-addons-search-field}
   :center {:fx/type search-addons-table}})

(defn addon-detail-button-menu
  "a row of buttons attached to available actions for the given addon"
  [{:keys [addon]}]
  {:fx/type :h-box
   :id "addon-detail-button-menu"
   :children [(if (addon/installed? addon)
                (button "Re-install" (async-handler #(cli/re-install-or-update [addon]))
                        {:disabled? (not (addon/re-installable? addon))
                         :tooltip (format "Re-install version %s" (:installed-version addon))})

                (button "Install" (async-handler #(cli/install-addon addon))
                        {:disabled? (addon/installed? addon)
                         :tooltip (format "Install %s version %s" (:name addon) (:version addon))}))

              (button "Update" (async-handler #(cli/update-selected [addon]))
                      {:disabled? (not (addon/updateable? addon))
                       :tooltip (format "Update to version %s" (:version addon))})

              (if (addon/pinned? addon)
                (button "Unpin" (async-handler #(cli/unpin [addon]))
                        {:disabled? (not (addon/unpinnable? addon))
                         :tooltip (format "Unpin from version %s" (:pinned-version addon))})

                (button "Pin" (async-handler #(cli/pin [addon]))
                        {:disabled? (not (addon/pinnable? addon))
                         :tooltip (format "Pin to version %s" (:installed-version addon))}))

              {:fx/type :separator
               :orientation :vertical}

              (if (addon/ignored? addon)
                (button "Unignore" (async-handler #(cli/clear-ignore-selected [addon] (core/selected-addon-dir))))
                (button "Ignore" (async-handler #(cli/ignore-selected [addon]))
                        {:tooltip "Prevent all changes"
                         :disabled? (not (addon/ignorable? addon))}))

              {:fx/type :separator
               :orientation :vertical}

              (button "Delete" (async-handler #(confirm->action "Confirm" "Are you sure you want to delete this addon?" (partial cli/delete-selected [addon])))
                      {:disabled? (not (addon/deletable? addon))
                       :tooltip "Permanently delete"})]})

(defn addon-detail-key-vals-widget
  "displays a two-column table of `key: val` fields for what we know about an addon."
  [{:keys [addon]}]
  (let [key-col (fn [keypair]
                  ;; shouldn't ever be nil but better safe than sorry
                  (-> keypair :key (or ":nil") str (subs 1)))
        column-list [{:text "key" :min-width 150 :pref-width 150 :max-width 200 :resizable false :cell-value-factory key-col}
                     {:text "val" :cell-value-factory :val}]

        blacklist [:group-addons :release-list :source-map-list]
        sanitised (apply dissoc addon blacklist)

        row-list (apply utils/csv-map [:key :val] (vec sanitised))
        row-list (sort-by :key row-list)]
    {:fx/type :border-pane
     :top {:fx/type :label
           :style-class ["section-title"]
           :text "raw data"}
     :center {:fx/type :table-view
              :id "key-vals"
              :style-class ["table-view"]
              :placeholder {:fx/type :text
                            :style-class ["table-placeholder-text"]
                            :text "(not installed)"}
              :column-resize-policy javafx.scene.control.TableView/CONSTRAINED_RESIZE_POLICY
              :columns (mapv make-table-column column-list)
              :items (or row-list [])}}))

(defn addon-detail-group-addons
  "displays a list of other addons that came grouped with this addon"
  [{:keys [addon]}]
  (let [opener #(component-instance (addon-fs-link (:dirname %)))
        column-list [{:text "" :style-class ["open-link-column"] :min-width 150 :pref-width 150 :max-width 150 :resizable false :cell-value-factory opener}
                     {:text "name" :cell-value-factory :dirname}]
        row-list (:group-addons addon)
        disabled? (empty? row-list)]
    {:fx/type :border-pane
     :top {:fx/type :label
           :style-class (if disabled? ["section-title", "disabled-text"] ["section-title"])
           :text "grouped addons"}
     :center {:fx/type :table-view
              :id "group-addons"
              :style-class ["table-view"]
              :placeholder {:fx/type :text
                            :style-class ["table-placeholder-text"]
                            :text "(not grouped)"}
              :column-resize-policy javafx.scene.control.TableView/CONSTRAINED_RESIZE_POLICY
              :columns (mapv make-table-column column-list)
              :items (or row-list [])
              :disable disabled?}}))

(defn addon-detail-release-widget
  "displays a list of available releases for the given addon"
  [{:keys [addon]}]
  (let [install-button (fn [release]
                         (component-instance
                          (button (if (= (:version release) (:installed-version addon))
                                    "re-install"
                                    "install")
                                  (async-handler #(cli/set-version addon release)))))
        column-list [{:text "" :style-class ["wide-button-column"] :min-width 120 :pref-width 120 :max-width 120 :resizable false :cell-value-factory install-button}
                     {:text "name" :cell-value-factory #(or (:release-label %) (:version %))}]
        row-list (or (:release-list addon) [])
        disabled? (not (addon/releases-visible? addon))]
    {:fx/type :border-pane
     :top {:fx/type :label
           :style-class (if disabled? ["section-title", "disabled-text"] ["section-title"])
           :text "releases"}
     :center {:fx/type :table-view
              :id "release-list"
              :style-class ["table-view"]
              :placeholder {:fx/type :text
                            :style-class ["table-placeholder-text"]
                            :text "(no releases)"}
              :column-resize-policy javafx.scene.control.TableView/CONSTRAINED_RESIZE_POLICY
              :columns (mapv make-table-column column-list)
              :items row-list
              :row-factory {:fx/cell-type :table-row
                            :describe (fn [row]
                                        {:style-class (utils/items
                                                       ["table-row-cell"
                                                        (when (= (:version row) (:installed-version addon))
                                                          "installed")
                                                        (when (= (:version row) (:version addon))
                                                          "updateable")])})}
              :disable disabled?}}))

(defn addon-detail-mutual-dependences-widget
  [{:keys [addon]}]
  (let [to-tree-rows (fn [row]
                       {:fx/type :tree-item
                        :expanded true
                        :value row})

        to-children (fn [a b]
                      (if (empty? a)
                        b
                        (assoc b :children [a])))

        install-dir (core/get-state :cfg :selected-addon-dir)

        children (if-not (:dirname addon)
                   ;; search result
                   []
                   ;; installed addon. read the raw nfo data and create a hierarchy of lowest->highest (see to-children)
                   (for [grouped-addon (addon/flatten-addon addon)
                         :let [mut-deps (nfo/mutual-dependencies install-dir (:dirname grouped-addon))
                               mut-deps-tree-items (map (fn [nfo]
                                                          (to-tree-rows (merge grouped-addon nfo))) mut-deps)]]
                     (reduce to-children {} mut-deps-tree-items)))

        root {:fx/type :tree-item
              :expanded true
              :children (remove empty? children)}

        ;; we need a depth > 1 to show anything meaningful
        depth (utils/find-depth root 0)
        disabled? (< depth 2)

        gui-column-map (gui-column-map nil)]

    {:fx/type :border-pane
     :top {:fx/type :v-box
           :children [{:fx/type :label
                       :style-class (if disabled? ["section-title", "disabled-text"] ["section-title"])
                       :text "mutual dependencies"}
                      {:fx/type :label
                       :style-class (if disabled? ["section-description" "disabled-text"] ["section-description"])
                       :text "addons that this addon (or any of it's grouped addons) overlaps with"}]}

     :center {:fx/type :tree-table-view
              :root root
              :style-class ["table-view"]
              :show-root false
              :column-resize-policy javafx.scene.control.TreeTableView/CONSTRAINED_RESIZE_POLICY
              :disable disabled?
              :placeholder {:fx/type :text
                            :style-class ["table-placeholder-text"]
                            :text "(no mutual dependencies)"}
              :row-factory {:fx/cell-type :tree-table-row
                            :describe (fn [row]
                                        {:style-class ["table-row-cell" "tree-table-row-cell"]})}
              :columns [{:fx/type :tree-table-column
                         :style-class ["table-view"]
                         :text ""
                         :cell-value-factory :arrow
                         :min-width (* depth 14)
                         :pref-width (* depth 14)
                         :max-width (* depth 14)}

                        (make-tree-table-column (:source gui-column-map))

                        (make-tree-table-column (:source-id gui-column-map))

                        {:fx/type :tree-table-column
                         :text "name"
                         :cell-value-factory :name}

                        {:fx/type :tree-table-column
                         :text "directory"
                         :cell-value-factory :dirname}

                        {:fx/type :tree-table-column
                         :text "version"
                         :cell-value-factory :installed-version}]}}))

(defn addon-detail-nav-widget
  [{:keys [tab-idx addon nav-key]}]
  (let [addon-detail-nav [[:releases+grouped-addons "releases + grouped-addons"]
                          [:mutual-dependencies "mutual dependencies"]
                          [:raw-data "raw data"]]
        toggle-button (fn [[key val]]
                        {:fx/type :toggle-button
                         :text val
                         :selected (= key nav-key)
                         :on-selected-changed (handler #(cli/change-addon-detail-nav key tab-idx))
                         :toggle-group {:fx/type fx/ext-get-ref
                                        :ref ::toggle-group}})]
    {:fx/type :v-box
     :min-width 460
     :pref-width 460
     :children
     (utils/items
      [{:fx/type :h-box
        :style-class ["subtitle"]
        :children (utils/items
                   [(when (:installed-version addon)
                      {:fx/type :label
                       :style-class ["installed-version"]
                       :text (:installed-version addon)})

                    (when (:update? addon)
                      {:fx/type :label
                       :style-class ["version"]
                       :text (format "%s available" (:version addon))})])}

       {:fx/type :h-box
        :style-class ["subtitle"]
        :children (utils/items
                   [;; if installed, path to addon directory, clicking it opens file browser
                    (addon-fs-link (:dirname addon))

                    ;; order is important, a hyperlink may not exist, can't have nav jumping around.
                    (href-to-hyperlink addon)])}

       (when-not (empty? (:description addon))
         {:fx/type :label
          :style-class ["description"]
          :wrap-text true
          :text (:description addon)})

       {:fx/type addon-detail-button-menu
        :addon addon}

       {:fx/type fx/ext-let-refs
        :refs {::toggle-group {:fx/type :toggle-group}}
        :desc {:fx/type :v-box
               :id "addon-detail-big-buttons"
               :children (mapv toggle-button addon-detail-nav)}}])}))

(defn addon-detail-centre-pane
  [{:keys [fx/context tab-idx addon]}]
  (let [selected-nav-key (fx/sub-val context get-in [:app-state :tab-list tab-idx :addon-detail-nav-key])
        nav {:fx/type addon-detail-nav-widget
             :addon addon
             :tab-idx tab-idx
             :nav-key selected-nav-key
             :grid-pane/column 1
             :grid-pane/hgrow :never
             :grid-pane/vgrow :always}

        key-vals {:fx/type addon-detail-key-vals-widget
                  :addon addon
                  :grid-pane/column 2
                  :grid-pane/hgrow :always
                  :grid-pane/vgrow :always}

        releases {:fx/type addon-detail-release-widget
                  :addon addon
                  :grid-pane/column 2
                  :grid-pane/hgrow :always
                  :grid-pane/vgrow :always}

        grouped {:fx/type addon-detail-group-addons
                 :addon addon
                 :grid-pane/column 3
                 :grid-pane/hgrow :always
                 :grid-pane/vgrow :always}

        mutuals {:fx/type addon-detail-mutual-dependences-widget
                 :addon addon
                 :grid-pane/column 2
                 :grid-pane/hgrow :always
                 :grid-pane/vgrow :always}]

    {:fx/type :grid-pane
     :children (case selected-nav-key
                 :releases+grouped-addons [nav releases grouped]
                 :mutual-dependencies [nav mutuals]
                 :raw-data [nav key-vals])}))

(defn addon-detail-pane
  "a place to elaborate on what we know about an addon as well as somewhere we can put lots of buttons and widgets."
  [{:keys [fx/context addon-id tab-idx]}]
  (let [installed-addons (fx/sub-val context get-in [:app-state :installed-addon-list])
        catalogue (fx/sub-val context get-in [:app-state :db]) ;; worst case is actually not so bad ...
        ;;addon-id-keys (keys addon-id) ;; [dirname] [source source-id], [source source-id dirname]

        -id-dirname (:dirname addon-id)
        -id-dirname? (some? -id-dirname)
        dirname-matcher (fn [addon]
                          (= -id-dirname (:dirname addon)))
        -id-source (select-keys addon-id [:source :source-id])
        -id-source? (not (empty? -id-source))
        source-matcher (fn [addon]
                         (= -id-source (select-keys addon [:source :source-id])))

        matcher (fn [addon]
                  (or (when -id-dirname?
                        (dirname-matcher addon))
                      (when -id-source?
                        (source-matcher addon))))

        ;; we may be given an installed addon, an ignored and unmatched addon, a catalogue entry so look in the installed
        ;; addon list first because it's smaller than the catalogue.
        addon (or (->> installed-addons (filter matcher) first)
                  (->> catalogue (filter matcher) first))

        ;; at this point addon may still be nil!
        ;; for example, an unmatched addon in the install dir is double clicked. we have a :dirname and that is all.
        ;; we can open the addon-detail pane but if we then delete the addon there is no longer any way to tie this addon detail pane
        ;; to addon data in the installed-addon-list (deleted) or the catalogue (no match).
        ;; we're forced to commit harikiri and close ourselves.
        ]
    (if (nil? addon)
      ;; this dodgy logic can be pushed back up the stack but we ultimately need to check for an addon and remove/exclude a tab if it exists.
      ;; deleting an addon doesn't affect the :tab-list, so we can't push this into #tabber, but perhaps we should re-check the open tabs when an addon is deleted?
      ;; todo: more thought required. for now it doesn't crash.
      (do (cli/remove-tab-at-idx tab-idx)
          {:fx/type :label :text "goodbye"})

      (let [notice-pane-filter (logging/log-line-filter-with-reports (core/selected-addon-dir) addon)]
        {:fx/type :border-pane
         :id "addon-detail-pane"
         :top {:fx/type :label
               :style-class ["title"]
               :text (:label addon)}

         :center {:fx/type addon-detail-centre-pane
                  :tab-idx tab-idx
                  :addon addon}

         :bottom {:fx/type notice-logger
                  :tab-idx tab-idx
                  :section-title "notices"
                  :filter-fn notice-pane-filter}}))))

(defn addon-detail-tab
  [{:keys [tab tab-idx]}]
  {:fx/type :tab
   :id (:tab-id tab)
   :text (:label tab)
   :closable (:closable? tab)
   :on-closed (fn [_]
                (cli/remove-tab-at-idx tab-idx)
                (switch-tab! INSTALLED-TAB))
   :content {:fx/type addon-detail-pane
             :tab-idx tab-idx
             :addon-id (:tab-data tab)}})

(defn tabber
  [{:keys [fx/context]}]
  (let [selected-addon-dir (fx/sub-val context get-in [:app-state :cfg :selected-addon-dir])
        dynamic-tab-list (fx/sub-val context get-in [:app-state :tab-list])
        static-tabs
        [{:fx/type :tab
          :text "installed"
          :id "installed-tab"
          :closable false
          :content {:fx/type installed-addons-pane}}
         {:fx/type :tab
          :text "search"
          :id "search-tab"
          :disable (nil? selected-addon-dir)
          :closable false
          ;; when the 'search' tab is selected, ensure the search field is focused so the user can just start typing
          :on-selection-changed (fn [^Event ev]
                                  (let [^javafx.scene.control.Tab tab (-> ev .getTarget)]
                                    (when (.isSelected tab)
                                      (let [^javafx.scene.control.TextField text-field
                                            (-> tab .getTabPane (.lookupAll "#search-text-field") first)]
                                        (Platform/runLater
                                         (fn []
                                           (-> text-field .requestFocus)))))))
          :content {:fx/type search-addons-pane}}
         {:fx/type :tab
          :text "log"
          :id "log-tab"
          :closable false
          :content {:fx/type notice-logger}}]

        dynamic-tabs (map-indexed (fn [idx tab] {:fx/type addon-detail-tab :tab tab :tab-idx idx}) dynamic-tab-list)]
    {:fx/type :tab-pane
     :id "tabber"
     :tab-closing-policy javafx.scene.control.TabPane$TabClosingPolicy/ALL_TABS
     :tabs (into static-tabs dynamic-tabs)}))

(defn split-pane-button
  "the little button in the bottom right of the screen that toggles the split gui feature"
  [{:keys [fx/context]}]
  (let [log-lines (fx/sub-val context get-in [:app-state :log-lines])
        log-lines (cli/log-entries-since-last-refresh log-lines)

        toggle (fx/sub-val context get-in [:app-state :gui-split-pane])

        ;; {:warn 1, :info 20}
        stats (utils/count-occurances log-lines :level)

        cmp (fn [kv1 kv2]
              (compare (get logging/level-map (first kv1))
                       (get logging/level-map (first kv2))))

        max-level (or (->> stats (sort cmp) last first)
                      logging/default-log-level)

        has-errors? (contains? stats :error)
        has-warnings? (contains? stats :warn)

        clf (partial clojure.pprint/cl-format nil)
        lbl (cond
              ;; '~:p' to pluralise using 's'
              ;; '~:*' to 'go back' a consumed argument
              ;; '~d' to format digit as a decimal (vs binary, hex, etc)
              has-errors? (clf "error~:p (~:*~d)" (:error stats)) ;; "error (1)", "errors (2)"
              has-warnings? (clf "warning~:p (~:*~d)" (:warn stats))
              :else "split")

        tooltip (if (or has-errors? has-warnings?)
                  "since last refresh"
                  "split log pane")]

    {:fx/type fx.ext.node/with-tooltip-props
     :props {:tooltip {:fx/type :tooltip
                       :text tooltip
                       :show-delay 400}}
     :desc {:fx/type :toggle-button
            :text lbl
            :selected (boolean toggle)
            :style-class (utils/items
                          ["toggle-button" (cond
                                             has-errors? "with-error"
                                             has-warnings? "with-warning")])
            :on-selected-changed (async-handler (fn []
                                                  (cli/toggle-split-pane)
                                                  (cli/change-notice-logger-level max-level)))}}))

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

        strings [(format catalogue-count-template (format-number a-count))
                 (if (= ia-count uia-count)
                   all-matching-template
                   (format num-matching-template uia-count ia-count))]]

    {:fx/type :h-box
     :id "status-bar"
     :children [{:fx/type :h-box
                 :id "status-bar-left"
                 :children [{:fx/type :text
                             :style-class ["text"]
                             :text (join " " strings)}]}
                {:fx/type :h-box
                 :id "status-bar-right"
                 :children [{:fx/type split-pane-button}]}]}))

;;

(defn app
  "returns a description of the javafx Stage, Scene and the 'root' node.
  the root node is the top-most node from which all others are descendents of."
  [{:keys [fx/context]}]
  (let [;; re-render gui whenever style state changes
        style (fx/sub-val context get :style)
        showing? (fx/sub-val context get-in [:app-state :gui-showing?])
        theme (fx/sub-val context get-in [:app-state :cfg :gui-theme])
        split-pane-on? (fx/sub-val context get-in [:app-state :gui-split-pane])]
    {:fx/type :stage
     :showing showing?
     :on-close-request exit-handler
     :title "strongbox"
     :width 1024
     :height 768
     :scene {:fx/type :scene
             :on-key-pressed (fn [^KeyEvent ev]
                               ;; ctrl-w
                               (when (and (.isControlDown ev)
                                          (= (.getCode ev) (KeyCode/W)))
                                 (let [;; when closing a tab, select the previous tab
                                       ;; UNLESS that previous tab is the last of the static tabs
                                       ;; then select the first of the static tabs
                                       prev-tab (dec (tab-index))
                                       prev-tab (if (= prev-tab (dec NUM-STATIC-TABS)) 0 prev-tab)]
                                   (cli/remove-tab-at-idx (tab-list-tab-index))
                                   (switch-tab! prev-tab)))
                               nil)
             :stylesheets [(::css/url style)]
             :root {:fx/type :border-pane
                    :id (name theme)
                    :top {:fx/type menu-bar}
                    :center (if split-pane-on?
                              {:fx/type :split-pane
                               :orientation :vertical
                               :divider-positions [0.6]
                               :items [{:fx/type tabber}
                                       {:fx/type notice-logger}]}
                              {:fx/type tabber})
                    :bottom {:fx/type status-bar}}}}))

(defn start
  []
  (timbre/info "starting gui")
  (let [;; the gui uses a copy of the application state because the state atom needs to be wrapped
        state-template {:app-state nil,
                        :style (style)}
        gui-state (atom (fx/create-context state-template)) ;; cache/lru-cache-factory))
        update-gui-state (fn [new-state]
                           (let [new-state (update-in new-state [:job-queue] deref)]
                             (swap! gui-state fx/swap-context assoc :app-state new-state)))
        _ (core/state-bind [] update-gui-state)

        update-job-state (fn [_ _ _ new-state]
                           (swap! gui-state fx/swap-context assoc-in [:app-state :job-queue] new-state))
        _ (add-watch (core/get-state :job-queue) :job update-job-state)

        ;; css watcher for live coding
        _ (doseq [rf [#'style #'major-theme-map #'sub-theme-map #'themes]
                  :let [key (str rf)]]
            (add-watch rf key (fn [_ _ _ _]
                                (swap! gui-state fx/swap-context assoc :style (style))))
            (core/add-cleanup-fn #(remove-watch rf key)))

        renderer (fx/create-renderer
                  :middleware (comp
                               fx/wrap-context-desc
                               (fx/wrap-map-desc (fn [_] {:fx/type app})))

                  ;; magic :(

                  :opts {:fx.opt/type->lifecycle #(or (fx/keyword->lifecycle %)
                                                      ;; For functions in `:fx/type` values, pass
                                                      ;; context from option map to these functions
                                                      (fx/fn->lifecycle-with-context %))})

        ;; don't do this, renderer has to be unmounted and the app closed before further state changes happen during cleanup.
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
        bump-search cli/bump-search]

    (swap! core/state assoc :gui-showing? true)
    (fx/mount-renderer gui-state renderer)

    ;; `refresh` the app but kill the `refresh` if app is closed before it finishes.
    ;; happens during testing and causes a few weird windows to hang around.
    ;; see `(run! (fn [_] (test :jfx)) (range 0 100))`
    (let [kick (future
                 ;; roughly follows `cli/start`

                 ;; logging to app state for use in the UI
                 (cli/init-ui-logger)
                 ;; asynchronous searching. as the user types, update the state with search results asynchronously
                 (cli/-init-search-listener)
                 (core/refresh)

                 (bump-search)

                 (set-icon) ;; 601ms :(
                 )]
      (core/add-cleanup-fn #(future-cancel kick)))

    ;; calling the `renderer` will re-render the GUI.
    ;; useful apparently, but not being used.
    ;;renderer
    ))

(defn stop
  []
  (timbre/info "stopping gui")
  (when-let [unmount-renderer (:disable-gui @core/state)]
    ;; only affects tests running from repl apparently
    (unmount-renderer))
  (exit-handler)
  nil)
