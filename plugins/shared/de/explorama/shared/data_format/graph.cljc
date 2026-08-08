(ns de.explorama.shared.data-format.graph
  (:require #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.set :as set]
            [malli.core :as m]
            [malli.error :as me]
            [de.explorama.shared.data-format.operations :as op]
            [de.explorama.shared.data-format.data-instance :as di]))

(def ^:private node-id [:or :keyword :string])

(def graph-schema
  [:map {:closed true}
   [:nodes [:map-of node-id
            [:multi {:dispatch :type}
             [:datasource [:map {:closed true}
                           [:type [:= :datasource]]
                           [:dataset pos-int?]
                           [:name {:optional true} :string]]]
             [:operation [:map {:closed true}
                          [:type [:= :operation]]
                          [:op :keyword]
                          [:params {:optional true} map?]
                          [:name {:optional true} :string]]]
             [:result [:map {:closed true}
                       [:type [:= :result]]
                       [:name [:string {:min 1}]]]]]]]
   [:edges [:map-of [:tuple node-id node-id]
            [:map {:closed true}
             [:direction {:optional true} [:enum :-> :<-]]
             [:order {:optional true} pos-int?]
             [:as {:optional true} [:string {:min 1}]]]]]])

(defn explain-schema [graph]
  (some-> (m/explain graph-schema graph) me/humanize))

(defn parse [s]
  (try
    {:ok (edn/read-string s)}
    (catch #?(:clj Exception :cljs :default) e
      {:error {:message (ex-message e)}})))

