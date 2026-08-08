(ns de.explorama.shared.data-format.graph
  (:require #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [malli.core :as m]
            [malli.error :as me]))

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
