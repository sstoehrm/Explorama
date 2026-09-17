(ns de.explorama.shared.algorithms.agent-ops
  (:require [de.explorama.shared.woco.agent-ops :as woco]))

(def state [:map [:task :any] [:goal :any] [:settings :any] [:parameter :any] [:result :any]])

(def ops
  [{:op :algorithms/open :side :frontend
    :doc "Open a prediction frame fed by :source-frame-id at :position. Returns the frame."
    :input [:map {:closed true} [:source-frame-id {:optional true} woco/frame-id] [:position {:optional true} [:tuple number? number?]]]
    :output woco/frame :timeout-ms 10000}
   {:op :algorithms/state :side :frontend
    :doc "The frame's current form state (goal, settings, parameters), the last submitted task and the prediction result."
    :input [:map {:closed true} [:frame-id woco/frame-id]] :output state}
   {:op :algorithms/set-value :side :frontend
    :doc "Set one form value the way the UI does: :state-key is :goal, :settings, :parameter, :simple-parameter or :future-data; :path the key path inside it; :map? true when the value is a select option. Returns the state."
    :input [:map {:closed true} [:frame-id woco/frame-id] [:state-key [:enum :goal :settings :parameter :simple-parameter :future-data]]
            [:path [:vector :any]] [:value :any] [:map? {:optional true} boolean?]]
    :output state}
   {:op :algorithms/submit-task :side :frontend
    :doc "Submit a prediction task (the shape :algorithms/state returns under :task) and answer with the state once the prediction arrived."
    :input [:map {:closed true} [:frame-id woco/frame-id] [:task map?]] :output state :timeout-ms 300000}])