(defn normalized-edges [{:keys [edges]}]
  (reduce (fn [{:keys [edges] :as acc} [[a b] {:keys [direction] :as attrs}]]
            (let [attrs (dissoc attrs :direction)
                  [from to] (if (= :<- direction) [b a] [a b])]
              (cond
                (and direction (not (#{:-> :<-} direction)))
                (update acc :errors conj {:code :bad-direction
                                          :edge [a b]
                                          :message "edge :direction must be :-> or :<-"})
                (or (contains? edges [from to])
                    (contains? edges [to from]))
                (update acc :errors conj {:code :duplicate-connection
                                          :edge [a b]
                                          :message "the same connection appears twice"})
                :else
                (assoc-in acc [:edges [from to]] attrs))))
          {:edges {} :errors []}
          edges))

(def order-sensitive-ops #{:- :/ :difference})

(def ^:private excluded-ops #{:heal-event})
(def ^:private curated-internal-ops #{:distinct :normalize :filter})

(def ^:private aggregation-input->output
  {:dependent [[[:join? true :join-fully? false]]
               {:meta-sub-group-list-events :meta-group-values
                :meta-group-list-events :meta-primitive-value
                :meta-group-values :meta-primitive-value
                :meta-list-events :meta-primitive-value}
               [[:join? true] [:join-fully? true]]
               {:meta-sub-group-list-events :meta-primitive-value
                :meta-group-list-events :meta-primitive-value
                :meta-group-values :meta-primitive-value
                :meta-list-events :meta-primitive-value}]
   :default {:meta-group-list-events :meta-group-values
             :meta-group-values :meta-group-values
             :meta-list-events :meta-primitive-value}})

(def ^:private supplemental-steering
  {:distinct {:arguments 1
              :attributes {:attribute {:type :select :values :ac-contexts}
                           :join? {:type :boolean :default false :optional true}
                           :join-fully? {:type :boolean :default false :optional true}}
              :input->output aggregation-input->output}
   :normalize {:arguments 1
               :attributes {:attribute {:type :select :values :ac-numbers}
                            :range-min {:type :number :optional true}
                            :range-max {:type :number :optional true}
                            :all-data? {:type :boolean :default true :optional true}
                            :result-name {:type :custom :optional true}}
               :input->output {:default {:meta-group-list-events :meta-group-list-events}}}
   :filter {:arguments 1
            :attributes {:filter {:type :custom}}
            :input->output {:default {:meta-list-events :meta-list-events}}}})

(defn- op-meta [k] (meta (get op/functions k)))

(defn allowed-operations []
  (into #{}
        (filter (fn [k]
                  (let [{:keys [category internal interal]} (op-meta k)]
                    (and (not (excluded-ops k))
                         (or (curated-internal-ops k)
                             (and category (not internal) (not interal)))))))
        (keys op/functions)))

(defn operation-metadata []
  (into {}
        (map (fn [k]
               (let [{:keys [key category description steering]} (op-meta k)
                     steering (merge steering (get supplemental-steering k))]
                 [k {:key key
                     :category category
                     :description description
                     :arguments (:arguments steering)
                     :attributes (:attributes steering)
                     :input->output (:input->output steering)}])))
        (allowed-operations)))

(defn- in-edges [edges node]
  (into {} (filter (fn [[[_ to] _]] (= to node))) edges))

(defn- out-edges [edges node]
  (into {} (filter (fn [[[from _] _]] (= from node))) edges))

(defn- topo-sort [nodes edges]
  (loop [order [] remaining (set (keys nodes))]
    (if (empty? remaining)
      {:order order}
      (let [ready (into #{}
                        (filter (fn [n]
                                  (every? (complement remaining)
                                          (map ffirst (in-edges edges n)))))
                        remaining)]
        (if (empty? ready)
          {:cycle remaining}
          (recur (into order (sort-by str ready))
                 (reduce disj remaining ready)))))))

(defn- reachable-from [edges starts]
  (loop [visited (set starts) frontier (vec starts)]
    (if (empty? frontier)
      visited
      (let [n (peek frontier)
            frontier (pop frontier)
            tos (map (fn [[[_ to] _]] to) (out-edges edges n))
            new-tos (remove visited tos)]
        (recur (into visited new-tos) (into frontier new-tos))))))

(defn- node-type [nodes nid] (:type (get nodes nid)))

(defn- classification-errors [nodes edges]
  (let [ids (keys nodes)
        sinks (filter #(empty? (out-edges edges %)) ids)
        datasources (filter #(= :datasource (node-type nodes %)) ids)
        results (filter #(= :result (node-type nodes %)) ids)
        reachable (reachable-from edges datasources)]
    (vec
     (concat
      (when (> (count sinks) 1)
        [{:code :multiple-sinks :message "graph must have exactly one sink"}])
      (when (or (empty? results)
                (and (= (count sinks) 1)
                     (not= :result (node-type nodes (first sinks)))))
        [{:code :no-result :message "graph has no result node"}])
      (for [nid results :when (seq (out-edges edges nid))]
        {:code :multiple-sinks :node nid :message "result must be the only sink"})
      (for [nid datasources :when (seq (in-edges edges nid))]
        {:code :datasource-has-input :node nid :message "datasource node cannot have an incoming edge"})
      (for [nid datasources :when (empty? (out-edges edges nid))]
        {:code :dangling-datasource :node nid :message "datasource is not connected to any operation"})
      (for [nid ids
            :when (and (not= :datasource (node-type nodes nid))
                       (not (reachable nid)))]
        {:code :disconnected :node nid :message "node is not reachable from any datasource"})))))

(defn- dataset-check [nodes n-datasets]
  (let [refs (into {}
                   (keep (fn [[nid node]]
                           (when (= :datasource (:type node))
                             [nid (:dataset node)])))
                   nodes)
        errors (for [[nid ref] refs
                     :when (not (<= 1 ref n-datasets))]
                 {:code :unbound-dataset :node nid
                  :message (str "dataset " ref " is not among the " n-datasets " connected datasets")})
        used (set (vals refs))
        unused (set/difference (set (range 1 (inc n-datasets))) used)
        warnings (for [ref (sort unused)]
                   {:code :unused-dataset :message (str "dataset " ref " is never used")})]
    {:errors (vec errors) :warnings (vec warnings)}))

(defn- operation-errors [nodes edges]
  (let [ops (allowed-operations)
        metadata (operation-metadata)]
    (vec
     (mapcat
      (fn [[nid node]]
        (when (= :operation (:type node))
          (let [op (:op node)
                in-count (count (in-edges edges nid))]
            (if (not (ops op))
              [{:code :unknown-op :node nid :message (str "unknown operation " op)}]
              (let [{:keys [arguments attributes]} (get metadata op)
                    unknown-params (set/difference (set (keys (:params node)))
                                                    (set (keys attributes)))
                    arity-ok? (if (= 1 arguments) (= 1 in-count) (>= in-count 2))]
                (concat
                 (for [k unknown-params]
                   {:code :unknown-param :node nid :message (str "unknown parameter " k)})
                 (when-not arity-ok?
                   [{:code :arity-mismatch :node nid
                     :message (str "operation " op " received " in-count " input(s)")}])))))))
      nodes))))

(defn- order-sensitive-target? [nodes edges nid]
  (let [node (get nodes nid)
        in-count (count (in-edges edges nid))]
    (and (>= in-count 2)
         (or (and (= :operation (:type node)) (order-sensitive-ops (:op node)))
             (= :result (:type node))))))

(defn- order-check-errors [edges nid]
  (let [ins (in-edges edges nid)
        in-count (count ins)
        orders (map :order (vals ins))]
    (cond
      (some nil? orders)
      [{:code :order-missing :node nid
        :message "order-sensitive operation requires :order on every input edge"}]
      (not= (set orders) (set (range 1 (inc in-count))))
      [{:code :order-invalid :node nid
        :message ":order values must form a 1..n permutation"}]
      :else [])))

(defn- order-and-as-checks [nodes edges]
  (let [order-sensitive-nodes (into #{}
                                     (filter #(order-sensitive-target? nodes edges %))
                                     (keys nodes))
        order-errors (mapcat #(order-check-errors edges %) order-sensitive-nodes)
        ignored-warnings (for [[[from to] attrs] edges
                               :when (and (:order attrs) (not (order-sensitive-nodes to)))]
                           {:code :ignored-order :edge [from to] :message ":order is ignored on this edge"})
        as-errors (mapcat
                   (fn [nid]
                     (when (and (= :result (node-type nodes nid))
                                (>= (count (in-edges edges nid)) 2))
                       (for [[[from _] attrs] (in-edges edges nid) :when (not (:as attrs))]
                         {:code :as-missing :edge [from nid]
                          :message "each branch into a result node needs :as"})))
                   (keys nodes))]
    {:errors (vec (concat order-errors as-errors))
     :warnings (vec ignored-warnings)}))

(def ^:private heal-value-shapes #{:meta-group-values :meta-group-list-values})

(defn result-policy [branch-shapes]
  (cond
    (and (seq branch-shapes) (every? heal-value-shapes branch-shapes)) :merge
    (= [:meta-group-list-events] (vec branch-shapes)) :vals))

(defn- params-with-defaults [op-md params]
  (reduce (fn [acc [k {:keys [default]}]]
            (if (contains? acc k) acc (assoc acc k default)))
          (or params {})
          (:attributes op-md)))

(defn- dependent-match? [clauses params]
  (every? (fn [pairs]
            (every? (fn [[k v]] (= v (get params k)))
                    (partition 2 pairs)))
          clauses))

(defn- transition [op-md params in-shape]
  (let [{:keys [dependent default]} (:input->output op-md)
        params (params-with-defaults op-md params)
        dep-table (some (fn [[clauses table]]
                          (when (dependent-match? clauses params) table))
                        (partition 2 dependent))]
    (get (or dep-table default) in-shape)))

(defn- ordered-input-shapes [edges shapes nid]
  (let [ordered (sort-by (fn [[[from _] attrs]] [(or (:order attrs) ##Inf) (str from)])
                         (in-edges edges nid))]
    (mapv (fn [[[from _] _]] (get shapes from)) ordered)))

(defn- infer-operation-shape [metadata edges shapes errors nid node]
  (let [in-shapes (ordered-input-shapes edges shapes nid)]
    (cond
      (some nil? in-shapes)
      [shapes errors]

      (< 1 (count (distinct in-shapes)))
      [shapes (conj errors {:code :shape-heterogeneous :node nid
                            :message "inputs to this operation have different shapes"})]

      :else
      (let [op-md (get metadata (:op node))
            out (transition op-md (:params node) (first in-shapes))]
        (if out
          [(assoc shapes nid out) errors]
          [shapes (conj errors {:code :shape-mismatch :node nid
                                :message (str (:op node) " cannot consume " (first in-shapes))})])))))

(defn- infer-result-shape [edges shapes errors nid]
  (let [in-shapes (ordered-input-shapes edges shapes nid)]
    (if (or (some nil? in-shapes) (result-policy in-shapes))
      [shapes errors]
      [shapes (conj errors {:code :result-shape :node nid
                            :message "result branches cannot be combined into a single shape"})])))

(defn- infer-shapes [nodes edges order]
  (let [metadata (operation-metadata)]
    (reduce
     (fn [[shapes errors] nid]
       (let [node (get nodes nid)]
         (case (:type node)
           :datasource [(assoc shapes nid :meta-list-events) errors]
           :operation (infer-operation-shape metadata edges shapes errors nid node)
           :result (infer-result-shape edges shapes errors nid))))
     [{} []]
     order)))

(defn validate [graph n-datasets]
  (if-let [explanation (explain-schema graph)]
    {:errors [{:code :schema :message (pr-str explanation)}] :warnings [] :shapes {}}
    (let [{:keys [nodes]} graph
          {ne-edges :edges ne-errors :errors} (normalized-edges graph)
          unknown-ref-errors (for [[[from to] _] ne-edges
                                    :when (or (not (contains? nodes from))
                                              (not (contains? nodes to)))]
                                {:code :unknown-node-ref :edge [from to]
                                 :message "edge refers to a node id that is not defined"})
          topo (topo-sort nodes ne-edges)]
      (if-let [cycle (:cycle topo)]
        {:errors (vec (concat ne-errors unknown-ref-errors
                               [{:code :cycle :node (vec cycle) :message "graph contains a cycle"}]))
         :warnings []
         :shapes {}}
        (let [{ds-errors :errors ds-warnings :warnings} (dataset-check nodes n-datasets)
              {order-errors :errors order-warnings :warnings} (order-and-as-checks nodes ne-edges)
              errors (vec (concat ne-errors unknown-ref-errors
                                   (classification-errors nodes ne-edges)
                                   ds-errors
                                   (operation-errors nodes ne-edges)
                                   order-errors))
              [shapes shape-errors] (if (empty? errors)
                                      (infer-shapes nodes ne-edges (:order topo))
                                      [{} []])]
          {:errors (vec (concat errors shape-errors))
           :warnings (vec (concat ds-warnings order-warnings))
           :shapes shapes})))))

(defn- branch-edges [edges node]
  (->> (in-edges edges node)
       (sort-by (fn [[_ {:keys [order]}]] (or order 1)))))

(defn compile-graph [graph bindings]
  (let [{:keys [errors shapes]} (validate graph (count bindings))
        _ (when (seq errors)
            (throw (ex-info "graph does not validate" {:errors errors})))
        {edges :edges} (normalized-edges graph)
        nodes (:nodes graph)
        filters (atom {})
        md (operation-metadata)
        compile-node
        (fn compile-node [id]
          (let [{:keys [type op params dataset]} (get nodes id)]
            (case type
              :datasource (get bindings dataset)
              :operation
              (let [inputs (mapv (comp compile-node ffirst) (branch-edges edges id))]
                (if (= :filter op)
                  (let [form (:filter params)
                        fid (di/ctn->sha256-id form)]
                    (swap! filters assoc fid form)
                    (into [:filter fid] inputs))
                  (into [op (not-empty (or params {}))] inputs))))))
        result-id (some (fn [[id n]] (when (= :result (:type n)) id)) nodes)
        branches (branch-edges edges result-id)
        branch-ids (mapv ffirst branches)
        as-names (if (= 1 (count branches))
                   [(or (:as (second (first branches))) "indicator")]
                   (mapv (comp :as second) branches))
        numeric? (fn [id]
                   (contains? #{:aggregation :nummerical}
                              (get-in md [(get-in nodes [id :op]) :category])))
        heal-params
        {:policy (result-policy (mapv shapes branch-ids))
         :descs (mapv (fn [as] {:attribute as}) as-names)
         :addons [{:attribute "datasource" :value (get-in nodes [result-id :name])}
                  {:attribute "indicator-type" :value "aggregation-graph"}]
         :generate-ids {:policy :uuid}
         :workaround {"date" {:month "01" :day "01"}}
         :force-type (into []
                           (comp (filter (fn [[id _]] (numeric? id)))
                                 (map (fn [[_ as]] {:attribute as :new-type :double})))
                           (map vector branch-ids as-names))}]
    {:calculation-desc (into [:heal-event heal-params] (mapv compile-node branch-ids))
     :filters @filters}))
