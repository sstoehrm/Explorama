(ns de.explorama.backend.indicator.persistence.graph-store
  (:require [de.explorama.backend.expdb.middleware.db :as expdb]))

(def ^:private bucket "/indicator/aggregation-graphs/")

(defn write-graph [artifact]
  (if (:success (expdb/set bucket (:id artifact) artifact))
    artifact
    (throw (ex-info "Could not write aggregation graph to expdb" {:artifact artifact}))))

(defn read-graph [id]
  (expdb/get bucket id))

(defn list-all-user-graphs [user]
  (filterv (fn [{creator :creator}]
             (= creator (:username user)))
           (vals (expdb/get+ bucket))))

(defn delete-graph [id]
  (if (:success (expdb/del bucket id))
    id
    (throw (ex-info "Could not delete aggregation graph from expdb" {:id id}))))
