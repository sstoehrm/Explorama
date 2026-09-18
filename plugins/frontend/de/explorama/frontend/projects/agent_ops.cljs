(ns de.explorama.frontend.projects.agent-ops
  (:require [de.explorama.frontend.agent-gateway.registry :as registry]
            [de.explorama.frontend.common.frontend-interface :as fi]
            [de.explorama.frontend.projects.path :as path]
            [de.explorama.frontend.projects.subs :as subs]
            [de.explorama.frontend.projects.utils.projects :as p-utils]
            [de.explorama.frontend.woco.agent-ops :as woco-ops]))

(defn current [db]
  {:project (fi/call-api :loaded-project-db-get db)
   :step (fi/call-api :project-current-step-db-get db)
   :unsaved? (boolean (p-utils/project-unsaved? db))})

(def ^:private settle-ms 1500)

(defn- reply-later [ok fail]
  {:ms settle-ms :dispatch [::woco-ops/reply-with (woco-ops/stash-reply! ok fail) current]})

(defn create [{:keys [db params ok fail]}]
  (let [{:keys [title description]} params]
    {:db (-> db
             (assoc-in path/new-project-title title)
             (assoc-in path/new-project-desc (or description "")))
     :dispatch [:de.explorama.frontend.projects.views.create-project/create]
     :dispatch-later [(reply-later ok fail)]}))

(defn load [{:keys [db params ok fail]}]
  (let [project (subs/project-by-id db (:project-id params))]
    (if project
      {:dispatch [:de.explorama.frontend.projects.core/start-loading-project project nil]
       :dispatch-later [(reply-later ok fail)]}
      (do (fail :invalid-params "unknown project-id") {}))))

(defn load-step [{:keys [db params ok fail]}]
  {:dispatch (fi/call-api :project-load-step-event-vec db (:step params))
   :dispatch-later [(reply-later ok fail)]})

(defn register! []
  (registry/register-op! :projects/current (fn [{:keys [db ok]}] (ok (current db)) {}))
  (registry/register-op! :projects/create create)
  (registry/register-op! :projects/load load)
  (registry/register-op! :projects/load-step load-step))
