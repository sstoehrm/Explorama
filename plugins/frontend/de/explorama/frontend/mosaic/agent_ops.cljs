(ns de.explorama.frontend.mosaic.agent-ops
  (:require [de.explorama.frontend.agent-gateway.registry :as registry]
            [de.explorama.frontend.common.queue :as ddq]
            [de.explorama.frontend.mosaic.config :as config]
            [de.explorama.frontend.mosaic.operations.tasks :as tasks]
            [de.explorama.frontend.mosaic.path :as gp]
            [de.explorama.frontend.mosaic.vis.state :as vis-state]
            [de.explorama.frontend.woco.agent-ops :as woco-ops]))

(defn- state [db frame-id]
  (vis-state/get-state db frame-id))

(defn- queued [{:keys [ok fail] :as ctx} event-fn]
  (woco-ops/with-frame ctx
    (fn [frame-id]
      (let [reply-id (woco-ops/stash-reply! ok fail)]
        {:db (ddq/set-event-callback (:db ctx) frame-id [::woco-ops/reply-with reply-id (fn [db] (state db frame-id))])
         :dispatch (event-fn frame-id)}))))

(defn operation [{:keys [params] :as ctx}]
  (queued ctx (fn [frame-id] [::tasks/execute-wrapper (gp/top-level frame-id) (:action params) (or (:params params) {})])))

(defn set-layouts [{:keys [params] :as ctx}]
  (queued ctx (fn [frame-id] [:de.explorama.frontend.mosaic.views.legend/change-layout frame-id (:layouts params)])))

(defn remove-layout [{:keys [params] :as ctx}]
  (queued ctx (fn [frame-id] [:de.explorama.frontend.mosaic.views.legend/remove-layout frame-id (:layout-id params)])))

(defn filter-op [{:keys [params] :as ctx}]
  (queued ctx (fn [frame-id] [:de.explorama.frontend.mosaic.views.filter.core/submit-filter frame-id (:filter-desc params)])))

(defn register! []
  (registry/register-op! :mosaic/open (woco-ops/open-op config/default-vertical-str))
  (registry/register-op! :mosaic/state (woco-ops/state-op state))
  (registry/register-op! :mosaic/operation operation)
  (registry/register-op! :mosaic/set-layouts set-layouts)
  (registry/register-op! :mosaic/remove-layout remove-layout)
  (registry/register-op! :mosaic/filter filter-op))
