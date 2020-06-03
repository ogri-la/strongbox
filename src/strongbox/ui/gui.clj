(ns strongbox.ui.gui
  (:require
   [me.raynes.fs :as fs]
   [strongbox
    [core :as core :refer [get-state state-bind colours]]
    [logging :as logging]
    [specs :as sp]
    [utils :as utils :refer [items kw2str]]]
   [clojure.instant]
   [clojure.string :refer [lower-case starts-with? trim]]
   [slugify.core :refer [slugify]]
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [seesaw
    [invoke :as ssi]
    [chooser :as chooser]
    [mig :as mig]
    [dev :refer [show-options show-events]]
    [color]
    [cursor :refer [cursor]]
    [swingx :as x]
    [core :as ss]
    [font :refer [font]]
    [bind :as sb]
    [table :as sstbl]]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]))

;; "Call ... early in your program (like before any other Swing or Seesaw function is called) 
;; to get a more 'native' behavior"
;; - https://github.com/daveray/seesaw/wiki#native-look-and-feel
(ss/native!)

(defn-spec trigger-gui-restart nil?
  "call to dispose of the current gui and let `main.clj` restart it"
  []
  (swap! core/state assoc :gui-restart-flag true)
  nil)

(defn select-ui
  [& path]
  (if-let [ui (get-state :gui)]
    (ss/select ui (vec path))
    (throw (RuntimeException. "attempted to access an uninitialised GUI"))))

(defn-spec as-selector keyword?
  "converts a regular :keyword to a seesaw selector :#keyword"
  [kw keyword?]
  (->> kw name (str "#") keyword))

(def INSTALLED-TAB 0)
(def SEARCH-TAB 1)

(defn inspect
  [x]
  (show-events x)
  (show-options x))

(defn tab
  [title body]
  {:title title
   :content body})

(defn button
  [label onclick & [button-id]]
  (ss/button :id (or button-id
                     (-> label slugify (str "-btn") keyword)) ;; ":refresh-btn", ":update-all-btn"
             :text label
             :listen [:action onclick]))

(defn menu-item
  [label onclick]
  (ss/menu-item :text label
                :listen [:action onclick]))

(defn dynamic-menu-item
  "like a regular menu-item, but it's label changes"
  [initial-label & {:keys [watch callback onclick]}]
  (let [mi (menu-item initial-label onclick)
        actual-callback (fn [state]
                          (ss/text! mi (callback state)))]
    (state-bind watch actual-callback)
    mi))

(defn disabled-button
  [initial-label & {:keys [watch callback onclick]}]
  (let [button (ss/button :id (-> initial-label slugify (str "-btn") keyword) ;; ":refresh-btn", ":update-all-btn"
                          :text initial-label
                          :listen [:action onclick]
                          :enabled? false)
        actual-callback (fn [state]
                          (ss/config! button :enabled? (callback (get-in state watch))))]
    (state-bind watch actual-callback)
    button))

(defn donothing
  "the does-nothing event handler"
  [_]
  nil)

(defn handler
  "returns a function that calls each given argument function sequentially, discards result, returns nil"
  [& fn-list]
  (fn [_]
    (doseq [f fn-list]
      (f))))

(defn async
  [f]
  (future
    (try
      (f)
      (catch RuntimeException re
        (error re "unhandled exception in thread")))))

(defn async-handler
  "like `handler`, but each function is executed on a separate thread instead of sequentially"
  [& fl]
  (fn [_]
    (doseq [f fl]
      (async f))))

(defn-spec browser (s/or :ok fn? :error nil?)
  "given the name of a binary, returns a function that will open a given URL in a browser or nil if 
  the binary cannot be found."
  [bin string?]
  (try
    (when (->> bin (clojure.java.shell/sh "which") :exit (= 0))
      (fn [url]
        (info (format "opening URL with %s: %s" bin url))
        (clojure.java.shell/sh bin url)))
    (catch Exception uncaught-exception
      (error uncaught-exception "failed to call `which`"))))

(defn-spec java-browser (s/or :ok fn? :error nil?)
  "returns a function that will open a given URL in a browser, or nil if 
  current Desktop is not supported"
  []
  (when (and (java.awt.Desktop/isDesktopSupported)
             (.isSupported (java.awt.Desktop/getDesktop) java.awt.Desktop$Action/BROWSE))
    (fn [url]
      (info "opening URL:" url)
      (.browse (java.awt.Desktop/getDesktop) (java.net.URL. url)))))

