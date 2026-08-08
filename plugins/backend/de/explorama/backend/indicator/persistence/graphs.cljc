(ns de.explorama.backend.indicator.persistence.graphs
  (:require [de.explorama.backend.indicator.persistence.graph-store :as store]
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
   [:graph-filters {:optional true} map?]])

(defn- validate-artifact [{:keys [graph-text dis] :as artifact}]
  (or (when-not (m/validate graph-desc-spec artifact)
        {:status :failed
         :msg :graph-desc-not-valid
         :data {:reason (me/humanize (m/explain graph-desc-spec artifact))}})
      (let [{:keys [ok error]} (graph/parse graph-text)]
        (if error
          {:status :failed :msg :graph-not-parseable :data {:reason error}}
          (let [{:keys [errors]} (graph/validate ok (count dis))]
            (when (seq errors)
              {:status :failed :msg :graph-not-valid :data {:reason errors}}))))))

(defn- check-access [{:keys [username]} graph-id]
  (= username (:creator (store/read-graph graph-id))))

(defn all-user-graphs [user]
  (mapv #(assoc % :write-access? true)
        (store/list-all-user-graphs user)))

(defn read-graph [id] (store/read-graph id))

(defn create-new-graph [_user artifact]
  (if-let [failure (validate-artifact artifact)]
    failure
    {:status :success :data (store/write-graph artifact)}))

(defn update-graph [user {:keys [id] :as artifact}]
  (let [failure (validate-artifact artifact)]
    (cond
      failure failure
      (not (check-access user id))
      (do (error "User has no rights to update aggregation graph" {:id id :user user})
          {:status :failed :msg :no-rights-update-infos :data {:id id :user user}})
      (not= (store/read-graph id) artifact)
      {:status :success :data (store/write-graph artifact)}
      :else {:status :info :msg :nothing-changed :data {:id id}})))

(defn share-with-user [current-user share-with {id :id :as artifact}]
  (let [failure (validate-artifact artifact)]
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
