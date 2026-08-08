(ns de.explorama.shared.data-format.graph
  (:require #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [malli.core :as m]
            [malli.error :as me]
            [de.explorama.shared.data-format.operations :as op]))

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
