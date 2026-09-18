(ns de.explorama.frontend.charts.agent-ops
  (:require [de.explorama.frontend.agent-gateway.registry :as registry]
            [de.explorama.frontend.charts.config :as config]
            [de.explorama.frontend.charts.vis-state :as vis-state]
            [de.explorama.frontend.woco.agent-ops :as woco-ops]))

(defn set-state [{:keys [params ok fail] :as ctx}]
  (woco-ops/with-frame ctx
    (fn [frame-id]
      (let [{:keys [chart-desc di local-filter]} params]
        {:dispatch-n [[::vis-state/restore-vis-desc frame-id {:chart-desc chart-desc :di di :local-filter local-filter}]
                      [::woco-ops/reply-with (woco-ops/stash-reply! ok fail) (fn [db] (vis-state/vis-desc db frame-id))]]}))))

(defn register! []
  (registry/register-op! :charts/open (woco-ops/open-op config/default-vertical-str))
  (registry/register-op! :charts/state (woco-ops/state-op vis-state/vis-desc))
  (registry/register-op! :charts/set-state set-state))
