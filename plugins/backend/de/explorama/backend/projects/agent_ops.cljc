(ns de.explorama.backend.projects.agent-ops
  (:require [de.explorama.backend.agent-gateway.dispatcher :as dispatcher]
            [de.explorama.backend.projects.core :as projects]))

(defn- user-info [user] {:username user})

(defn- list-for [user]
  (projects/list-projects (user-info user)))

(defn register! []
  (dispatcher/register-op! :projects/list (fn [{:keys [user]}] (list-for user)))
  (dispatcher/register-op! :projects/rename
                           (fn [{:keys [user params]}]
                             (projects/update-project-detail (:project-id params) :title (:title params))
                             (list-for user)))
  (dispatcher/register-op! :projects/set-description
                           (fn [{:keys [user params]}]
                             (projects/update-project-detail (:project-id params) :description (:description params))
                             (list-for user))))
