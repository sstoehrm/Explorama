(ns de.explorama.backend.expdb.db-api-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [de.explorama.backend.expdb.middleware.db :as middleware]
            [de.explorama.backend.expdb.persistence.backend-simple :as backend-simple]
            [de.explorama.backend.expdb.persistence.db-api :as sut]))

(def ^:private node-fs (js/require "fs"))

(def ^:private db-key "de.explorama.backend.expdb.db-api-test.sqlite3")

(defn- with-db [test-fn]
  (with-redefs [de.explorama.backend.expdb.persistence.backend-simple/db-key db-key]
    (reset! @#'backend-simple/known-buckets #{})
    (try
      (test-fn)
      (finally
        (reset! @#'backend-simple/known-buckets #{})
        (when (.existsSync node-fs db-key)
          (.rmSync node-fs db-key))))))

(deftest load-buckets-lists-original-simple-names
  (with-db
    (fn []
      (middleware/set "a-b" :k 1)
      (middleware/set "c/d" :k 2)
      (let [result (atom nil)]
        (sut/load-buckets {:client-callback #(reset! result %)})
        (testing "original names, not mangled table names"
          (is (contains? (set @result) [:simple "a-b"]))
          (is (contains? (set @result) [:simple "c/d"])))))))

(deftest simple-roundtrip-through-wipe
  (with-db
    (fn []
      (middleware/set "roundtrip" :answer 42)
      (let [dumped (atom nil)]
        (sut/download-expdb {:client-callback #(reset! dumped %)})
        (is (= 42 (get-in @dumped [:simple "roundtrip" :answer])))
        (middleware/del-bucket "roundtrip")
        (reset! @#'backend-simple/known-buckets #{})
        (let [ok (atom nil)]
          (sut/upload-expdb {:client-callback #(reset! ok %)}
                            [{:simple (:simple @dumped)}])
          (testing "upload succeeds onto a wiped instance"
            (is (true? @ok))))
        (testing "data restored under the original name"
          (is (= 42 (middleware/get "roundtrip" :answer))))))))
