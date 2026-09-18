(ns de.explorama.shared.projects.agent-ops)

(def current
  [:map [:project :any] [:step :any] [:unsaved? boolean?]])

(def ops
  [{:op :projects/current :side :frontend
    :doc "The loaded project (nil when none), the current protocol step and whether the workspace has unsaved changes."
    :input [:map {:closed true}] :output current}
   {:op :projects/create :side :frontend
    :doc "Create a project from the current workspace with :title and :description. This is the only way the agent may persist a workspace; there is no save op. Answers 1.5 s after dispatch and may describe work still in flight; poll :woco/workspace until the interaction mode is :normal. Returns :projects/current."
    :input [:map {:closed true} [:title [:string {:min 1}]] [:description {:optional true} string?]] :output current :timeout-ms 20000}
   {:op :projects/load :side :frontend
    :doc "Load a project into the workspace, replacing what is open. Answers 1.5 s after dispatch and may describe work still in flight; poll :woco/workspace until the interaction mode is :normal. Returns :projects/current."
    :input [:map {:closed true} [:project-id string?]] :output current :timeout-ms 20000}
   {:op :projects/load-step :side :frontend
    :doc "Replay the loaded project up to protocol step :step. Answers 1.5 s after dispatch and may describe work still in flight; poll :woco/workspace until the interaction mode is :normal. Returns :projects/current."
    :input [:map {:closed true} [:step int?]] :output current :timeout-ms 20000}
   {:op :projects/list :side :backend
    :doc "The user's projects grouped into :created-projects, :allowed-projects, :read-only-projects and :public-read-only-projects, each id -> description with :title and :description."
    :input [:map {:closed true}] :output map?}
   {:op :projects/rename :side :backend
    :doc "Change a project's title. Returns :projects/list."
    :input [:map {:closed true} [:project-id string?] [:title [:string {:min 1}]]] :output map?}
   {:op :projects/set-description :side :backend
    :doc "Change a project's description. Returns :projects/list."
    :input [:map {:closed true} [:project-id string?] [:description string?]] :output map?}])
