(ns de.explorama.backend.indicator.graph-request
  (:require [de.explorama.backend.agent-requests.registry :as registry]
            [de.explorama.backend.agent-requests.store :as agent-store]
            [de.explorama.shared.data-format.graph :as graph]
            [taoensso.timbre :refer [debug]]))

(def request-type :indicator/aggregation-graph)

(def ^:private format-doc
  (str "A graph is one edn map {:nodes {..} :edges {..}}. Node types: "
       ":datasource (requires :dataset <n> referencing the numbered input datasets), "
       ":operation (requires :op, optional :params), :result (requires :name, exactly one, "
       "the only sink). Edges are {[from to] {:direction :-> | :<- (default :->), "
       ":order <1..n on all in-edges of order-sensitive multi-input ops (:- :/ :difference) "
       "and of the result node>, :as <output attribute name, only on result in-edges, "
       "required when the result has several>}}. The graph must be a connected dag. "
       "Never use :heal-event. Operations, their parameters and their shape transitions "
       "are listed in the input under :operations; datasource nodes start as "
       ":meta-list-events and every branch entering :result must end as "
       ":meta-group-values or :meta-group-list-values (aggregate after :group-by)."))

(def ^:private description
  (str "Produce an aggregation graph for the Explorama indicator plugin from the "
       "user's prompt. The input holds the prompt, the connected datasets with their "
       "attributes, the available operations with metadata, and the format "
       "documentation. Answer with a single edn map satisfying the output schema. "
       format-doc))

(def ^:private example
  {:nodes {:src {:type :datasource :dataset 1}
           :grouped {:type :operation :op :group-by :params {:attributes ["year" "country"]}}
           :total {:type :operation :op :sum :params {:attribute "fatalities"}}
           :out {:type :result :name "fatalities-per-year"}}
   :edges {[:src :grouped] {:direction :->}
           [:grouped :total] {:direction :->}
           [:total :out] {:direction :-> :as "indicator"}}})

(defn input [prompt datasets]
  {:prompt prompt
   :datasets datasets
   :operations (graph/operation-metadata)
   :format-doc format-doc})

(defn- on-fulfilled [{{:keys [client-callback]} :context} result]
  (when client-callback
    (client-callback result)))

(defn register! []
  (registry/register-type! {:id request-type
                            :description description
                            :output-schema graph/graph-schema
                            :output-example example
                            :on-fulfilled on-fulfilled}))

(defn request-generation [{:keys [client-callback failed-callback]}
                          [user-info prompt datasets correlation-id]]
  (if-let [{:keys [id]} (agent-store/create!
                         {:type request-type
                          :input (input prompt datasets)
                          :user (:username user-info)
                          :context {:client-callback
                                    (fn [result]
                                      (client-callback {:graph result :id correlation-id}))
                                    :failed-callback
                                    (fn [result]
                                      (failed-callback (assoc result :id correlation-id)))}})]
    (debug "Aggregation graph request filed" {:id id})
    (failed-callback {:error "unknown user" :id correlation-id})))
