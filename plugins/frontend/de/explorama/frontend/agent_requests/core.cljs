(ns de.explorama.frontend.agent-requests.core
  (:require [de.explorama.frontend.agent-requests.path :as path]
            [de.explorama.frontend.agent-requests.views.sidebar :as sidebar]
            [de.explorama.frontend.common.frontend-interface :as fi]
            [de.explorama.frontend.common.i18n :as i18n]
            [de.explorama.shared.agent-requests.ws-api :as ws-api]
            [re-frame.core :as re-frame]
            [taoensso.timbre :refer [error]]))

(def ^:private vertical-str "agent-requests")
(def ^:private tool-name "tool-agent-requests")
(def ^:private refresh-ms 3000)

(defn set-requests [db requests]
  (assoc-in db path/requests (vec requests)))

(defn set-open [db open?]
  (assoc-in db path/open? open?))

(defn open-count [requests]
  (count (filter (comp #{:open :claimed} :status) requests)))

(re-frame/reg-event-fx
 ::list-requests
 (fn [{db :db} _]
   (when (get-in db path/open?)
     {:backend-tube [ws-api/list-requests
                     {:client-callback [ws-api/list-requests-result]}]})))

(re-frame/reg-event-fx
 ws-api/list-requests-result
 (fn [{db :db} [_ requests]]
   (cond-> {:db (set-requests db requests)}
     (get-in db path/open?)
     (assoc :dispatch-later [{:ms refresh-ms :dispatch [::list-requests]}]))))

(re-frame/reg-event-fx
 ::cancel-request
 (fn [_ [_ id]]
   {:backend-tube [ws-api/cancel-request
                   {:client-callback [ws-api/list-requests-result]}
                   id]}))

(re-frame/reg-event-fx
 ::sidebar-open
 (fn [{db :db} _]
   {:db (set-open db true)
    :dispatch [::list-requests]}))

(re-frame/reg-event-db
 ::sidebar-close
 (fn [db _]
   (-> (set-open db false)
       (set-requests []))))

(re-frame/reg-sub
 ::requests
 (fn [db _]
   (get-in db path/requests [])))

(re-frame/reg-sub
 ::open-count
 :<- [::requests]
 (fn [requests _]
   (open-count requests)))

(def ^:private create-sidebar
  {:id tool-name
   :width 400
   :module "agent-requests-sidebar"
   :title [::i18n/translate :agent-requests-title]
   :event ::sidebar-open
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
                                       :icon "list"
                                       :component :agent-requests
                                       :action [::open-sidebar]
                                       :tooltip-text [::i18n/translate :agent-requests-title]
                                       :vertical vertical-str
                                       :type :frame/management-type
                                       :tool-group :bar
                                       :bar-group :bottom
                                       :sort-order 9})]
           [:dispatch (init-done vertical-str)]]})))

(re-frame/reg-event-fx
 ::init-event
 (fn [_ _]
   (let [{service-register :service-register-event-vec} (fi/api-definitions)]
     {:dispatch-n [[::arrive]
                   (service-register :modules "agent-requests-sidebar" sidebar/content)]})))

(def ^:private max-check-tries 100)

(defn register-init [current-tries]
  (cond
    (fi/api-definitions) (fi/call-api :init-register-event-dispatch ::init-event vertical-str)
    (< current-tries max-check-tries) (js/setTimeout #(register-init (inc current-tries)) 100)
    :else (error "Max number of tries reached to check for frontend-interface api.")))

(defn init []
  (register-init 0))
