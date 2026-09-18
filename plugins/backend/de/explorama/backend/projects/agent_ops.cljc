(ns de.explorama.backend.projects.agent-ops
  (:require [de.explorama.backend.agent-gateway.dispatcher :as dispatcher]
            [de.explorama.backend.projects.core :as projects]))

(defn- list-for [user]
  (projects/list-projects (dispatcher/user-info user)))

(defn- writable? [projects-desc id]
  (or (contains? (:created-projects projects-desc) id)
      (contains? (:allowed-projects projects-desc) id)))

(defn- check-write-access! [user id]
  (when-not (writable? (list-for user) id)
    (throw (ex-info "no write access to the project" {:gateway-error :unauthorized :project-id id}))))

(defn register! []
  (dispatcher/register-op! :projects/list (fn [{:keys [user]}] (list-for user)))
  (dispatcher/register-op! :projects/rename
                           (fn [{:keys [user params]}]
                             (check-write-access! user (:project-id params))
                             (projects/update-project-detail (:project-id params) :title (:title params))
                             (list-for user)))
  (dispatcher/register-op! :projects/set-description
                           (fn [{:keys [user params]}]
                             (check-write-access! user (:project-id params))
                             (projects/update-project-detail (:project-id params) :description (:description params))
                             (list-for user))))
