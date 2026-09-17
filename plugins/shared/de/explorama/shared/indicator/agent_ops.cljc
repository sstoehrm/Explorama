(ns de.explorama.shared.indicator.agent-ops
  (:require [de.explorama.shared.woco.agent-ops :as woco]))

(def frontend-ops
  [{:op :indicator/open :side :frontend
    :doc "Open the indicator management frame (there is at most one), or bring the existing one to front if it is already open. Connect a search to it with :woco/connect. Returns the frame."
    :input [:map {:closed true}] :output woco/frame :timeout-ms 10000}])

(def artifact
  [:map [:id string?] [:name string?] [:description {:optional true} string?]
   [:graph-text string?] [:dataset-bindings :any]])

(def backend-ops
  [{:op :indicator/graphs :side :backend
    :doc "The user's aggregation graphs: id, name, description, graph-text, dataset-bindings and the compiled artifact."
    :input [:map {:closed true}] :output :any}
   {:op :indicator/graph :side :backend :doc "One graph by id." :input [:map {:closed true} [:id string?]] :output :any}
   {:op :indicator/validate-graph :side :backend
    :doc "Parse and validate :graph-text against :dataset-count bound datasets without saving. Returns {:errors [...] :warnings [...] :shapes {...}} plus :operations, the operation reference with parameters and shape transitions. A graph is one edn map {:nodes {..} :edges {..}}: nodes are :datasource (with :dataset <n>), :operation (with :op and optional :params) or exactly one :result (with :name); edges {[from to] {:direction :-> :order n :as \"name\"}} form a connected dag."
    :input [:map {:closed true} [:graph-text string?] [:dataset-count int?]] :output :any}
   {:op :indicator/create-graph :side :backend
    :doc "Create a graph artifact. It is recompiled on save; a failing validation is returned as invalid-params. Read an existing graph for the shape of :dataset-bindings. Returns the stored artifact."
    :input [:map {:closed true} [:artifact artifact]] :output :any}
   {:op :indicator/update-graph :side :backend :doc "Replace a graph artifact by :id. Returns the stored artifact." :input [:map {:closed true} [:artifact artifact]] :output :any}
   {:op :indicator/delete-graph :side :backend :doc "Delete a graph. Returns :indicator/graphs." :input [:map {:closed true} [:id string?]] :output :any}
   {:op :indicator/publish-graph :side :backend
    :doc "Compute the graph and publish its result as a data instance other verticals can open. Returns the data instance."
    :input [:map {:closed true} [:id string?]] :output :any :timeout-ms 300000}])

(def ops (into frontend-ops backend-ops))
