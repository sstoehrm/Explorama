(ns de.explorama.shared.map.agent-ops
  (:require [de.explorama.shared.woco.agent-ops :as woco]))

(def actions
  [:enum :base-layer :marker :cluster-switch :highlight-marker :overlayer :feature-layer :hide-feature-layer :popup :position-change :filter])

(def state [:map [:di :any] [:vertical string?] [:tool string?] [:title :any] [:task-desc :any]])

(def ops
  [{:op :map/open :side :frontend
    :doc "Open a map frame fed by :source-frame-id at :position. Returns the frame."
    :input [:map {:closed true} [:source-frame-id {:optional true} woco/frame-id] [:position {:optional true} [:tuple number? number?]]]
    :output woco/frame :timeout-ms 10000}
   {:op :map/state :side :frontend
    :doc "The frame's task description: data instance, base layer, marker layouts, feature layers, overlayers, filter and view position."
    :input [:map {:closed true} [:frame-id woco/frame-id]] :output state}
   {:op :map/operation :side :frontend
    :doc "Run one map operation and answer with the state once the map finished it. :params is the payload the legend sends for that action; read :map/state first to see the current shape."
    :input [:map {:closed true} [:frame-id woco/frame-id] [:action actions] [:params {:optional true} map?]]
    :output state :timeout-ms 60000}])
