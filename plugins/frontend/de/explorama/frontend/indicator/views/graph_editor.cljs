(ns de.explorama.frontend.indicator.views.graph-editor
  (:require [clojure.string :as str]
            [de.explorama.frontend.common.frontend-interface :as fi]
            [de.explorama.frontend.common.i18n :as i18n]
            [de.explorama.frontend.ui-base.components.formular.core :refer [button
                                                                            input-field
                                                                            section
                                                                            textarea]]
            [de.explorama.frontend.ui-base.components.misc.core :refer [icon]]
            [de.explorama.frontend.indicator.components.dialog :as dialog]
            [de.explorama.frontend.indicator.views.graph-management :as gm]
            [de.explorama.frontend.indicator.views.result-preview :as result-preview]
            [de.explorama.shared.common.configs.platform-specific :as config-platform]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]))

(defn- header-row [graph-id]
  (let [{:keys [name description]} @(re-frame/subscribe [::gm/graph-meta graph-id])
        {name-label :indicator-name
         desc-label :indicator-desc
         desc-placeholder :indicator-desc-placeholder}
        @(re-frame/subscribe [::i18n/translate-multi :indicator-name :indicator-desc :indicator-desc-placeholder])]
    [:div.flex.flex-col.gap-2
     [:h1 name]
     [input-field {:label name-label
                   :extra-class "input--w14"
                   :value name
                   :max-length 25
                   :on-change (fn [val]
                                (re-frame/dispatch [::gm/update-graph-prop graph-id :name val]))}]
     [textarea {:label desc-label
                :extra-class "input--w14"
                :value (or description "")
                :placeholder desc-placeholder
                :max-length 255
                :on-change (fn [val]
                             (re-frame/dispatch [::gm/update-graph-prop graph-id :description val]))}]]))

(def ^:private graph-drop-area-id (str ::drop-area))

(defn- handle-drop [drag-entered-state _ _]
  (reset! drag-entered-state false))

(defn- handle-drag-enter [drag-entered-state _ _]
  (reset! drag-entered-state true))

(defn- handle-drag-leave [drag-entered-state _ _]
  (reset! drag-entered-state false))

(re-frame/reg-event-fx
 ::register-drop-handler
 (fn [{db :db} [_ drag-entered-state frame-id]]
   (when-let [service-register-db-update (fi/api-definition :service-register-db-update)]
     {:db (-> db
              (service-register-db-update :frame-drop-hitbox
                                          ::hitboxes
                                          {:dom-ids [graph-drop-area-id]
                                           :id frame-id
                                           :global-context? true
                                           :default-connect? true
                                           :on-drag-enter (partial handle-drag-enter drag-entered-state)
                                           :on-drag-leave (partial handle-drag-leave drag-entered-state)
                                           :on-drop (partial handle-drop drag-entered-state)}))})))

(re-frame/reg-event-fx
 ::unregister-drop-handler
 (fn [{db :db}]
   (when-let [service-deregister-db-update (fi/api-definition :service-deregister-db-update)]
     {:db (-> db
              (service-deregister-db-update :frame-drop-hitbox
                                            ::hitboxes))})))

(re-frame/reg-sub
 ::dataset-bindings
 (fn [db [_ graph-id]]
   (gm/dataset-bindings db graph-id)))

(defn- dataset-chip [n]
  [:span {:class "rounded-full px-2 py-1 text-sm border border-(--border-secondary) bg-(--bg-over-bg)"}
   (str "Dataset " n)])

(defn- drop-area [frame-id _ _]
  (let [drag-entered-state (reagent/atom false)]
    (reagent/create-class
     {:component-did-mount #(re-frame/dispatch [::register-drop-handler drag-entered-state frame-id])
      :component-will-unmount #(re-frame/dispatch [::unregister-drop-handler])
      :reagent-render
      (fn [_ graph-id _]
        (let [drop-area-hint @(re-frame/subscribe [::i18n/translate :drop-area-text])
              bindings @(re-frame/subscribe [::dataset-bindings graph-id])
              no-datasets? (empty? bindings)]
          [:div {:id graph-drop-area-id
                 :class ["drag-drop-area"
                         (when no-datasets?
                           "drag-drop-area--empty")
                         (when @drag-entered-state
                           "drop-target")]}
           (when no-datasets? [:span drop-area-hint])
           [:div.flex.flex-wrap.gap-2
            (for [[n _] (sort-by key bindings)]
              ^{:key n} [dataset-chip n])]]))})))

