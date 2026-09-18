(ns de.explorama.frontend.indicator.agent-ops
  (:require [de.explorama.frontend.agent-gateway.registry :as registry]
            [de.explorama.frontend.indicator.config :as config]
            [de.explorama.frontend.woco.agent-ops :as woco-ops]
            [de.explorama.frontend.woco.frame.api :as frame-api]
            [de.explorama.frontend.woco.path :as path]))

(defn- existing-frame [db vertical]
  (some (fn [[id desc]]
          (when (and (map? id) (= vertical (:vertical id)))
            [id desc]))
        (get-in db path/frames)))

(defn open [{:keys [db ok] :as ctx}]
  (if-let [[id desc] (existing-frame db config/default-vertical-str)]
    (do (ok (woco-ops/frame-entry db id desc))
        {:dispatch [::frame-api/bring-to-front id]})
    (woco-ops/open-frame ctx config/default-vertical-str [:de.explorama.frontend.indicator.core/open])))

(defn register! []
  (registry/register-op! :indicator/open open))
