(ns strongbox.ui.check-combo-box
  (:require
   [cljfx.api] ;; Eastwood dies mid-lint without this: "Toolkit not initialized"

   [cljfx.composite :as composite]
   [cljfx.lifecycle :as lifecycle]
   [cljfx.fx.control]
   [cljfx.mutator :as mutator]
   [cljfx.coerce :as coerce])
  (:import
   [org.controlsfx.control IndexedCheckModel CheckComboBox]
   [javafx.collections ObservableList FXCollections]
   [java.util Collection]))

;; checkModelProperty https://controlsfx.github.io/javadoc/11.1.1/org.controlsfx.controls/org/controlsfx/control/CheckListView.html#checkModelProperty()
;; seeing a bug here: https://github.com/controlsfx/controlsfx/issues/1030
;; this fn gets called twice if you click the label vs the checkbox
(def props
  (merge
   cljfx.fx.control/props
   (composite/props CheckComboBox
                    :items [:list lifecycle/scalar]
                    :converter [:setter lifecycle/scalar :coerce coerce/string-converter]
                    :show-checked-count [:setter lifecycle/scalar :default false]
                    :title [:setter lifecycle/scalar]
                    :check-model [:setter lifecycle/scalar]

                    :on-checked-items-changed [(mutator/list-change-listener
                                                #(.getCheckedItems ^IndexedCheckModel (.getCheckModel ^CheckComboBox %)))
                                               lifecycle/list-change-listener])))

(def lifecycle
  (composite/describe CheckComboBox
                      :ctor []
                      :props props))
