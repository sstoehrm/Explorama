(ns de.explorama.shared.data-atlas.agent-ops
  (:require [de.explorama.shared.woco.agent-ops :as woco]))

(def ops
  [{:op :data-atlas/open :side :frontend
    :doc "Open the data atlas frame. It has no readable state; use :woco/frames to see it."
    :input [:map {:closed true}] :output woco/frame :timeout-ms 10000}])
