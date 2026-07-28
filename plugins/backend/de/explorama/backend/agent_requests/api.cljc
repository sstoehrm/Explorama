(ns de.explorama.backend.agent-requests.api
  (:require [de.explorama.backend.agent-requests.store :as store]))

(defn- public-request [request]
  (select-keys request [:id :type :status :created-at :claimed-by]))

(defn- user-list [username]
  (mapv public-request (store/user-requests username)))

(defn list-requests [{:keys [client-callback user-info]} _]
  (client-callback (user-list (:username user-info))))

(defn cancel-request [{:keys [client-callback user-info]} [id]]
  (let [username (:username user-info)]
    (when (= username (:user (store/get-request id)))
      (store/cancel! id)
      (client-callback (user-list username)))))
