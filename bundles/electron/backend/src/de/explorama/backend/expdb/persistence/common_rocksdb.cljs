(ns de.explorama.backend.expdb.persistence.common-rocksdb
  (:require ["@harperfast/rocksdb-js" :refer [RocksDatabase]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [taoensso.timbre :refer [error]]))

(defn table-name [schema]
  (let [schema (str "indexed_" schema)
        result (str/replace (if (str/ends-with? schema "/")
                              (subs schema 0 (dec (count schema)))
                              schema)
                            #"[_\/-]+" "_")]
    result))

(defonce ^:private registry (atom {}))

(defn- db-instance [db-key bucket]
  (let [cf-name (table-name bucket)
        cache-key [db-key cf-name]]
    (or (get @registry cache-key)
        (let [db (RocksDatabase. db-key #js{:name cf-name})]
          (.open db)
          (swap! registry assoc cache-key db)
          db))))

(defn close-db! [db-key]
  (doseq [[[entry-db-key _ :as cache-key] db] @registry
          :when (= entry-db-key db-key)]
    (.close db)
    (swap! registry dissoc cache-key)))

(defn dump [db-key bucket]
  (try
    (let [db (db-instance db-key bucket)]
      (into {}
            (map (fn [entry]
                   [(edn/read-string (.-key entry))
                    (edn/read-string (.-value entry))]))
            (js/Array.from (.getRange db))))
    (catch :default e
      (error "dump" e)
      {:success false
       :message "dump - see logs for details"
       :error-reason (ex-message e)})))

(defn- write-pairs [db-key bucket data op-name]
  (try
    (let [db (db-instance db-key bucket)]
      (.transactionSync db
                        (fn [txn]
                          (doseq [[k v] data]
                            (.putSync ^js txn (pr-str k) (pr-str v)))))
      nil)
    (catch :default e
      (error op-name e)
      {:success false
       :message (str op-name " - see logs for details")
       :error-reason (ex-message e)})))

(defn set-dump [db-key bucket data]
  (write-pairs db-key bucket data "set-dump"))

(defn db-set+ [db-key bucket data]
  (write-pairs db-key bucket data "db-set+"))

(defn db-get+ [db-key bucket keys]
  (try
    (let [db (db-instance db-key bucket)]
      (into {}
            (keep (fn [k]
                    (let [v (.getSync db (pr-str k))]
                      (when (some? v)
                        [k (edn/read-string v)]))))
            keys))
    (catch :default e
      (error "db-get+" e)
      {:success false
       :message "db-get+ - see logs for details"
       :error-reason (ex-message e)})))

(defn db-del+ [db-key bucket keys]
  (try
    (let [db (db-instance db-key bucket)]
      (.transactionSync db
                        (fn [txn]
                          (doseq [k keys]
                            (.removeSync ^js txn (pr-str k)))))
      nil)
    (catch :default e
      (error "del" e)
      {:success false
       :message "db-del+ - see logs for details"
       :error-reason (ex-message e)})))

(defn db-drop-table [db-key bucket]
  (try
    (let [db (db-instance db-key bucket)]
      (.clearSync db)
      nil)
    (catch :default e
      (error "del-bucket" e)
      {:success false
       :message "db-drop-table - see logs for details"
       :error-reason (ex-message e)})))
