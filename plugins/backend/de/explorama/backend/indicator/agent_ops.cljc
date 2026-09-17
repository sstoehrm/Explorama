(ns de.explorama.backend.indicator.agent-ops
  (:require [de.explorama.backend.agent-gateway.dispatcher :as dispatcher]
            [de.explorama.backend.indicator.calculate :as calc]
            [de.explorama.backend.indicator.persistence.graphs :as graphs]
            [de.explorama.shared.data-format.graph :as graph]))

(def ^:private no-rights-msgs #{:no-rights-to-delete :no-rights-update-infos})

(defn- checked [{:keys [status data msg] :as outcome}]
  (if (= :success status)
    data
    (throw (ex-info "the graph did not validate"
                    (assoc (dissoc outcome :status)
                           :gateway-error (if (contains? no-rights-msgs msg) :unauthorized :invalid-params))))))

(defn- validate-graph [{:keys [params]}]
  (let [{:keys [graph-text dataset-count]} params
        {:keys [ok error]} (graph/parse graph-text)]
    (if error
      (throw (ex-info (str "graph text is not readable edn: " (:message error)) {:gateway-error :invalid-params}))
      (assoc (graph/validate ok dataset-count) :operations (graph/operation-metadata)))))

(defn- existing-graph [id]
  (or (graphs/read-graph id)
      (throw (ex-info "unknown graph id" {:gateway-error :invalid-params :id id}))))

(defn register! []
  (dispatcher/register-op! :indicator/graphs (fn [{:keys [user]}] (graphs/all-user-graphs (dispatcher/user-info user))))
  (dispatcher/register-op! :indicator/graph (fn [{:keys [params]}] (existing-graph (:id params))))
  (dispatcher/register-op! :indicator/validate-graph validate-graph)
  (dispatcher/register-op! :indicator/create-graph
                           (fn [{:keys [user params]}] (checked (graphs/create-new-graph (dispatcher/user-info user) (:artifact params)))))
  (dispatcher/register-op! :indicator/update-graph
                           (fn [{:keys [user params]}] (checked (graphs/update-graph (dispatcher/user-info user) (:artifact params)))))
  (dispatcher/register-op! :indicator/delete-graph
                           (fn [{:keys [user params]}]
                             (checked (graphs/delete-graph (dispatcher/user-info user) {:id (:id params)}))
                             (graphs/all-user-graphs (dispatcher/user-info user))))
  (dispatcher/register-op! :indicator/publish-graph
                           (fn [{:keys [params]}]
                             (existing-graph (:id params))
                             (first (dispatcher/call-route calc/create-graph-di-and-acs [(:id params) false])))))
