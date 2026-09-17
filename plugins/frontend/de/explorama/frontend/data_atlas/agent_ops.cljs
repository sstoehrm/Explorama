(ns de.explorama.frontend.data-atlas.agent-ops
  (:require [de.explorama.frontend.agent-gateway.registry :as registry]
            [de.explorama.frontend.data-atlas.core :as core]
            [de.explorama.frontend.woco.agent-ops :as woco-ops]))

(defn register! []
  (registry/register-op! :data-atlas/open
                         (fn [ctx]
                           (woco-ops/open-frame ctx (:vertical (core/create-frame [100 200] [600 550]))
                                                [::core/data-atlas-open nil {:overwrites {:behavior {:force :provided-position}}}]))))
