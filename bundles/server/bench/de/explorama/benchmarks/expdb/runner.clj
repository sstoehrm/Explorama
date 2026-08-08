(ns de.explorama.benchmarks.expdb.runner
  (:require [clojure.java.io :as io]
            [de.explorama.backend.expdb.persistence.common-rocksdb :as rocksdb]
            [de.explorama.benchmarks.expdb.e2e :as e2e]
            [de.explorama.benchmarks.expdb.harness :as harness]
            [de.explorama.benchmarks.expdb.scenarios :as scenarios]))

(def ^:private scratch-db "target/bench-kv.rocksdb")
(def ^:private results-dir "../../benchmarks/results")

(defn- delete-recursively! [path]
  (let [f (io/file path)]
    (when (.exists f)
      (doseq [file (reverse (file-seq f))]
        (io/delete-file file true)))))

(def ^:private storage
  {:set+ (partial rocksdb/db-set+ scratch-db)
   :get+ (partial rocksdb/db-get+ scratch-db)
   :del+ (partial rocksdb/db-del+ scratch-db)
   :dump (partial rocksdb/dump scratch-db)
   :set-dump (partial rocksdb/set-dump scratch-db)
   :drop (partial rocksdb/db-drop-table scratch-db)
   :reset! (fn []
             (rocksdb/close-db! scratch-db)
             (delete-recursively! scratch-db))})

(defn -main [& _]
  (harness/assert-deterministic!)
  (.mkdirs (io/file "target"))
  (let [results {:timestamp (harness/timestamp)
                 :git-sha (harness/git-sha)
                 :bundle :server
                 :backend :rocksdb
                 :scenarios (merge (scenarios/run-all storage {:warmup 1 :iterations 5})
                                   (e2e/run-all {}))}]
    ((:reset! storage))
    (harness/print-report results)
    (println "results written to" (harness/write-results! results-dir results))
    (System/exit 0)))
