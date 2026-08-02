(ns de.explorama.backend.expdb.persistence.backend-simple-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [de.explorama.backend.expdb.persistence.backend-simple :as sut]
            [de.explorama.backend.expdb.persistence.simple :as itf]))

(def ^:private node-fs (js/require "fs"))

(def ^:private db-key "de.explorama.backend.expdb.backend-simple-test.sqlite3")

(defn- with-registry [test-fn]
  (with-redefs [de.explorama.backend.expdb.persistence.backend-simple/db-key db-key]
    (reset! @#'sut/known-buckets #{})
    (try
      (test-fn)
      (finally
        (reset! @#'sut/known-buckets #{})
        (when (.existsSync node-fs db-key)
          (.rmSync node-fs db-key))))))

(deftest instances-returns-registered-buckets-as-map
  (with-registry
    (fn []
      (let [a (sut/new-instance nil "a-b")
            _ (itf/set a :k 1)
            b (sut/new-instance nil "c/d")
            _ (itf/set b :k 2)
            result (sut/instances)]
        (testing "map of original bucket name to instance"
          (is (map? result))
          (is (= #{"a-b" "c/d"} (set (keys result))))
          (is (= 1 (itf/get (get result "a-b") :k)))
          (is (= 2 (itf/get (get result "c/d") :k))))))))

(deftest registry-survives-process-restart
  (with-registry
    (fn []
      (itf/set (sut/new-instance nil "persistent-bucket") :k "v")
      (reset! @#'sut/known-buckets #{})
      (testing "enumeration rebuilt from sqlite, not from session memory"
        (is (= #{"persistent-bucket"} (set (keys (sut/instances)))))))))

(deftest del-bucket-deregisters
  (with-registry
    (fn []
      (let [instance (sut/new-instance nil "doomed")]
        (itf/set instance :k 1)
        (is (contains? (sut/instances) "doomed"))
        (itf/del-bucket instance)
        (testing "dropped bucket leaves the listing"
          (is (not (contains? (sut/instances) "doomed"))))))))
