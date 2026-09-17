(ns de.explorama.frontend.table.agent-ops
  (:require [de.explorama.frontend.agent-gateway.registry :as registry]
            [de.explorama.frontend.table.config :as config]
            [de.explorama.frontend.table.vis-state :as vis-state]
            [de.explorama.frontend.woco.agent-ops :as woco-ops]))

(defn set-state [{:keys [params ok fail] :as ctx}]
  (woco-ops/with-frame ctx
    (fn [frame-id]
      (let [{:keys [table-state di local-filter]} params]
        {:dispatch-n [[::vis-state/restore-vis-desc frame-id {:table-state table-state :di di :local-filter local-filter}]
                      [::woco-ops/reply-with (woco-ops/stash-reply! ok fail) (fn [db] (vis-state/vis-desc db frame-id))]]}))))

(defn register! []
  (registry/register-op! :table/open
                         (fn [{:keys [params] :as ctx}]
                           (woco-ops/open-vertical (assoc ctx :params {:vertical config/default-vertical-str
                                                                        :source-frame-id (:source-frame-id params)
                                                                        :position (:position params)}))))
  (registry/register-op! :table/state
                         (fn [{:keys [db ok] :as ctx}]
                           (woco-ops/with-frame ctx (fn [frame-id] (ok (vis-state/vis-desc db frame-id)) {}))))
  (registry/register-op! :table/set-state set-state))
