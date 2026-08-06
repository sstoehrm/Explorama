(ns de.explorama.backend.expdb.persistence.common-rocksdb
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [taoensso.timbre :refer [error]])
  (:import [java.nio.charset StandardCharsets]
           [java.util ArrayList]
           [org.rocksdb ColumnFamilyDescriptor ColumnFamilyHandle
            ColumnFamilyOptions DBOptions Options RocksDB RocksIterator
            WriteBatch WriteOptions]))

(RocksDB/loadLibrary)

(defn table-name [schema]
  (let [schema (str "indexed_" schema)
        result (str/replace (if (str/ends-with? schema "/")
                              (subs schema 0 (dec (count schema)))
                              schema)
                            #"[_\/-]+" "_")]
    result))

(defonce ^:private registry (atom {}))

(defn- ->bytes ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn- ->str [^bytes b]
  (String. b StandardCharsets/UTF_8))

(defn- open-db* [db-key]
  (let [cf-names (with-open [opts (Options.)]
                   (mapv ->str (RocksDB/listColumnFamilies opts db-key)))
        cf-names (if (seq cf-names) cf-names ["default"])
        descriptors (ArrayList. ^java.util.Collection
                                (mapv #(ColumnFamilyDescriptor. (->bytes %) (ColumnFamilyOptions.))
                                      cf-names))
        handles (ArrayList.)
        db (RocksDB/open (doto (DBOptions.)
                           (.setCreateIfMissing true))
                         ^String db-key descriptors handles)]
    {:db db
     :cfs (zipmap cf-names (vec handles))}))

(defn- db-entry [db-key]
  (or (get @registry db-key)
      (locking registry
        (or (get @registry db-key)
            (let [entry (open-db* db-key)]
              (swap! registry assoc db-key entry)
              entry)))))

(defn- cf-handle [db-key bucket create?]
  (let [cf-name (table-name bucket)
        {:keys [^RocksDB db cfs]} (db-entry db-key)]
    (or (get cfs cf-name)
        (when create?
          (locking registry
            (or (get-in @registry [db-key :cfs cf-name])
                (let [handle (.createColumnFamily db (ColumnFamilyDescriptor.
                                                      (->bytes cf-name)
                                                      (ColumnFamilyOptions.)))]
                  (swap! registry assoc-in [db-key :cfs cf-name] handle)
                  handle)))))))

(defn close-db! [db-key]
  (locking registry
    (when-let [{:keys [^RocksDB db cfs]} (get @registry db-key)]
      (doseq [^ColumnFamilyHandle handle (vals cfs)]
        (.close handle))
      (.close db)
      (swap! registry dissoc db-key))))

(defn dump [db-key bucket]
  (try
    (if-let [^ColumnFamilyHandle cf (cf-handle db-key bucket false)]
      (let [{:keys [^RocksDB db]} (db-entry db-key)]
        (with-open [^RocksIterator it (.newIterator db cf)]
          (.seekToFirst it)
          (loop [result (transient {})]
            (if (.isValid it)
              (let [k (edn/read-string (->str (.key it)))
                    v (edn/read-string (->str (.value it)))]
                (.next it)
                (recur (assoc! result k v)))
              (persistent! result)))))
      {})
    (catch Throwable e
      (error e "dump")
      {:success false
       :message "dump - see logs for details"
       :error-reason (ex-message e)})))

(defn- write-pairs [db-key bucket data op-name]
  (try
    (let [^ColumnFamilyHandle cf (cf-handle db-key bucket true)
          {:keys [^RocksDB db]} (db-entry db-key)]
      (with-open [wb (WriteBatch.)
                  wo (WriteOptions.)]
        (doseq [[k v] data]
          (.put wb cf (->bytes (pr-str k)) (->bytes (pr-str v))))
        (.write db wo wb))
      nil)
    (catch Throwable e
      (error e op-name)
      {:success false
       :message (str op-name " - see logs for details")
       :error-reason (ex-message e)})))

(defn set-dump [db-key bucket data]
  (write-pairs db-key bucket data "set-dump"))

(defn db-set+ [db-key bucket data]
  (write-pairs db-key bucket data "db-set+"))

(defn db-get+ [db-key bucket keys]
  (try
    (if-let [^ColumnFamilyHandle cf (cf-handle db-key bucket false)]
      (if (empty? keys)
        {}
        (let [{:keys [^RocksDB db]} (db-entry db-key)
              cf-list (ArrayList. ^java.util.Collection (vec (repeat (count keys) cf)))
              key-list (ArrayList. ^java.util.Collection (mapv #(->bytes (pr-str %)) keys))
              values (.multiGetAsList db cf-list key-list)]
          (into {}
                (keep (fn [[k ^bytes v]]
                        (when v
                          [k (edn/read-string (->str v))])))
                (map vector keys values))))
      {})
    (catch Throwable e
      (error e "db-get+")
      {:success false
       :message "db-get+ - see logs for details"
       :error-reason (ex-message e)})))

(defn db-del+ [db-key bucket keys]
  (try
    (when-let [^ColumnFamilyHandle cf (cf-handle db-key bucket false)]
      (let [{:keys [^RocksDB db]} (db-entry db-key)]
        (with-open [wb (WriteBatch.)
                    wo (WriteOptions.)]
          (doseq [k keys]
            (.delete wb cf (->bytes (pr-str k))))
          (.write db wo wb))
        nil))
    (catch Throwable e
      (error e "db-del+")
      {:success false
       :message "db-del+ - see logs for details"
       :error-reason (ex-message e)})))

(defn db-drop-table [db-key bucket]
  (try
    (locking registry
      (let [cf-name (table-name bucket)
            {:keys [^RocksDB db cfs]} (db-entry db-key)]
        (when-let [^ColumnFamilyHandle handle (get cfs cf-name)]
          (.dropColumnFamily db handle)
          (.close handle)
          (swap! registry update-in [db-key :cfs] dissoc cf-name)
          nil)))
    (catch Throwable e
      (error e "db-drop-table")
      {:success false
       :message "db-drop-table - see logs for details"
       :error-reason (ex-message e)})))
