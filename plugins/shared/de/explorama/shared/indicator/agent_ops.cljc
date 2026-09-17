(ns de.explorama.shared.indicator.agent-ops
  (:require [de.explorama.shared.woco.agent-ops :as woco]))

(def frontend-ops
  [{:op :indicator/open :side :frontend
    :doc "Open the indicator management frame (there is at most one). Connect a search to it with :woco/connect. Returns the frame."
    :input [:map {:closed true}] :output woco/frame :timeout-ms 10000}])

(def ops frontend-ops)
