(ns de.explorama.backend.expdb.persistence.backend-simple-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [de.explorama.backend.expdb.persistence.backend-simple :as sut]
            [de.explorama.backend.expdb.persistence.simple :as itf]))

(def ^:private db-key "de.explorama.backend.expdb.backend-simple-test.sqlite3")

(defn- registry-fixture [test-fn]
  (with-redefs [de.explorama.backend.expdb.persistence.backend-simple/db-key db-key]
    (reset! @#'sut/known-buckets #{})
    (test-fn)
    (reset! @#'sut/known-buckets #{})
    (when (.exists (io/file db-key))
      (io/delete-file db-key))))

(use-fixtures :each registry-fixture)

(deftest instances-returns-registered-buckets-as-map
  (let [a (sut/new-instance nil "a-b")
        _ (itf/set a :k 1)
        b (sut/new-instance nil "c/d")
        _ (itf/set b :k 2)
        result (sut/instances)]
    (testing "map of original bucket name to instance"
      (is (map? result))
      (is (= #{"a-b" "c/d"} (set (keys result))))
      (is (= 1 (itf/get (get result "a-b") :k)))
      (is (= 2 (itf/get (get result "c/d") :k))))))

(deftest registry-survives-process-restart
  (itf/set (sut/new-instance nil "persistent-bucket") :k "v")
  (reset! @#'sut/known-buckets #{})
  (testing "enumeration rebuilt from sqlite, not from session memory"
    (is (= #{"persistent-bucket"} (set (keys (sut/instances)))))))

(deftest del-bucket-deregisters
  (let [instance (sut/new-instance nil "doomed")]
    (itf/set instance :k 1)
    (is (contains? (sut/instances) "doomed"))
    (itf/del-bucket instance)
    (testing "dropped bucket leaves the listing"
      (is (not (contains? (sut/instances) "doomed"))))))
