(ns de.explorama.frontend.algorithms.agent-ops
  (:require [de.explorama.frontend.agent-gateway.registry :as registry]
            [de.explorama.frontend.algorithms.config :as config]
            [de.explorama.frontend.algorithms.event-logging :as event-logging]
            [de.explorama.frontend.algorithms.path.core :as paths]
            [de.explorama.frontend.woco.agent-ops :as woco-ops]))

(defn state [db frame-id]
  {:task (get-in db (paths/prediction-task frame-id))
   :goal (get-in db (paths/goal-state frame-id))
   :settings (get-in db (paths/settings-state frame-id))
   :parameter (get-in db (paths/parameter-state frame-id))
   :result (get-in db (paths/result frame-id))})

(defn set-value [{:keys [db params ok fail] :as ctx}]
  (woco-ops/with-frame ctx
    (fn [frame-id]
      (let [fx (event-logging/ui-value-changed-event db frame-id nil (select-keys params [:state-key :path :value :map?]))]
        (update fx :fx (fnil conj []) [:dispatch [::woco-ops/reply-with (woco-ops/stash-reply! ok fail) (fn [db] (state db frame-id))]])))))

(defn submit-task [{:keys [params ok fail] :as ctx}]
  (woco-ops/with-frame ctx
    (fn [frame-id]
      (let [{:keys [task]} params]
        {:dispatch [:de.explorama.frontend.algorithms.components.main/submit-task frame-id task true
                    [::woco-ops/reply-with (woco-ops/stash-reply! ok fail) (fn [db] (state db frame-id))]]}))))

(defn register! []
  (registry/register-op! :algorithms/open (woco-ops/open-op config/default-vertical-str))
  (registry/register-op! :algorithms/state (woco-ops/state-op state))
  (registry/register-op! :algorithms/set-value set-value)
  (registry/register-op! :algorithms/submit-task submit-task))
