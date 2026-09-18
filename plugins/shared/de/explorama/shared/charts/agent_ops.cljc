(ns de.explorama.shared.charts.agent-ops
  (:require [de.explorama.shared.woco.agent-ops :as woco]))

(def state [:map [:di :any] [:vertical string?] [:tool string?] [:title :any] [:chart-desc :any] [:local-filter :any]])

(def ops
  [{:op :charts/open :side :frontend
    :doc "Open a charts frame fed by :source-frame-id at :position. Returns the frame."
    :input [:map {:closed true} [:source-frame-id {:optional true} woco/frame-id] [:position {:optional true} [:tuple number? number?]]]
    :output woco/frame :timeout-ms 10000}
   {:op :charts/state :side :frontend
    :doc "The frame's data instance, chart description and local filter."
    :input [:map {:closed true} [:frame-id woco/frame-id]] :output state}
   {:op :charts/set-state :side :frontend
    :doc "Restore :chart-desc, :di and/or :local-filter on the frame, the way a project reload does. Read :charts/state first and modify what it returned. Returns the state."
    :input [:map {:closed true} [:frame-id woco/frame-id] [:chart-desc {:optional true} :any] [:di {:optional true} :any] [:local-filter {:optional true} :any]]
    :output state :timeout-ms 60000}])
