(ns de.explorama.backend.expdb.persistence.common-rocksdb
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :refer [error]])
  (:import [java.nio.charset StandardCharsets]
           [java.util ArrayList]
           [java.util.concurrent.locks ReentrantReadWriteLock]
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

(defonce ^:private rw-lock (ReentrantReadWriteLock.))

(defn- ->bytes ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn- ->str [^bytes b]
  (String. b StandardCharsets/UTF_8))

(defn- open-db* [db-key]
  (when-let [parent (.getParentFile (io/file db-key))]
    (.mkdirs parent))
  (let [cf-names (with-open [opts (Options.)]
                   (mapv ->str (RocksDB/listColumnFamilies opts db-key)))
        cf-names (if (seq cf-names) cf-names ["default"])
        cf-opts (zipmap cf-names (repeatedly (count cf-names) #(ColumnFamilyOptions.)))
        descriptors (ArrayList. ^java.util.Collection
                                (mapv (fn [n] (ColumnFamilyDescriptor. (->bytes n) (get cf-opts n)))
                                      cf-names))
        handles (ArrayList.)
        db-options (doto (DBOptions.)
                     (.setCreateIfMissing true))
        db (RocksDB/open db-options ^String db-key descriptors handles)]
    {:db db
     :db-options db-options
     :cfs (zipmap cf-names (vec handles))
     :cf-opts cf-opts}))

(defn- open-entry! [db-key]
  (or (get @registry db-key)
      (let [entry (open-db* db-key)]
        (swap! registry assoc db-key entry)
        entry)))

(defn- create-cf! [db-key cf-name]
  (let [{:keys [^RocksDB db]} (get @registry db-key)
        cf-opts (ColumnFamilyOptions.)
        handle (.createColumnFamily db (ColumnFamilyDescriptor. (->bytes cf-name) cf-opts))]
    (swap! registry (fn [reg]
                       (-> reg
                           (assoc-in [db-key :cfs cf-name] handle)
                           (assoc-in [db-key :cf-opts cf-name] cf-opts))))
    handle))

(defn- with-entry+cf [db-key bucket create? f]
  (let [cf-name (table-name bucket)
        rl (.readLock rw-lock)
        wl (.writeLock rw-lock)]
    (.lock rl)
    (let [entry (get @registry db-key)]
      (if (and entry (or (get-in entry [:cfs cf-name]) (not create?)))
        (try
          (f entry (get-in entry [:cfs cf-name]))
          (finally (.unlock rl)))
        (do
          (.unlock rl)
          (.lock wl)
          (let [entry (try
                        (let [entry (open-entry! db-key)]
                          (when (and create? (not (get-in entry [:cfs cf-name])))
                            (create-cf! db-key cf-name))
                          (get @registry db-key))
                        (catch Throwable t
                          (.unlock wl)
                          (throw t)))]
            (.lock rl)
            (.unlock wl)
            (try
              (f entry (get-in entry [:cfs cf-name]))
              (finally (.unlock rl)))))))))

(defn close-db! [db-key]
  (let [wl (.writeLock rw-lock)]
    (.lock wl)
    (try
      (when-let [{:keys [^RocksDB db cfs cf-opts ^DBOptions db-options]} (get @registry db-key)]
        (doseq [^ColumnFamilyHandle handle (vals cfs)]
          (.close handle))
        (doseq [^ColumnFamilyOptions opts (vals cf-opts)]
          (.close opts))
        (.close db)
        (.close db-options)
        (swap! registry dissoc db-key))
      (finally (.unlock wl)))))

(defn dump [db-key bucket]
  (try
    (with-entry+cf db-key bucket false
      (fn [{:keys [^RocksDB db]} ^ColumnFamilyHandle cf]
        (if cf
          (with-open [^RocksIterator it (.newIterator db cf)]
            (.seekToFirst it)
            (loop [result (transient {})]
              (if (.isValid it)
                (let [k (edn/read-string (->str (.key it)))
                      v (edn/read-string (->str (.value it)))]
                  (.next it)
                  (recur (assoc! result k v)))
                (persistent! result))))
          {})))
    (catch Throwable e
      (error e "dump")
      {:success false
       :message "dump - see logs for details"
       :error-reason (ex-message e)})))

(defn- write-pairs [db-key bucket data op-name]
  (try
    (with-entry+cf db-key bucket true
      (fn [{:keys [^RocksDB db]} ^ColumnFamilyHandle cf]
        (with-open [wb (WriteBatch.)
                    wo (WriteOptions.)]
          (doseq [[k v] data]
            (.put wb cf (->bytes (pr-str k)) (->bytes (pr-str v))))
          (.write db wo wb))
        nil))
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
    (with-entry+cf db-key bucket false
      (fn [{:keys [^RocksDB db]} ^ColumnFamilyHandle cf]
        (if (and cf (seq keys))
          (let [cf-list (ArrayList. ^java.util.Collection (vec (repeat (count keys) cf)))
                key-list (ArrayList. ^java.util.Collection (mapv #(->bytes (pr-str %)) keys))
                values (.multiGetAsList db cf-list key-list)]
            (into {}
                  (keep (fn [[k ^bytes v]]
                          (when v
                            [k (edn/read-string (->str v))])))
                  (map vector keys values)))
          {})))
    (catch Throwable e
      (error e "db-get+")
      {:success false
       :message "db-get+ - see logs for details"
       :error-reason (ex-message e)})))

(defn db-del+ [db-key bucket keys]
  (try
    (with-entry+cf db-key bucket false
      (fn [{:keys [^RocksDB db]} ^ColumnFamilyHandle cf]
        (when cf
          (with-open [wb (WriteBatch.)
                      wo (WriteOptions.)]
            (doseq [k keys]
              (.delete wb cf (->bytes (pr-str k))))
            (.write db wo wb)))
        nil))
    (catch Throwable e
      (error e "db-del+")
      {:success false
       :message "db-del+ - see logs for details"
       :error-reason (ex-message e)})))

(defn db-drop-table [db-key bucket]
  (let [wl (.writeLock rw-lock)]
    (.lock wl)
    (try
      (let [cf-name (table-name bucket)
            {:keys [^RocksDB db cfs cf-opts]} (open-entry! db-key)]
        (when-let [^ColumnFamilyHandle handle (get cfs cf-name)]
          (.dropColumnFamily db handle)
          (.close handle)
          (when-let [^ColumnFamilyOptions opts (get cf-opts cf-name)]
            (.close opts))
          (swap! registry (fn [reg]
                             (-> reg
                                 (update-in [db-key :cfs] dissoc cf-name)
                                 (update-in [db-key :cf-opts] dissoc cf-name))))
          nil))
      (catch Throwable e
        (error e "db-drop-table")
        {:success false
         :message "db-drop-table - see logs for details"
         :error-reason (ex-message e)})
      (finally (.unlock wl)))))
