(ns de.explorama.backend.expdb.persistence.common-rocksdb-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [de.explorama.backend.expdb.persistence.common-rocksdb :as sut]))

(def ^:private node-fs (js/require "fs"))

(def ^:private db-key "target/common-rocksdb-test.rocksdb")

(defn- delete-recursively! [path]
  (.rmSync node-fs path #js{:recursive true :force true}))

(use-fixtures :each
  {:before (fn []
             (sut/close-db! db-key)
             (delete-recursively! db-key))
   :after (fn []
            (sut/close-db! db-key)
            (delete-recursively! db-key))})

(deftest round-trip-test
  (is (= {} (sut/dump db-key "bucket")))
  (sut/db-set+ db-key "bucket" {"a" 1 :b {:c [1 2]}})
  (is (= {"a" 1 :b {:c [1 2]}} (sut/dump db-key "bucket")))
  (is (= {"a" 1} (sut/db-get+ db-key "bucket" ["a" "missing"])))
  (sut/db-del+ db-key "bucket" ["a"])
  (is (= {:b {:c [1 2]}} (sut/dump db-key "bucket"))))

(deftest set-dump-merges-test
  (sut/db-set+ db-key "bucket" {"a" 1})
  (sut/set-dump db-key "bucket" {"b" 2})
  (is (= {"a" 1 "b" 2} (sut/dump db-key "bucket"))))

(deftest drop-and-recreate-test
  (sut/db-set+ db-key "bucket" {"a" 1})
  (sut/db-drop-table db-key "bucket")
  (is (= {} (sut/dump db-key "bucket")))
  (sut/db-drop-table db-key "never-existed")
  (sut/db-set+ db-key "bucket" {"b" 2})
  (is (= {"b" 2} (sut/dump db-key "bucket"))))

(deftest reopen-test
  (sut/db-set+ db-key "bucket" {"a" 1})
  (sut/close-db! db-key)
  (is (= {"a" 1} (sut/dump db-key "bucket")))
  (is (= {} (sut/dump db-key "other-bucket"))))

(deftest bucket-isolation-test
  (sut/db-set+ db-key "b1" {"k" 1})
  (sut/db-set+ db-key "b2" {"k" 2})
  (is (= {"k" 1} (sut/dump db-key "b1")))
  (is (= {"k" 2} (sut/dump db-key "b2"))))

(deftest empty-get-test
  (sut/db-set+ db-key "bucket" {"a" 1})
  (is (= {} (sut/db-get+ db-key "bucket" []))))
