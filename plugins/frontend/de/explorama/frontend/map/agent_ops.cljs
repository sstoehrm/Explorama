(ns de.explorama.frontend.map.agent-ops
  (:require [de.explorama.frontend.agent-gateway.registry :as registry]
            [de.explorama.frontend.common.queue :as ddq]
            [de.explorama.frontend.map.config :as config]
            [de.explorama.frontend.map.operations.tasks :as tasks]
            [de.explorama.frontend.map.vis-state :as vis-state]
            [de.explorama.frontend.woco.agent-ops :as woco-ops]))

(defn operation [{:keys [params ok fail] :as ctx}]
  (woco-ops/with-frame ctx
    (fn [frame-id]
      (let [reply-id (woco-ops/stash-reply! ok fail)]
        {:db (ddq/set-event-callback (:db ctx) frame-id [::woco-ops/reply-with reply-id (fn [db] (vis-state/vis-desc db frame-id))])
         :dispatch [::tasks/execute-wrapper frame-id (:action params) (or (:params params) {})]}))))

(defn register! []
  (registry/register-op! :map/open (woco-ops/open-op config/default-vertical-str))
  (registry/register-op! :map/state (woco-ops/state-op vis-state/vis-desc))
  (registry/register-op! :map/operation operation))
