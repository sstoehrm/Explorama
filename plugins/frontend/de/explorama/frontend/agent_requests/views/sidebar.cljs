(ns de.explorama.frontend.agent-requests.views.sidebar
  (:require [de.explorama.frontend.agent-requests.refresh :as refresh]
            [de.explorama.frontend.common.i18n :as i18n]
            [de.explorama.frontend.ui-base.components.formular.core :refer [button]]
            [de.explorama.frontend.ui-base.components.misc.core :refer [hint]]
            [re-frame.core :as re-frame]
            [reagent.core :as r]))

(def ^:private status-labels
  {:open :agent-requests-status-open
   :claimed :agent-requests-status-claimed
   :fulfilled :agent-requests-status-fulfilled
   :failed :agent-requests-status-failed
   :expired :agent-requests-status-expired
   :cancelled :agent-requests-status-cancelled})

(defn- request-row [{:keys [id type status claimed-by]}]
  [:div.flex.justify-between.items-center.gap-2
   {:key id}
   [:div.flex.flex-column
    [:span (str type)]
    [:span @(re-frame/subscribe [::i18n/translate (get status-labels status)])
     (when claimed-by (str " · " claimed-by))]]
   (when (#{:open :claimed} status)
     [button {:label @(re-frame/subscribe [::i18n/translate :agent-requests-cancel])
              :variant :tertiary
              :size :small
              :on-click #(re-frame/dispatch
                          [:de.explorama.frontend.agent-requests.core/cancel-request id])}])])

(defn- content-impl []
  (let [requests @(re-frame/subscribe [:de.explorama.frontend.agent-requests.core/requests])]
    [:div.flex.flex-column.gap-4.p-2
     (if (empty? requests)
       [hint {:variant :info
              :content @(re-frame/subscribe [::i18n/translate :agent-requests-empty])}]
       (into [:<>] (map request-row) requests))]))

(defn content [_props]
  (r/create-class
   {:display-name "agent-requests-sidebar"
    :component-did-mount
    #(refresh/start! re-frame/dispatch [:de.explorama.frontend.agent-requests.core/list-requests])
    :component-will-unmount
    #(refresh/stop!)
    :reagent-render
    (fn [_] [content-impl])}))
