(ns de.explorama.shared.table.agent-ops
  (:require [de.explorama.shared.woco.agent-ops :as woco]))

(def state [:map [:di :any] [:vertical string?] [:tool string?] [:title :any] [:table-state :any] [:local-filter :any]])

(def ops
  [{:op :table/open :side :frontend
    :doc "Open a table frame fed by :source-frame-id at :position. Returns the frame."
    :input [:map {:closed true} [:source-frame-id {:optional true} woco/frame-id] [:position {:optional true} [:tuple number? number?]]]
    :output woco/frame :timeout-ms 10000}
   {:op :table/state :side :frontend
    :doc "The frame's data instance, table state (sorting, column layout as the table encodes it) and local filter."
    :input [:map {:closed true} [:frame-id woco/frame-id]] :output state}
   {:op :table/set-state :side :frontend
    :doc "Restore :table-state, :di and/or :local-filter on the frame, the way a project reload does. Read :table/state first and modify what it returned. Returns the state."
    :input [:map {:closed true} [:frame-id woco/frame-id] [:table-state {:optional true} :any] [:di {:optional true} :any] [:local-filter {:optional true} :any]]
    :output state :timeout-ms 60000}])
