(ns de.explorama.backend.expdb.test-util
  (:require [clojure.java.io :as io]
            [de.explorama.backend.expdb.persistence.common-rocksdb :as rocksdb]))

(defn cleanup-db! [db-key]
  (rocksdb/close-db! db-key)
  (let [f (io/file db-key)]
    (when (.exists f)
      (doseq [file (reverse (file-seq f))]
        (io/delete-file file true)))))
