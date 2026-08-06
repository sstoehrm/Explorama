(ns de.explorama.backend.expdb.test-util
  (:require [de.explorama.backend.expdb.persistence.common-rocksdb :as rocksdb]))

(def ^:private node-fs (js/require "fs"))

(defn cleanup-db! [db-key]
  (rocksdb/close-db! db-key)
  (.rmSync node-fs db-key #js{:recursive true :force true}))
