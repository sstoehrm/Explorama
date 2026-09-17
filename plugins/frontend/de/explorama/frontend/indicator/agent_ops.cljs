(ns de.explorama.frontend.indicator.agent-ops
  (:require [de.explorama.frontend.agent-gateway.registry :as registry]
            [de.explorama.frontend.indicator.core :as core]
            [de.explorama.frontend.woco.agent-ops :as woco-ops]))

(defn register! []
  (registry/register-op! :indicator/open
                         (fn [ctx]
                           (woco-ops/open-frame ctx (:vertical (core/create-frame [100 200] [800 700])) [::core/open]))))
