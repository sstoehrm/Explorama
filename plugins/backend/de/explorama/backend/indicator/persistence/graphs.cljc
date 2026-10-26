(ns de.explorama.backend.indicator.persistence.graphs
  (:require [de.explorama.backend.indicator.persistence.graph-store :as store]
            [de.explorama.backend.indicator.data.core :as data]
            [de.explorama.backend.common.middleware.cache-invalidate :as cache-invalidate]
            [de.explorama.shared.data-format.graph :as graph]
            [de.explorama.shared.common.unification.misc :refer [cljc-uuid]]
            [malli.core :as m]
            [malli.error :as me]
            [taoensso.timbre :refer [error]]))

(def graph-desc-spec
  [:map
   [:id [:string {:min 1 :max 255}]]
   [:name [:string {:min 1 :max 255}]]
   [:creator [:string {:min 1}]]
   [:shared-by {:optional true} [:string {:min 1}]]
   [:description {:optional true} [:string {:min 1 :max 255}]]
   [:graph-text [:string {:min 1}]]
   [:dis map?]
   [:calculation-desc vector?]
   [:graph-filters {:optional true} map?]
   [:dataset-bindings {:optional true} map?]])

(defn- recompile-artifact
  "Re-parses and re-compiles the submitted `:graph-text` against the
   submitted `:dataset-bindings`. Returns `{:status :failed ...}` when the
   artifact or the graph text does not validate, otherwise
   `{:calculation-desc [..] :graph-filters {..}}` - the server-authoritative
   replacement for whatever the client submitted under those keys."
  [{:keys [graph-text dis dataset-bindings] :as artifact}]
  (cond
    (not (m/validate graph-desc-spec artifact))
    {:status :failed
     :msg :graph-desc-not-valid
     :data {:reason (me/humanize (m/explain graph-desc-spec artifact))}}

    (or (not (map? dataset-bindings))
        (not (every? #(contains? dis %) (vals dataset-bindings))))
    {:status :failed
     :msg :graph-dataset-bindings-not-valid
     :data {:reason "dataset-bindings is required and must reference connected :dis"}}

    :else
    (let [{:keys [ok error]} (graph/parse graph-text)]
      (if error
        {:status :failed :msg :graph-not-parseable :data {:reason error}}
        (let [{:keys [errors]} (graph/validate ok (count dataset-bindings))]
          (if (seq errors)
            {:status :failed :msg :graph-not-valid :data {:reason errors}}
            (try
              (let [{:keys [calculation-desc filters]} (graph/compile-graph ok dataset-bindings)]
                {:calculation-desc calculation-desc :graph-filters filters})
              (catch #?(:clj Exception :cljs :default) e
                {:status :failed :msg :graph-not-compilable :data {:reason (ex-message e)}}))))))))

(defn- validation-failure [artifact]
  (let [{:keys [status] :as result} (recompile-artifact artifact)]
    (when (= status :failed) result)))

(defn- inform-caches [dirty-tile]
  (cache-invalidate/send-invalidate #{"transparent-data"}
                                    {"identifier" #{"indicator"}
                                     "description" #{(get dirty-tile "description")}}))

(defn- check-access [{:keys [username]} graph-id]
  (= username (:creator (store/read-graph graph-id))))

(defn all-user-graphs [user]
  (mapv #(assoc % :write-access? true)
        (store/list-all-user-graphs user)))

(defn read-graph [id] (store/read-graph id))

(defn create-new-graph [_user artifact]
  (let [{:keys [status] :as compiled} (recompile-artifact artifact)]
    (if (= status :failed)
      compiled
      {:status :success :data (store/write-graph (merge artifact compiled))})))

(defn update-graph [user {:keys [id] :as artifact}]
  (let [{:keys [status] :as compiled} (recompile-artifact artifact)]
    (cond
      (= status :failed) compiled
      (not (check-access user id))
      (do (error "User has no rights to update aggregation graph" {:id id :user user})
          {:status :failed :msg :no-rights-update-infos :data {:id id :user user}})
      :else
      (let [final-artifact (merge artifact compiled)
            original (store/read-graph id)]
        (if (not= original final-artifact)
          (do (inform-caches (data/data-tile-desc original))
              {:status :success :data (store/write-graph final-artifact)})
          {:status :info :msg :nothing-changed :data {:id id}})))))

(defn share-with-user [current-user share-with {id :id :as artifact}]
  (let [failure (validation-failure artifact)]
    (cond
      failure failure
      (not (check-access current-user id))
      {:status :failed :msg :sharing-failed-with-user :data {:user current-user :id id}}
      :else
      (let [copy (assoc (store/read-graph id)
                         :id (cljc-uuid)
                         :creator (:username share-with)
                         :shared-by (:username current-user))]
        {:status :success :data (store/write-graph copy)}))))

(defn delete-graph [user {:keys [id]}]
  (if (check-access user id)
    {:status :success :data (store/delete-graph id)}
    (do (error "The user has no write-access to delete the aggregation graph" {:id id :user user})
        {:status :failed :msg :no-rights-to-delete :data {:id id :user user}})))
