(ns de.explorama.frontend.search.agent-ops
  (:require [de.explorama.frontend.agent-gateway.registry :as registry]
            [de.explorama.frontend.common.frontend-interface :as fi]
            [de.explorama.frontend.search.backend.di :as di]
            [de.explorama.frontend.search.backend.util :refer [build-options-request-params event-log-keys]]
            [de.explorama.frontend.search.path :as spath]
            [de.explorama.frontend.woco.agent-ops :as woco-ops]
            [de.explorama.shared.search.ws-api :as ws-api]))

(def ^:private vertical "search")

(defn form-state [db frame-id]
  (get-in db (spath/frame-search-rows frame-id) {}))

(defn- data-instance [db frame-id]
  (when-let [api (fi/call-api :frame-info-api-get-db db frame-id)]
    ((:di api) db frame-id)))

(defn- attributes [db frame-id]
  {:attribute-types (get-in db spath/attribute-types)
   :frame-attributes (get-in db (spath/frame-attributes frame-id))})

(defn- reply-form [ok fail frame-id]
  [::woco-ops/reply-with (woco-ops/stash-reply! ok fail) (fn [db] (form-state db frame-id))])

(defn set-form [{:keys [params ok fail] :as ctx}]
  (woco-ops/with-frame ctx
    (fn [frame-id]
      (let [{:keys [formdata]} params]
        {:dispatch-n [[:de.explorama.frontend.search.views.formdata/formdata frame-id formdata]
                      (reply-form ok fail frame-id)]}))))

(defn add-rows [{:keys [params ok fail] :as ctx}]
  (woco-ops/with-frame ctx
    (fn [frame-id]
      (let [{:keys [rows]} params]
        {:dispatch-n [[:de.explorama.frontend.search.api.core/add-search-rows rows false nil frame-id]
                      (reply-form ok fail frame-id)]}))))

(defn run [{:keys [db ok fail] :as ctx}]
  (woco-ops/with-frame ctx
    (fn [frame-id]
      (let [rows (get-in db (spath/frame-search-rows frame-id))
            {formdata :formdata} (build-options-request-params db frame-id nil rows false)
            {log-formdata :formdata} (build-options-request-params db frame-id nil rows event-log-keys {:translate-topics? false})
            datasources (get-in db spath/search-enabled-datasources)
            reply-id (woco-ops/stash-reply! ok fail)]
        {:db (-> db
                 (assoc-in (spath/di-creation-pending frame-id) true)
                 (assoc-in (spath/frame-wait-callback frame-id)
                           [::woco-ops/reply-with reply-id (fn [db] (data-instance db frame-id))])
                 (assoc-in (spath/frame-wait-callback-keys frame-id) #{:create}))
         :dispatch-n [(fi/call-api :reset-selections-event-vec frame-id)
                      [:de.explorama.frontend.search.event-logging/log-event frame-id "create-data-instance" {:formdata (into {} log-formdata)}]
                      [ws-api/create-di datasources frame-id formdata [::di/wait-callback frame-id :create]]]}))))

(defn register! []
  (registry/register-op! :search/open
                         (fn [ctx]
                           (woco-ops/open-frame ctx vertical
                                                [:de.explorama.frontend.search.core/search-open nil
                                                 {:overwrites {:behavior {:force :provided-position}}}])))
  (registry/register-op! :search/attributes
                         (fn [{:keys [db ok] :as ctx}]
                           (woco-ops/with-frame ctx (fn [frame-id] (ok (attributes db frame-id)) {}))))
  (registry/register-op! :search/form-state
                         (fn [{:keys [db ok] :as ctx}]
                           (woco-ops/with-frame ctx (fn [frame-id] (ok (form-state db frame-id)) {}))))
  (registry/register-op! :search/data-instance
                         (fn [{:keys [db ok] :as ctx}]
                           (woco-ops/with-frame ctx (fn [frame-id] (ok (data-instance db frame-id)) {}))))
  (registry/register-op! :search/set-form set-form)
  (registry/register-op! :search/add-rows add-rows)
  (registry/register-op! :search/run run)
  (registry/register-op! :search/open-visualization
                         (fn [{:keys [params] :as ctx}]
                           (woco-ops/with-frame ctx
                             (fn [frame-id]
                               (woco-ops/open-vertical (assoc ctx :params {:vertical (:vertical params)
                                                                            :source-frame-id frame-id
                                                                            :position (:position params)})))))))
