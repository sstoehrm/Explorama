(ns de.explorama.frontend.agent-requests.core
  (:require [de.explorama.frontend.agent-requests.path :as path]
            [de.explorama.frontend.agent-requests.refresh :as refresh]
            [de.explorama.frontend.agent-requests.views.sidebar :as sidebar]
            [de.explorama.frontend.common.frontend-interface :as fi]
            [de.explorama.frontend.common.i18n :as i18n]
            [de.explorama.shared.agent-requests.ws-api :as ws-api]
            [re-frame.core :as re-frame]
            [taoensso.timbre :refer [error]]))

(def ^:private vertical-str "agent-requests")
(def ^:private tool-name "tool-agent-requests")

(defn set-requests [db requests]
  (assoc-in db path/requests (vec requests)))

(defn set-refreshed-at [db now]
  (assoc-in db path/refreshed-at now))

(re-frame/reg-event-fx
 ::list-requests
 (fn [{db :db} _]
   (let [user-info (fi/call-api :user-info-db-get db)]
     {:backend-tube [ws-api/list-requests
                     {:client-callback [ws-api/list-requests-result]}
                     user-info]})))

(re-frame/reg-event-db
 ws-api/list-requests-result
 (fn [db [_ requests]]
   (-> (set-requests db requests)
       (set-refreshed-at (js/Date.now)))))

(re-frame/reg-event-fx
 ::cancel-request
 (fn [{db :db} [_ id]]
   (let [user-info (fi/call-api :user-info-db-get db)]
     {:backend-tube [ws-api/cancel-request
                     {:client-callback [ws-api/list-requests-result]}
                     user-info
                     id]})))

(re-frame/reg-event-db
 ::sidebar-close
 (fn [db _]
   (set-requests db [])))

(re-frame/reg-sub
 ::requests
 (fn [db _]
   (get-in db path/requests [])))

(re-frame/reg-sub
 ::refreshed-at
 (fn [db _]
   (get-in db path/refreshed-at)))

(def ^:private create-sidebar
  {:id tool-name
   :width 400
   :module "agent-requests-sidebar"
   :title [::i18n/translate :agent-requests-title]
   :close-event [::sidebar-close]
   :position :right
   :vertical vertical-str})

(re-frame/reg-event-fx
 ::open-sidebar
 (fn [_ _]
   {:dispatch (fi/call-api :sidebar-create-event-vec create-sidebar)}))

(re-frame/reg-event-fx
 ::arrive
 (fn [_ _]
   (let [{tools-register :tools-register-event-vec
          init-done :init-done-event-vec} (fi/api-definitions)]
     {:fx [[:dispatch (tools-register {:id tool-name
                                       :icon :magic
                                       :component :agent-requests
                                       :action [::open-sidebar]
                                       :tooltip-text [::i18n/translate :agent-requests-title]
                                       :vertical vertical-str
                                       :tool-group :header
                                       :header-group :left
                                       :sort-order 3})]
           [:dispatch (init-done vertical-str)]]})))

(defn clean-workspace-fx [{db :db} [_ follow-event _reason]]
  (refresh/stop!)
  {:db (set-requests db [])
   :dispatch (conj follow-event ::clean-workspace)})

(re-frame/reg-event-fx ::clean-workspace clean-workspace-fx)

(defn logout-fx [_ _]
  (refresh/stop!)
  {})

(re-frame/reg-event-fx ::logout logout-fx)

(re-frame/reg-event-fx
 ::init-event
 (fn [_ _]
   (let [{service-register :service-register-event-vec} (fi/api-definitions)]
     {:dispatch-n [[::arrive]
                   (service-register :modules "agent-requests-sidebar" sidebar/content)
                   (service-register :clean-workspace ::clean-workspace [::clean-workspace])
                   (service-register :logout-events :agent-requests-logout [::logout])]})))

(def ^:private max-check-tries 100)

(defn register-init [current-tries]
  (cond
    (fi/api-definitions) (fi/call-api :init-register-event-dispatch ::init-event vertical-str)
    (< current-tries max-check-tries) (js/setTimeout #(register-init (inc current-tries)) 100)
    :else (error "Max number of tries reached to check for frontend-interface api.")))

(defn init []
  (register-init 0))
