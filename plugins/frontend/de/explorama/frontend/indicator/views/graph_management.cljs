(ns de.explorama.frontend.indicator.views.graph-management
  (:require [cljs.pprint :as pprint]
            [de.explorama.frontend.common.frontend-interface :as fi]
            [de.explorama.frontend.indicator.path :as ip]
            [de.explorama.shared.data-format.graph :as graph]
            [de.explorama.shared.indicator.ws-api :as ws-api]
            [re-frame.core :as re-frame]))

(def ^:private agent-timeout-ms 900000)

(def ^:private starter-graph-text
  "{:nodes {:data-1 {:type :datasource :dataset 1}\n         :out {:type :result :name \"my-aggregation\"}}\n :edges {[:data-1 :out] {}}}")

(defn- graph-meta-path [graph-id]
  (conj (ip/graph-editor-state graph-id) :meta))

(defn- agent-pending-path [graph-id]
  (conj (ip/graph-agent graph-id) :pending?))

(defn- agent-corr-path [graph-id]
  (conj (ip/graph-agent graph-id) :correlation-id))

(defn- current-agent-request? [db graph-id corr-id]
  (= corr-id (get-in db (agent-corr-path graph-id))))

(defn dataset-bindings [db graph-id]
  (into {}
        (map-indexed (fn [i di-id] [(inc i) di-id]))
        (keys (get-in db (ip/indicator-data graph-id)))))

(defn validate-graph-state [db graph-id]
  (let [text (get-in db (ip/graph-text graph-id))
        {:keys [ok error]} (graph/parse text)]
    (if error
      (assoc-in db (conj (ip/graph-validation graph-id) :parse-error) (:message error))
      (let [{:keys [errors warnings]} (graph/validate ok (count (dataset-bindings db graph-id)))]
        (assoc-in db (ip/graph-validation graph-id)
                  {:parse-error nil :errors errors :warnings warnings :parsed ok})))))

(defn graph-artifact->final [db graph-id]
  (let [{:keys [parse-error errors parsed]} (get-in db (ip/graph-validation graph-id))
        bindings (dataset-bindings db graph-id)]
    (if (or parse-error (seq errors))
      {:errors (or errors [{:code :parse-error :message parse-error}])}
      (let [{:keys [calculation-desc filters]} (graph/compile-graph parsed bindings)
            base (merge (get-in db (graph-meta-path graph-id))
                        (get-in db (ip/graph-desc graph-id)))]
        {:artifact (assoc base
                          :graph-text (get-in db (ip/graph-text graph-id))
                          :dis (get-in db (ip/indicator-data graph-id))
                          :graph-filters filters
                          :calculation-desc calculation-desc)}))))

(defn text-dirty? [db graph-id]
  (not= (get-in db (ip/graph-text graph-id))
        (get-in db (conj (ip/graph-desc graph-id) :graph-text))))