(defn- graph-textarea [graph-id]
  (let [label @(re-frame/subscribe [::i18n/translate :indicator-graph-text-label])
        text @(re-frame/subscribe [::gm/graph-text graph-id])]
    [textarea {:label label
               :extra-class "custom-indicator"
               :max-length nil
               :value (or text "")
               :on-change (fn [val]
                            (re-frame/dispatch [::gm/set-graph-text graph-id val]))}]))

(defn- validation-summary [{:keys [parse-error errors warnings]}]
  [:<>
   (when parse-error
     [:div {:class "p-2 rounded-md bg-(--bg-error) text-(--text-warning)"} parse-error])
   (when (seq errors)
     (into [:ul.flex.flex-col.gap-1]
           (map (fn [{:keys [code message node edge]}]
                  [:li.text-warning (str (name code)
                                         (when node (str " @ " node))
                                         (when edge (str " @ " (pr-str edge)))
                                         ": " message)]))
           errors))
   (when (seq warnings)
     (into [:ul.flex.flex-col.gap-1]
           (map (fn [{:keys [code message]}]
                  [:li.text-warning (str (name code) ": " message)]))
           warnings))])

(defn- validation-panel [graph-id]
  (let [validation @(re-frame/subscribe [::gm/validation graph-id])
        label @(re-frame/subscribe [::i18n/translate :indicator-graph-validation-label])]
    [:div.flex.flex-col.gap-2
     [:div.indicator__section__title label]
     [validation-summary validation]]))

