(ns de.explorama.backend.expdb.persistence.backend-simple
  (:require [de.explorama.backend.expdb.persistence.common-rocksdb
             :refer [db-del+ db-drop-table db-get+ db-set+ dump set-dump]]
            [de.explorama.backend.expdb.persistence.simple :as itf]))

(def ^:private db-key "de.explorama.backend.expdb.simple.rocksdb")

(defonce ^:private store (atom {}))

(deftype Backend [bucket config]
  itf/Simple

  (schema [_]
    bucket)

  (dump [_]
    (dump db-key bucket))

  (set-dump [_ data]
    (set-dump db-key bucket data)
    {:success true
     :pairs (count data)})

  (del [_ key]
    (db-del+ db-key bucket [key])
    {:success true
     :pairs -1})
  (del-bucket [_]
    (db-drop-table db-key bucket)
    (swap! store dissoc bucket)
    {:success true
     :dropped-bucket? true})

  (get [_ key]
    (-> (db-get+ db-key bucket [key])
        (get key)))
  (get+ [_]
    (dump db-key bucket))
  (get+ [_ keys]
    (db-get+ db-key bucket keys))

  (set [_ key value]
    (db-set+ db-key bucket {key value})
    {:success true
     :pairs 1})

  (set+ [_ data]
    (db-set+ db-key bucket data)
    {:success true
     :pairs (count data)}))

(defn new-instance [config bucket]
  (if-let [instance (get @store bucket)]
    instance
    (let [instance (Backend. bucket config)]
      (swap! store assoc bucket instance)
      instance)))

(defn instances []
  @store)