(defn- unique-graph-name [count graphs]
  (loop [count count
         unique? (not (some #(= (str "Aggregation " count) (:name %)) graphs))]
    (if unique?
      (str "Aggregation " count)
      (recur (inc count)
             (not (some #(= (str "Aggregation " (inc count)) (:name %)) graphs))))))

(defn- request-datasets [db graph-id]
  (->> (dataset-bindings db graph-id)
       (sort-by key)
       (mapv (fn [[n di-id]]
               {:dataset n
                :attributes (get-in db (conj (ip/indicator-dataset graph-id di-id) :ui-options))}))))

(defn- store-graph-artifact [db artifact]
  (assoc-in db (ip/graph-desc (:id artifact)) artifact))

(re-frame/reg-event-fx
 ::set-graph-text
 (fn [{db :db} [_ graph-id text]]
   {:db (assoc-in db (ip/graph-text graph-id) text)
    :dispatch-later [{:ms 300 :dispatch [::validate-now graph-id text]}]}))

(re-frame/reg-event-db
 ::validate-now
 (fn [db [_ graph-id text]]
   (if (= text (get-in db (ip/graph-text graph-id)))
     (validate-graph-state db graph-id)
     db)))

(re-frame/reg-event-fx
 ::change-active-graph
 (fn [{db :db} [_ graph-id]]
   (if (nil? graph-id)
     {:db (assoc-in db ip/active-indicator nil)}
     (let [seeded? (some? (get-in db (ip/graph-text graph-id)))
           persisted-text (get-in db (conj (ip/graph-desc graph-id) :graph-text))
           db (assoc-in db ip/active-indicator {:id graph-id :kind :graph})]
       {:db (if (or seeded? (nil? persisted-text))
              db
              (validate-graph-state (assoc-in db (ip/graph-text graph-id) persisted-text) graph-id))}))))

(re-frame/reg-event-fx
 ::create-new-graph-artifact
 (fn [{db :db} _]
   (let [{:keys [username]} (fi/call-api :user-info-db-get db)
         graphs (vals (get-in db ip/graphs))
         new-id (str (random-uuid))
         graph-name (unique-graph-name (inc (count graphs)) graphs)]
     {:db (-> db
              (assoc-in (graph-meta-path new-id)
                        {:id new-id
                         :name graph-name
                         :creator username
                         :write-access? true})
              (assoc-in (ip/graph-text new-id) starter-graph-text))
      :dispatch [::change-active-graph new-id]})))

(re-frame/reg-event-fx
 ::save-graph
 (fn [{db :db} [_ graph-id]]
   (let [user-info (fi/call-api :user-info-db-get db)
         is-new? (not (contains? (get-in db ip/graphs) graph-id))
         {:keys [artifact errors]} (graph-artifact->final db graph-id)]
     (when-not errors
       {:backend-tube [(if is-new? ws-api/create-new-graph ws-api/update-graph)
                       {:client-callback [(if is-new?
                                            ws-api/create-new-graph-result
                                            ws-api/update-graph-result)
                                          artifact]}
                       user-info
                       artifact]}))))

(re-frame/reg-event-db
 ws-api/create-new-graph-result
 (fn [db [_ artifact {:keys [status]}]]
   (cond-> db
     (= status :success) (store-graph-artifact artifact))))

(re-frame/reg-event-db
 ws-api/update-graph-result
 (fn [db [_ artifact {:keys [status]}]]
   (cond-> db
     (= status :success) (store-graph-artifact artifact))))

(re-frame/reg-event-fx
 ::delete-graph
 (fn [{db :db} [_ graph-id]]
   (let [user-info (fi/call-api :user-info-db-get db)
         is-new? (not (contains? (get-in db ip/graphs) graph-id))]
     (if is-new?
       {:db (update-in db ip/graph-editor-states dissoc graph-id)
        :dispatch [::change-active-graph nil]}
       {:backend-tube [ws-api/delete-graph
                       {:client-callback [ws-api/delete-graph-result]}
                       user-info {:id graph-id}]}))))

(re-frame/reg-event-fx
 ws-api/delete-graph-result
 (fn [{db :db} [_ {:keys [status data]}]]
   (when (= status :success)
     {:db (-> db
              (update-in ip/graphs dissoc data)
              (update-in ip/graph-editor-states dissoc data))
      :dispatch [::change-active-graph nil]})))

(re-frame/reg-event-db
 ws-api/all-graphs-result
 (fn [db [_ graphs]]
   (assoc-in db ip/graphs (into {} (map (fn [g] [(:id g) g])) graphs))))

(re-frame/reg-event-fx
 ::request-generation
 (fn [{db :db} [_ graph-id prompt]]
   (let [user-info (fi/call-api :user-info-db-get db)
         corr-id (str (random-uuid))
         datasets (request-datasets db graph-id)]
     {:db (-> db
              (assoc-in (agent-pending-path graph-id) true)
              (assoc-in (agent-corr-path graph-id) corr-id)
              (update-in (ip/graph-proposal graph-id) dissoc :error))
      :backend-tube [ws-api/request-graph-generation
                     {:client-callback [ws-api/graph-generation-result graph-id]
                      :failed-callback [ws-api/graph-generation-failed graph-id]}
                     user-info prompt datasets corr-id]
      :dispatch-later [{:ms agent-timeout-ms
                        :dispatch [::generation-timeout graph-id corr-id]}]})))

(re-frame/reg-event-db
 ws-api/graph-generation-result
 (fn [db [_ graph-id {:keys [graph id]}]]
   (if (current-agent-request? db graph-id id)
     (let [bindings (dataset-bindings db graph-id)]
       (-> db
           (assoc-in (ip/graph-proposal graph-id)
                     {:graph graph
                      :text (with-out-str (pprint/pprint graph))
                      :validation (graph/validate graph (count bindings))})
           (assoc-in (agent-pending-path graph-id) false)
           (assoc-in (agent-corr-path graph-id) nil)))
     db)))

(re-frame/reg-event-db
 ws-api/graph-generation-failed
 (fn [db [_ graph-id {:keys [id error]}]]
   (if (current-agent-request? db graph-id id)
     (-> db
         (assoc-in (agent-pending-path graph-id) false)
         (assoc-in (agent-corr-path graph-id) nil)
         (assoc-in (conj (ip/graph-proposal graph-id) :error) error))
     db)))

(re-frame/reg-event-db
 ::generation-timeout
 (fn [db [_ graph-id corr-id]]
   (if (current-agent-request? db graph-id corr-id)
     (-> db
         (assoc-in (agent-pending-path graph-id) false)
         (assoc-in (agent-corr-path graph-id) nil)
         (assoc-in (conj (ip/graph-proposal graph-id) :error) :timeout))
     db)))

(re-frame/reg-event-db
 ::apply-proposal
 (fn [db [_ graph-id]]
   (let [{:keys [text]} (get-in db (ip/graph-proposal graph-id))]
     (-> db
         (assoc-in (ip/graph-text graph-id) text)
         (validate-graph-state graph-id)
         (update-in (ip/graph-editor-state graph-id) dissoc :proposal)))))

(re-frame/reg-event-db
 ::dismiss-proposal
 (fn [db [_ graph-id]]
   (update-in db (ip/graph-editor-state graph-id) dissoc :proposal)))

(re-frame/reg-sub
 ::all-graphs
 (fn [db _]
   (->> (get-in db ip/graphs)
        vals
        (sort-by :name)
        vec)))

(re-frame/reg-sub
 ::active-graph-id
 (fn [db _]
   (let [{:keys [id kind]} (get-in db ip/active-indicator)]
     (when (= kind :graph) id))))

(re-frame/reg-sub
 ::graph-text
 (fn [db [_ graph-id]]
   (get-in db (ip/graph-text graph-id))))

(re-frame/reg-sub
 ::text-dirty?
 (fn [db [_ graph-id]]
   (text-dirty? db graph-id)))

(re-frame/reg-sub
 ::validation
 (fn [db [_ graph-id]]
   (select-keys (get-in db (ip/graph-validation graph-id)) [:parse-error :errors :warnings])))

(re-frame/reg-sub
 ::valid?
 (fn [db [_ graph-id]]
   (let [{:keys [parse-error errors]} (get-in db (ip/graph-validation graph-id))]
     (and (nil? parse-error) (empty? errors)))))

(re-frame/reg-sub
 ::proposal
 (fn [db [_ graph-id]]
   (get-in db (ip/graph-proposal graph-id))))

(re-frame/reg-sub
 ::agent-pending?
 (fn [db [_ graph-id]]
   (get-in db (agent-pending-path graph-id) false)))

(re-frame/reg-sub
 ::operation-reference
 (fn [_ _]
   (graph/operation-metadata)))