(defn-spec find-browser fn?
  "returns a function that attempts to open a given URL in a browser.
  Prints an error message to console if URL cannot be opened."
  []
  (let [xdg-open #(browser "xdg-open")
        gnome-open #(browser "gnome-open")
        kde-open #(browser "kde-open")
        fail (constantly #(error "failed to find a program to open URL:" (str %)))]
    (loop [lst [java-browser xdg-open gnome-open kde-open fail]]
      (if-let [browser-fn ((first lst))]
        browser-fn
        (recur (rest lst))))))

(defn-spec browse-to nil?
  "given a URL, open a browser window with it"
  [url ::sp/url]
  (future-call #((find-browser) url))
  nil)

(defn selected-rows-handler
  "calls given `f` with last event when selection has stopped adjusting"
  [f]
  (fn [ev]
    ;; https://docs.oracle.com/javase/7/docs/api/javax/swing/event/ListSelectionEvent.html#getValueIsAdjusting()
    (when-not (.getValueIsAdjusting ev)
      (f ev))))

(defn insert-all
  [grid rows]
  (ss/invoke-now
   (sstbl/clear! grid)
   (debug "grid" (ss/id-of grid) "num rows" (count rows) "num grid rows" (sstbl/row-count grid))
   (dorun (map-indexed (fn [idx row]
                         (debug "inserting row" (:name row) "at idx" idx)
                         (sstbl/insert-at! grid idx row)) rows))
   (.packAll grid)))

(defn insert-one
  [grid row]
  (ss/invoke-later
   (sstbl/insert-at! grid 0 row)))

(defn-spec select-one nil?
  [grid #(instance? javax.swing.JTable %), idx int?]
  (debug "selecting row" idx)
  (try
    (.setSelectionInterval (.getSelectionModel grid) idx idx)
    (catch java.lang.IllegalArgumentException iae
      (warn (format "failed to select row %s: %s" idx (.getMessage iae))))))

(comment "unused, for now"
         (defn select-many
           [grid & indicies]
           (ss/invoke-later
            (let [s-model (.getSelectionModel grid)]
              (select-one grid (first indicies))
              (doseq [idx (rest indicies)]
                (.addSelectionInterval s-model idx idx))))))

(defn find-column-by-label
  "returns the index of the column for the given label. remember, column positions can change!"
  [grid label]
  (let [mdl (.getModel grid)
        matches? (fn [idx]
                   (when (= (.getColumnName mdl idx) label)
                     idx))
        columns (range 0 (.getColumnCount mdl))]
    (first (remove nil? (map matches? columns)))))

(defn find-row-by-value
  "returns the first set of coordinates [row col] for a match of `row-value` in column with given `column-label` or `nil` if not found."
  [grid column-label row-value]
  (let [col-idx (find-column-by-label grid column-label)
        matches? (fn [row-idx]
                   (when (= (.getValueAt (.getModel grid) row-idx col-idx) row-value)
                     [row-idx col-idx]))
        rows (range 0 (.getRowCount grid))]
    (first (remove nil? (map matches? rows)))))

;;

(defn switch-tab [idx] (.setSelectedIndex (select-ui :#tabber) idx))
(defn switch-tab-handler [idx] (fn [_] (switch-tab idx)))

;;

(defn-spec tbl-selected-rows (s/or :ok ::sp/list-of-maps, :nothing-selected nil?)
  "returns a list of rows that are currently selected for given table"
  [tbl keyword?]
  (let [tbl (select-ui tbl)]
    (sstbl/value-at tbl (ss/selection tbl {:multi? true}))))

(defn-spec installed-addons-selection-handler nil?
  [_ ::sp/gui-event]
  (let [selected-rows (tbl-selected-rows :#tbl-installed-addons)]
    (debug (count selected-rows) "selected, " (count (filter :update? selected-rows)) "updatable")
    (swap! core/state assoc :selected-installed selected-rows))
  nil)

(defn-spec search-results-selection-handler nil?
  [_ ::sp/gui-event]
  (let [selected-rows (tbl-selected-rows :#tbl-search-addons)]
    (debug (count selected-rows) "selected")
    (swap! core/state assoc :selected-search selected-rows))
  nil)

(defn-spec search-results-install-handler nil?
  []
  ;; this: switches tab, then, for each addon selected, expands summary, installs addon, calls load-installed-addons and finally refreshes;
  ;; there will be a plodding step-wise update which is better than a blank screen and apparent hang
  ;;(core/-install-update-these (map curseforge/expand-summary (get-state :selected-search))) ;; original approach. efficient but no feedback for user
  (switch-tab INSTALLED-TAB)
  (doseq [selected (get-state :selected-search)]
    (some-> selected core/expand-summary-wrapper vector core/-install-update-these)
    (core/load-installed-addons))
  (ss/selection! (select-ui :#tbl-search-addons) nil) ;; deselect rows in search table
  (core/refresh))

(defn-spec remove-selected-handler nil?
  []
  (when-let [selected (get-state :selected-installed)]
    (let [header [[(format "Deleting %s:" (count selected)) ""]]
          labels (mapv (fn [row] [(str " - " (-> row :name)) ""]) selected)

          content (into header labels)
          content (interleave content (repeat [:separator "growx, wrap"]))

          dialog (ss/dialog :parent (select-ui :#root)
                            :content (mig/mig-panel :items content)
                            :resizable? false
                            :type :warning
                            :option-type :ok-cancel
                            :default-option :no
                            :success-fn (async-handler core/remove-selected))]
      (-> dialog ss/pack! ss/show!)
      nil)))

(defn about-strongbox-dialog
  []
  (let [content [[(ss/label :text "strongbox" :font (font :size 18 :style #{:bold})) "center"]
                 [(format "version %s" (core/strongbox-version)) "center"]
                 ["" ""]
                 (when-not (core/latest-strongbox-version?)
                   [(format "version %s is now available to download!" (core/latest-strongbox-release)) "center"])
                 [(x/hyperlink :text "github" :uri "https://github.com/ogri-la/strongbox") "center"]
                 ["AGPL v3", "center"]]
        content (remove nil? content)
        content (interleave content (repeat [:separator "growx, wrap"]))

        dialog (ss/dialog :parent (select-ui :#root)
                          :content (mig/mig-panel :items content)
                          :type :info
                          :resizable? false)]
    (-> dialog ss/pack! ss/show!)
    nil))

(defn configure-app-panel
  []
  (let [picker (fn []
                 (when-let [dir (chooser/choose-file (select-ui :#root)
                                                     ;; ':open' forces a better dialog type in mac for opening directories
                                                     :type :open
                                                     :selection-mode :dirs-only)]
                   (if (fs/directory? dir)
                     (do
                       (core/set-addon-dir! (str dir))
                       (core/save-settings))
                     (ss/alert (format "Directory doesn't exist: %s" (str dir))))))
        ;; important! release the event thread using async-handler else updates during process won't be shown until complete
        refresh-button (button "Refresh" (async-handler core/refresh))
        update-all-button (button "Update all" (async-handler core/install-update-all))

        wow-dir-button (button "WoW directory" (async-handler picker))

        wow-dir-dropdown (ss/combobox :model (core/available-addon-dirs)
                                      :selected-item (core/selected-addon-dir))

        wow-game-track (ss/combobox :model (mapv kw2str core/game-tracks)
                                    :selected-item (kw2str (core/get-game-track)))

        _ (ss/listen wow-dir-dropdown :selection
                     (async-handler ;; execute elsewhere
                      (fn []
                        ;; called when a different addon dir is selected
                        (let [old-addon-dir (core/selected-addon-dir)
                              new-addon-dir (ss/selection wow-dir-dropdown)]
                          (when-not (= new-addon-dir old-addon-dir)
                            (debug "addon-dir selection changed to" new-addon-dir)
                            ;; positioned here so the dropdown change is shown immediately
                            (ss/invoke-later
                             (ss/selection! wow-game-track (-> new-addon-dir core/addon-dir-map :game-track kw2str))))

                          (core/set-addon-dir! new-addon-dir)
                          (core/save-settings)))))

        _ (state-bind [:cfg :selected-addon-dir]
                      (fn [state]
                        ;; called when the selected addon directory changes (like via `core/set-addon-dir!`)
                        (let [new-addon-dir (get-in state [:cfg :selected-addon-dir]) ;; use the given `state`
                              game-track (-> new-addon-dir core/addon-dir-map :game-track kw2str)
                              selected-addon-dir (ss/selection wow-dir-dropdown)]
                          (when-not (= selected-addon-dir new-addon-dir)
                            (debug (format ":selected-addon-dir changed from '%s' to '%s'" (core/selected-addon-dir) new-addon-dir))
                            (ss/invoke-later
                             ;; it's possible the addon-dir-list data has changed as well, so update the dropdown model
                             ;; adding a second listener for :addon-dir-list risks two updates being performed
                             (ss/config! wow-dir-dropdown :model (core/available-addon-dirs))
                             (ss/selection! wow-dir-dropdown new-addon-dir)
                             (ss/selection! wow-game-track game-track))))))

        _ (ss/listen wow-game-track :selection
                     (fn [ev]
                       ;; called when a different game track is selected
                       (let [new-game-track (ss/selection wow-game-track)
                             old-game-track (-> (core/addon-dir-map) :game-track kw2str)]
                         (when-not (= new-game-track old-game-track)
                           (debug (format "selected game track changed from %s to %s" old-game-track new-game-track))
                           (ss/invoke-later
                            (core/set-game-track! (keyword new-game-track)) ;; this affects [:cfg :addon-dir-list]
                            ;; will save settings
                            (core/refresh))))))

        items [[refresh-button]
               [update-all-button]
               [wow-dir-button]
               [wow-dir-dropdown "wmax 200"]
               [wow-game-track]]

        update-clicker (button (str "Update Available: " (core/latest-strongbox-release))
                               (handler #(browse-to "https://github.com/ogri-la/strongbox/releases"))
                               :update-available-btn)

        items (if-not (core/latest-strongbox-version?)
                (conj items [update-clicker])
                items)]

    (mig/mig-panel
     :constraints ["insets 0"]
     :items items)))

(defn installed-addons-panel-column-widths
  "this sucks"
  [grid]
  (let [default-min-width 80 ;; solid default for all columns
        min-width-map {"WoW" 45
                       "source" 120} ;; these can be a little smaller
        max-width-map {"installed" 200
                       "available" 200
                       "updated" 100
                       "downloads" 100
                       "source" 140}
        pre-width-map {"WoW" 50
                       "updated" 100}] ;; we would like these a little larger, if possible
    (doseq [column (.getColumns grid)]
      (.setMinWidth column (get min-width-map (.getTitle column) default-min-width))
      (when-let [max-width (get max-width-map (.getTitle column))]
        (.setMaxWidth column max-width))
      (when-let [pre-width (get pre-width-map (.getTitle column))]
        (.setPreferredWidth column pre-width)))))

(defn hide-columns
  [grid hidden-column-list]
  (doseq [column hidden-column-list]
    (.setVisible (.getColumnExt grid (-> column name str)) false)))

(defn add-highlighter
  "target a selection of rows and colour their backgrounds differently"
  [grid pred-fn bg-colour & [fg-colour]]
  (let [predicate (proxy [org.jdesktop.swingx.decorator.HighlightPredicate] []
                    (isHighlighted [renderer adapter]
                      (pred-fn adapter)))

        highlighter (org.jdesktop.swingx.decorator.ColorHighlighter.
                     predicate
                     (when bg-colour (seesaw.color/color bg-colour))
                     (when fg-colour (seesaw.color/color fg-colour)))]
    (.addHighlighter grid highlighter)
    nil))

(defn add-cell-renderer
  "target a cell and render it's contents differently"
  [grid column-name render-fn]
  ;; todo: change column-idx to column title. idx is too brittle
  (let [column-idx (find-column-by-label grid column-name)
        cell-renderer (proxy [javax.swing.table.DefaultTableCellRenderer] []
                        (setValue [colval]
                          ;; nil values can make cells render strangely, so ensure an empty string if nil returned
                          (proxy-super setValue (or (render-fn colval) ""))))]
    (.setCellRenderer (.getColumn (.getColumnModel grid) column-idx) cell-renderer)

    ;; lawdy dawdy this is awful. 
    (when (= column-name "source")
      (.setHorizontalAlignment cell-renderer javax.swing.SwingConstants/LEFT))

    nil))

(defn installed-addons-popup-menu
  []
  (let [no-label ""
        popup-menu-items [(dynamic-menu-item
                           no-label
                           :watch [:selected-installed]
                           :callback (fn [state]
                                       (let [selected-rows (tbl-selected-rows :#tbl-installed-addons)]
                                         (format "%s selected, %s updateable"
                                                 (count selected-rows)
                                                 (count (filter :update? selected-rows)))))
                           :onclick donothing)
                          (ss/separator)
                          (menu-item "Update" (async-handler core/install-update-selected))
                          (menu-item "Re-install" (async-handler core/re-install-selected))
                          (ss/separator)
                          (menu-item "Delete" (async-handler remove-selected-handler))]]
    (ss/popup :items popup-menu-items)))

(defn installed-addons-go-links
  [grid]
  (let [gocol? #(= (.getColumnName grid %) "source")

        cell-val-for-event (fn [e]
                             (let [row (.rowAtPoint grid (.getPoint e))
                                   col (.columnAtPoint grid (.getPoint e))]
                               (when (and (> col -1) (> row -1))
                                 [row col (.getValueAt grid row col)])))

        go-link-clicked (fn [e]
                          (when-let [triple (cell-val-for-event e)]
                            (let [[row col val] triple]
                              (if (and (gocol? col) val)
                                (browse-to val)))))

        hand-cursor-on-hover (fn [e]
                               (when-let [triple (cell-val-for-event e)]
                                 (let [[row col val] triple]
                                   (if (and (gocol? col) val)
                                     (.setCursor grid (cursor :hand))
                                     (.setCursor grid (cursor :default))))))

        hyperlink-colour (-> :hyperlink colours name)
        url-template (str "<html><font color='" hyperlink-colour "'>&nbsp;↪ %s</font></html>")
        url-renderer (fn [x]
                       (when x
                         (let [url (java.net.URL. x)
                               label (case (.getHost url)
                                       "www.curseforge.com" "curseforge"
                                       "www.wowinterface.com" "wowinterface"
                                       "github.com" "github"
                                       "www.tukui.org" "tukui"
                                       "???")]
                           (format url-template label))))]

    (ss/listen grid :mouse-motion hand-cursor-on-hover)
    (ss/listen grid :mouse-clicked go-link-clicked)
    (add-cell-renderer grid "source" url-renderer)

    nil))

(defn installed-addons-panel
  []
  (let [;; always visible when debugging and always available from the column menu
        hidden-by-default-cols [:addon-id :group-id :primary? :update? :matched? :ignore? :tags :downloads :updated :source-id :track]
        tblmdl (sstbl/table-model :columns [{:key :name, :text "addon-id"}
                                            :group-id
                                            :primary?
                                            :update?
                                            :matched?
                                            :ignore?
                                            {:key :installed-game-track, :text "track"}
                                            {:key :url, :text "source"}
                                            :source-id
                                            {:key :label, :text "name"}
                                            :description
                                            {:key :installed-version, :text "installed"}
                                            {:key :version, :text "available"}
                                            {:key :download-count, :text "downloads" :class Integer}
                                            {:key :updated-date, :text "updated"}
                                            {:key :interface-version, :text "WoW"}
                                            {:key :tag-list, :text "tags"}]
                                  :rows [])

        grid (x/table-x :id :tbl-installed-addons
                        :model tblmdl
                        :highlighters [((x/hl-color :background (colours :installed/hovering)) :rollover-row)]
                        :popup (installed-addons-popup-menu))

        addon-unmatched? (fn [adapter]
                           (nil? (.getValue adapter (find-column-by-label grid "matched?"))))

        addon-ignored? (fn [adapter]
                         (->> (find-column-by-label grid "ignore?") (.getValue adapter) true?))

        addon-needs-update? (fn [adapter]
                              (if (addon-ignored? adapter)
                                false
                                (->> (find-column-by-label grid "update?") (.getValue adapter) true?)))

        date-renderer #(when % (-> % clojure.instant/read-instant-date (utils/fmt-date "yyyy-MM-dd")))
        iface-version-renderer #(when % (-> % str utils/interface-version-to-game-version))

        ;; refreshes installed addons table
        update-rows-fn (fn [state]
                         (debug "refreshing installed addons table")
                         ;; don't use core/get-state inside listeners!
                         (insert-all grid (:installed-addon-list state)))

        watch-for-unsteady-addons (fn [state]
                                    (let [unsteady (:unsteady-addons state)]
                                      (when-not (empty? unsteady)
                                        (debug "unsteady addons:" unsteady)
                                        (ss/invoke-now
                                         (if-let [idx (first (find-row-by-value grid "addon-id" (first unsteady)))]
                                           (select-one grid idx) ;; if the rows are hidden you won't be able to see it
                                           ;; downgrading to 'debug' for now as I only see it when installing new addons
                                           ;; so if the addon is installed and we're seeing it, it's a BUG
                                           (debug "failed to find value" (first unsteady) "in column 'addon-id'."))))))]

    ;; this is before render and before hiding and before user has had a chance to move or resize columns
    (installed-addons-panel-column-widths grid)

    (installed-addons-go-links grid)

    (when (colours :installed/unmatched)
      (add-highlighter grid addon-unmatched? (colours :installed/unmatched)))

    (add-highlighter grid addon-ignored? (colours :installed/ignored-bg) (colours :installed/ignored-fg))
    (add-highlighter grid addon-needs-update? (colours :installed/needs-updating))
    (add-cell-renderer grid "updated" date-renderer)
    (add-cell-renderer grid "WoW" iface-version-renderer)

    (hide-columns grid hidden-by-default-cols)

    (ss/listen grid :selection (selected-rows-handler installed-addons-selection-handler))

    (state-bind [:installed-addon-list] update-rows-fn)
    (state-bind [:unsteady-addons] watch-for-unsteady-addons)
    (ss/scrollable grid)))

;;

(defn installed-panel
  []
  (mig/mig-panel
   :constraints ["flowy" "fill,grow"]
   :items [[(configure-app-panel)]
           [(installed-addons-panel) "height 100%"]]))

(defn-spec search-input-handler nil?
  [ev ::sp/gui-event]
  (let [text-field-value (ss/value (.getSource ev))]
    (swap! core/state assoc :search-field-input text-field-value))
  nil)

(defn search-input-panel
  []
  (let [install-button (disabled-button "install selected"
                                        :watch [:selected-search]
                                        :callback (comp not empty?)
                                        :onclick (async-handler search-results-install-handler))
        search-input (ss/text :id :search-input-txt :columns 40 :listen [:key-released search-input-handler])]
    (ss/flow-panel :align :left :items ["search" search-input install-button])))

(defn search-results-panel
  []
  (let [hidden-by-default-cols [:source-id]
        tblmdl (sstbl/table-model :columns [{:key :url :text "source"}
                                            :source-id
                                            {:key :label :text "name"}
                                            :description
                                            {:key :tag-list :text "tags"}
                                            {:key :updated-date :text "updated"}
                                            {:key :download-count :text "downloads" :class Integer}]
                                  :rows [])

        grid (x/table-x :id :tbl-search-addons
                        :model tblmdl
                        :highlighters [((x/hl-color :background (colours :installed/hovering)) :rollover-row)])

        ;; an index of labels for all installed addons that gets updated whenever a new addon is installed
        label-idx (atom (set []))
        update-label-idx (fn [_]
                           (reset! label-idx
                                   (->> (get-state :installed-addon-list) (map :label) (remove nil?) set)))
        _ (state-bind [:installed-addon-list] update-label-idx)

        addon-installed? (fn [adapter]
                           (let [label-column (find-column-by-label grid "name")
                                 value (.getValue adapter label-column)]
                             (contains? @label-idx value)))

        date-renderer #(when % (-> % clojure.instant/read-instant-date (utils/fmt-date "yyyy-MM-dd")))

        update-rows-fn (fn [state]
                         (let [uinput (-> state :search-field-input (or "") trim)
                               search-results (if (empty? uinput)
                                                (core/db-search)
                                                (core/db-search uinput))]
                           (insert-all grid search-results)))]

    ;; I'm rather pleased these just work as-is :)
    ;; todo: rename these to something a bit more general
    (installed-addons-go-links grid)
    (installed-addons-panel-column-widths grid)

    (add-cell-renderer grid "updated" date-renderer)
    (add-highlighter grid addon-installed? (colours :search/already-installed))

    (ss/listen grid :selection (selected-rows-handler search-results-selection-handler))
    (state-bind [:db] update-rows-fn)
    (state-bind [:search-field-input] update-rows-fn)

    (hide-columns grid hidden-by-default-cols)

    (ss/scrollable grid)))

(defn search-panel
  []
  (mig/mig-panel
   :constraints ["flowy" "fill,grow"]
   :items [[(search-input-panel)]
           [(search-results-panel) "height 100%"]]))

(defn notice-logger
  []
  (let [mdl (sstbl/table-model :columns [:level :message])
        grid (x/table-x :id :log-window
                        :model mdl
                        :show-grid? false)

        gui-logger (fn [data]
                     (let [{:keys [timestamp_ msg_ level]} data
                           formatted-output-str (force (format "%s - %s" (force timestamp_) (force msg_)))]
                       (insert-one grid {:level level :message formatted-output-str})))

        cell-renderer (javax.swing.table.DefaultTableCellRenderer.)
        _ (.setHorizontalAlignment cell-renderer javax.swing.SwingConstants/CENTER)

        level-width 50
        level-col-idx 0]

    (doto (.getColumn (.getColumnModel grid) level-col-idx)
      (.setMinWidth level-width)
      (.setMaxWidth (* level-width 2))
      (.setPreferredWidth (* level-width 1.5))
      (.setCellRenderer cell-renderer))

    (logging/add-appender! :gui gui-logger {:timestamp-opts {:pattern "HH:mm:ss"}})

    (add-highlighter grid #(= (.getValue % level-col-idx) :warn) (colours :notice/warning))
    (add-highlighter grid #(= (.getValue % level-col-idx) :error) (colours :notice/error))

    ;; hide header
    (.setTableHeader grid nil)

    ;; would love to know how to make these layouts and widths more consistent and deterministic
    (mig/mig-panel
     :constraints ["flowy" "fill,grow"]
     :items [[(ss/scrollable grid) "height 100%"]])))

(defn-spec switch-search-tab-handler nil?
  [_ ::sp/gui-event]
  (let [tabber-component (select-ui :#tabber)]
    (.setSelectedIndex tabber-component 1)
    nil))

(defn mk-tabber
  []
  (let [tabber (ss/tabbed-panel
                :id :tabber
                :tabs [(tab "installed" (installed-panel))
                       (tab "search" (search-panel))])]

    (ss/listen tabber :selection
               (fn [e]
                 (let [tab-label (-> (ss/selection tabber) :title)]
                   (when (= tab-label "search")
                     (ss/request-focus! (select-ui :#search-input-txt))))))

    (ss/listen tabber :mouse-wheel
               (fn [e]
                 (let [num-tabs (.getTabCount tabber)
                       current-idx (.getSelectedIndex tabber)
                       next-idx (inc current-idx)
                       next-idx (if (>= next-idx num-tabs)
                                  0 ;; start over
                                  next-idx)]
                   (ss/selection! tabber next-idx))))

    tabber))

;; todo: push this into core
(defn status-bar
  "this is the litle strip of text at the bottom of the application."
  []
  (let [num-matching-template "%s of %s installed addons found in catalogue."
        all-matching-template "all installed addons found in catalogue."
        catalogue-count-template "%s addons in catalogue."

        status (ss/label :text ""
                         :font (font :size 11))

        update-label (fn [state]
                       (let [ia (:installed-addon-list state)
                             uia (filter :matched? ia)

                             a-count (count (:db state))
                             ia-count (count ia)
                             uia-count (count uia)

                             strings [(format catalogue-count-template a-count)

                                      (if (= ia-count uia-count)
                                        all-matching-template
                                        (format num-matching-template uia-count ia-count))]]

                         (ss/text! status (clojure.string/join " " strings))))]
    (state-bind [:installed-addon-list] update-label)
    status))

(defn-spec export-addon-list-handler nil?
  "prompts user with a file selection dialogue then writes the current directory of addons to the selected file"
  []
  (when-let [path (chooser/choose-file (select-ui :#root)
                                       :type :save
                                       :selection-mode :files-only
                                       :filters [["JSON" ["json"]]]
                                       :success-fn (fn [_ file]
                                                     (str (.getAbsolutePath file))))]
    (core/export-installed-addon-list-safely path)
    nil))

(defn-spec export-user-catalogue-handler nil?
  "prompts user with a file selection dialogue then writes the user catalogue to selected file"
  []
  (when-let [path (chooser/choose-file (select-ui :#root)
                                       :type :open
                                       :selection-mode :files-only
                                       :filters [["JSON" ["json"]]]
                                       :success-fn (fn [_ file]
                                                     (str (.getAbsolutePath file))))]
    (core/export-user-catalogue-addon-list-safely path)
    nil))

(defn-spec import-addon-list-handler nil?
  "prompts user with a file selection dialogue then imports a list of addons from the selected file"
  []
  (when-let [path (chooser/choose-file (select-ui :#root)
                                       :type :open
                                       :selection-mode :files-only
                                       :filters [["JSON" ["json"]]]
                                       :success-fn (fn [_ file]
                                                     (str (.getAbsolutePath file))))]
    (core/import-exported-file path)
    (core/refresh)
    nil))

(defn import-addon-handler
  "imports an addon by parsing a URL"
  []
  (let [addon-url (ss/input "Enter URL of addon"
                            :title "Addon URL"
                            :value "https://github.com/")

        fail "Failed. URL must be:
  * valid
  * originate from github.com
  * addon uses 'releases'
  * latest release has a packaged 'asset'
  * asset must be a .zip file
  * zip file must be structured like an addon"

        failure-warning #(ss/alert fail)

        less-failure-warning #(ss/alert "Failed. Addon successfully added to catalogue but could not be installed.")]
    (when addon-url
      (if-let [result (core/add+install-user-addon! addon-url)]
        (when-not (contains? result :download-url)
          (less-failure-warning))
        (failure-warning))))
  nil)

(defn build-catalogue-menu
  []
  (let [catalogue-to-id (fn [catalogue]
                          (-> catalogue :name name (str "catalogue-menu-") keyword))

        catalogue-button-grp (ss/button-group)
        cat-list (core/get-state :cfg :catalogue-location-list)
        cat-list (if (empty? cat-list) [{:label "No catalogues available" :name :dummy}] cat-list)
        catalogue-menu (mapv (fn [catalogue-location]
                               (ss/radio-menu-item :id (catalogue-to-id catalogue-location)
                                                   :text (:label catalogue-location)
                                                   :user-data catalogue-location
                                                   :group catalogue-button-grp
                                                   :enabled? (-> catalogue-location :name (= :dummy) not)
                                                   :selected? (= (core/get-state :cfg :selected-catalogue) (:name catalogue-location))))
                             cat-list)]

    ;; user selection updates application state
    ;; obtuse bug here if category list is empty: "ArityException Wrong number of args (0) passed to: core/juxt"
    ;; so I've added a dummy catalogue entry that is then disabled
    (sb/bind
     (sb/selection catalogue-button-grp)
     (sb/b-do [val]
              (when val ;; hrm, we're getting two events here, one where the value is nil ...
                (async (fn []
                         (core/set-catalogue-location! (-> val ss/user-data :name))
                         (core/save-settings))))))

    ;; application state updates menu selection
    (core/state-bind [:cfg :selected-catalogue]
                     (fn [state]
                       (let [catalogue-location (core/get-catalogue-location (-> state :cfg :selected-catalogue))
                             button (-> catalogue-location catalogue-to-id as-selector select-ui)]
                         (ss/selection! catalogue-button-grp button))))

    catalogue-menu))

(defn build-theme-menu
  "returns a menu of radio buttons that can toggle through the available themes defined in `core/themes`"
  []
  (let [button-grp (ss/button-group)

        menu (mapv (fn [theme-key]
                     (ss/radio-menu-item :id theme-key
                                         :text (format "%s theme" (-> theme-key name clojure.string/capitalize))
                                         :group button-grp
                                         :selected? (= (core/get-state :cfg :gui-theme) theme-key)))
                   (keys core/themes))]

    (sb/bind (sb/selection button-grp)
             (sb/b-do* (fn [val]
                         (when val ;; sometimes we get nil values?
                           (swap! core/state assoc-in [:cfg :gui-theme] (ss/id-of val))
                           (core/save-settings)
                           (trigger-gui-restart)))))
    menu))

(defn start-ui
  []
  (let [root->splitter (ss/top-bottom-split (mk-tabber) (notice-logger))
        _ (.setResizeWeight root->splitter 0.8)

        root (ss/vertical-panel :id :root
                                :items [root->splitter (status-bar)])

        newui (ss/frame
               :title "strongbox"

               ;; 2019-10 Steam survey says 63% of users run at 1920x1080 (1080p)
               ;; followed by 11% at 1366x768 (16:9 at a lower resolution, think laptops)
               :size [1024 :by 768]

               :content (mig/mig-panel
                         :constraints ["flowy" "fill,grow"] ;; ["debug,flowy"]
                         :items [[root "height 100%"]])

               ;; exit app entirely when not in repl
               ;; calling `in-repl?` from gui thread will always return `nil`
               :on-close (if (core/get-state :in-repl?) :dispose :exit))

        file-menu [(ss/action :name "Installed" :key "menu I" :mnemonic "i" :handler (switch-tab-handler INSTALLED-TAB))
                   (ss/action :name "Search" :key "menu H" :mnemonic "h" :handler (switch-tab-handler SEARCH-TAB))
                   :separator
                   (ss/action :name "Exit" :key "menu Q" :mnemonic "x" :handler (handler #(ss/dispose! newui)))]

        view-menu (build-theme-menu)

        catalogue-menu (into (build-catalogue-menu)
                             [:separator
                              (ss/action :name "Refresh user catalogue" :handler (async-handler core/refresh-user-catalogue))])

        addon-menu [(ss/action :name "Update all" :key "menu U" :mnemonic "u" :handler (async-handler core/install-update-all))
                    (ss/action :name "Re-install all" :handler (async-handler core/re-install-all))
                    :separator
                    (ss/action :name "Remove directory" :handler (async-handler core/remove-addon-dir!))]

        impexp-menu [(ss/action :name "Import addon from Github" :handler (handler import-addon-handler))
                     :separator
                     (ss/action :name "Import addon list" :handler (async-handler import-addon-list-handler))
                     (ss/action :name "Export addon list" :handler (async-handler export-addon-list-handler))
                     (ss/action :name "Export Github addon list" :handler (async-handler export-user-catalogue-handler))]

        cache-menu [(ss/action :name "Clear http cache" :handler (async-handler core/delete-http-cache!))
                    (ss/action :name "Clear addon zips" :handler (async-handler core/delete-downloaded-addon-zips!))
                    (ss/action :name "Clear catalogues" :handler (async-handler (juxt core/db-reload-catalogue core/delete-catalogue-files!)))
                    (ss/action :name "Clear log files" :handler (async-handler core/delete-log-files!))
                    (ss/action :name "Clear all" :handler (async-handler core/clear-all-temp-files!))
                    :separator
                    (ss/action :name "Delete WowMatrix.dat files" :handler (async-handler core/delete-wowmatrix-dat-files!))
                    (ss/action :name "Delete .wowman.json files" :handler (async-handler (comp core/refresh core/delete-wowman-json-files!)))
                    (ss/action :name "Delete .strongbox.json files" :handler (async-handler (comp core/refresh core/delete-strongbox-json-files!)))]

        help-menu [(ss/action :name "About strongbox" :handler (handler about-strongbox-dialog))]

        menu (ss/menubar :id :main-menu
                         :items [(ss/menu :text "File" :mnemonic "F" :items file-menu)
                                 (ss/menu :text "View" :mnemonic "V" :items view-menu)
                                 (ss/menu :text "Catalog" :items catalogue-menu)
                                 (ss/menu :text "Addons" :mnemonic "A" :items addon-menu)
                                 (ss/menu :text "Import/Export" :mnemonic "i" :items impexp-menu)
                                 (ss/menu :text "Cache" :items cache-menu)
                                 (ss/menu :text "Help" :items help-menu)])
        _ (.setJMenuBar newui menu)

        init (fn [newui]
               (future
                 (core/refresh)
                 (core/latest-strongbox-release))
               newui)]

    (ss/invoke-later
     (-> newui ss/show! init))

    newui))

(defn start
  []
  (info "starting gui")
  (swap! core/state assoc :gui (start-ui)))

(defn stop
  []
  (info "stopping gui")
  (try
    ;; don't do this. state may not be started yet for it to be stopped!
    ;;(ss/dispose! (get-state :gui))
    (ss/dispose! (:gui @core/state))
    (catch RuntimeException re
      (warn "failed to stop state:" (.getMessage re)))))