(defn- agent-section [_graph-id]
  (when config-platform/agent-requests-available?
    (let [prompt (reagent/atom "")]
      (fn [graph-id]
        (let [pending? @(re-frame/subscribe [::gm/agent-pending? graph-id])
              {prompt-label :indicator-graph-agent-prompt-label
               generate-label :indicator-graph-generate}
              @(re-frame/subscribe [::i18n/translate-multi
                                    :indicator-graph-agent-prompt-label
                                    :indicator-graph-generate])]
          [:div.flex.flex-col.gap-2
           [textarea {:label prompt-label
                      :extra-class "custom-indicator"
                      :max-length nil
                      :value @prompt
                      :on-change #(reset! prompt %)}]
           [:div.flex.items-center.gap-2
            [button {:label generate-label
                     :disabled? (or pending? (str/blank? @prompt))
                     :on-click #(re-frame/dispatch [::gm/request-generation graph-id @prompt])}]
            (when pending? [:div.loader-sm [:span]])]])))))

(defn- proposal-pane [graph-id]
  (let [{:keys [text validation error]} @(re-frame/subscribe [::gm/proposal graph-id])
        dirty? @(re-frame/subscribe [::gm/dirty? graph-id])
        {apply-label :indicator-graph-apply-proposal
         dismiss-label :indicator-graph-dismiss-proposal}
        @(re-frame/subscribe [::i18n/translate-multi
                              :indicator-graph-apply-proposal
                              :indicator-graph-dismiss-proposal])]
    (when (or text error)
      [:div.flex.flex-col.gap-2
       (if error
         [:div {:class "p-2 rounded-md bg-(--bg-error) text-(--text-warning)"} (str error)]
         [:<>
          [:pre.custom-indicator text]
          [validation-summary validation]
          [:div.flex.gap-2
           [button {:label apply-label
                    :on-click #(if dirty?
                                 (re-frame/dispatch [::dialog/set-show "apply-proposal" graph-id true])
                                 (re-frame/dispatch [::gm/apply-proposal graph-id]))}]
           [button {:label dismiss-label
                    :variant :secondary
                    :on-click #(re-frame/dispatch [::gm/dismiss-proposal graph-id])}]]])])))

(defn- operation-reference []
  (let [md @(re-frame/subscribe [::gm/operation-reference])
        label @(re-frame/subscribe [::i18n/translate :indicator-graph-editor-ops-label])]
    [:div.flex.flex-col.gap-2
     [:div.indicator__section__title label]
     (into [:ul.flex.flex-col.gap-1]
           (map (fn [[op {:keys [description arguments attributes]}]]
                  [:li [:b (str op)]
                   [:span (str " " description " (inputs: " (if (= 0 arguments) "2+" arguments)
                               ", params: " (or (some->> attributes keys (mapv name)) "none") ")")]]))
           (sort-by key md))]))

(defn- preview-cell-style []
  {:float :left
   :display :block
   :width 120
   :overflow :hidden
   :white-space :nowrap
   :text-overflow :ellipsis})

(defn- preview-header [attribute-labels header]
  [:ul {:style {:width "100%" :height 10}}
   (for [[index ele] (map-indexed vector header)]
     (let [attribute-label (get attribute-labels ele ele)]
       ^{:key (str "graph-preview-col-h-" index)}
       [:li {:title attribute-label :style (preview-cell-style)} attribute-label]))])

(defn- preview-row [language header row]
  [:ul
   (for [[num item-key] (map-indexed vector header)
         :let [item-org (result-preview/format-changes language (get row item-key))]]
     ^{:key (str "graph-preview-col-" num)}
     [:li {:title item-org :style (preview-cell-style)} item-org])])

(defn- preview-section [graph-id]
  (let [{:keys [data header]} @(re-frame/subscribe [::result-preview/preview-result graph-id])
        {:keys [input-data-section calculate-preview-button no-data]}
        @(re-frame/subscribe [::i18n/translate-multi :input-data-section :calculate-preview-button :no-data])
        attribute-labels @(fi/call-api [:i18n :get-labels-sub])
        hint-label @(re-frame/subscribe [::i18n/translate :input-data-section-hint])
        language @(re-frame/subscribe [::i18n/current-language])
        has-result? (and data header)]
    [section {:label input-data-section
              :default-open? false
              :hint hint-label}
     [:div
      [button {:label calculate-preview-button
               :extra-style {:pointer-events :auto}
               :on-click #(re-frame/dispatch [::gm/calculate-preview graph-id])}]
      (when has-result? [icon {:icon :check :color :green}])]
     [:div.prediction__data__list
      (if has-result?
        [:<>
         [preview-header attribute-labels header]
         (doall (map-indexed (fn [i row]
                               ^{:key i} [preview-row language header row])
                             data))]
        no-data)]]))

(defn- footer [graph-id]
  (let [{save-label :indicator-save
         discard-label :indicator-discard-changes
         back-label :back-to-overview-label}
        @(re-frame/subscribe [::i18n/translate-multi :indicator-save :indicator-discard-changes :back-to-overview-label])
        valid? @(re-frame/subscribe [::gm/valid? graph-id])
        dirty? @(re-frame/subscribe [::gm/dirty? graph-id])]
    [:div {:class "flex items-center gap-2 p-3 border-t border-(--border)"}
     [button {:start-icon :previous
              :variant :back
              :label back-label
              :on-click #(re-frame/dispatch [::gm/change-active-graph nil])}]
     [:span.flex-1]
     [button {:start-icon :save
              :extra-style {:pointer-events :auto}
              :label save-label
              :disabled? (not valid?)
              :on-click #(re-frame/dispatch [::gm/save-graph graph-id])}]
     [button {:start-icon :cross
              :variant :secondary
              :label discard-label
              :disabled? (not dirty?)
              :on-click #(re-frame/dispatch [::gm/discard-changes graph-id])}]]))

(defn view [frame-id drop-area-props]
  (let [graph-id @(re-frame/subscribe [::gm/active-graph-id])]
    (when graph-id
      (let [valid? @(re-frame/subscribe [::gm/valid? graph-id])]
        [:<>
         [:div.indicator__wrapper
          [header-row graph-id]
          [drop-area frame-id graph-id drop-area-props]
          [:div.flex.gap-4.items-start
           [:div.flex-1.min-w-0 [graph-textarea graph-id]]
           [:div.flex-1.min-w-0.flex.flex-col.gap-4
            [validation-panel graph-id]
            [agent-section graph-id]
            [proposal-pane graph-id]
            [operation-reference]]]
          (when valid?
            [preview-section graph-id])]
         [footer graph-id]]))))
