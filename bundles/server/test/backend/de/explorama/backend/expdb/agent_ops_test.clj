(ns de.explorama.backend.expdb.agent-ops-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [de.explorama.backend.agent-gateway.dispatcher :as dispatcher]
            [de.explorama.backend.expdb.agent-ops :as sut]
            [de.explorama.backend.expdb.persistence.backend-indexed :as backend-indexed]
            [de.explorama.backend.expdb.persistence.backend-simple :as backend-simple]
            [de.explorama.backend.expdb.query.index :as index]
            [de.explorama.backend.expdb.test-util :as test-util]))

(def ^:private csv
  "id;country;date;cases\n1;Germany;01.02.2020;3\n2;France;02.02.2020;5\n")

(def ^:private simple-db-key "de.explorama.backend.expdb.agent-ops-test.simple.rocksdb")
(def ^:private indexed-db-key "de.explorama.backend.expdb.agent-ops-test.indexed.rocksdb")

(defn- db-fixture [test-fn]
  (with-redefs [de.explorama.backend.expdb.persistence.backend-simple/db-key simple-db-key
                de.explorama.backend.expdb.persistence.backend-indexed/db-key indexed-db-key]
    (reset! @#'backend-simple/store {})
    (sut/reset-staged!)
    (try
      (test-fn)
      (finally
        (reset! @#'backend-simple/store {})
        (sut/reset-staged!)
        (swap! index/current dissoc "default")
        (swap! index/current-inv dissoc "default")
        (swap! index/expdb-hash->dt-key dissoc "default")
        (doseq [f [simple-db-key indexed-db-key]]
          (test-util/cleanup-db! f))))))

(use-fixtures :each (fn [f]
                      (dispatcher/reset-registry!)
                      (sut/register!)
                      (db-fixture f)
                      (dispatcher/reset-registry!)))

(deftest round-trip-test
  (testing "committing with nothing staged by the gateway is invalid-params"
    (is (= :invalid-params (get-in (dispatcher/invoke {:op :expdb/commit :user "alice" :params {}}) [:error :type]))))
  (let [{:keys [status result]} (dispatcher/invoke {:op :expdb/upload :user "alice"
                                                    :params {:file-name "cases.csv" :content csv :csv {:separator ";" :quote "\""}}})]
    (is (= :ok status))
    (is (map? (get-in result [:suggestion :mapping])))
    (is (= 2 (count (:preview result))))
    (testing "the suggestion stages, commits and lands in a bucket"
      (let [staged (dispatcher/invoke {:op :expdb/set-mapping :user "alice"
                                       :params {:file-name "cases.csv" :mapping (:suggestion result)}})]
        (is (= :ok (:status staged)) (pr-str staged))
        (testing "another user cannot commit alice's staged import"
          (is (= :invalid-params (get-in (dispatcher/invoke {:op :expdb/commit :user "bob" :params {}}) [:error :type]))))
        (is (= :ok (:status (dispatcher/invoke {:op :expdb/commit :user "alice" :params {}}))))
        (is (= :ok (:status (dispatcher/invoke {:op :expdb/buckets :user "alice" :params {}})))))))
  (testing "a mapping that fails the schema is invalid-params"
    (let [response (dispatcher/invoke {:op :expdb/set-mapping :user "alice" :params {:file-name "cases.csv" :mapping {:nonsense true}}})]
      (is (= :invalid-params (get-in response [:error :type]))))
    (testing "the failed staging still records its owner, so alice can cancel it"
      (let [cancel-response (dispatcher/invoke {:op :expdb/cancel :user "alice" :params {}})]
        (is (not= "no import staged by this user" (get-in cancel-response [:error :message])))))
    (testing "a subsequent valid mapping still works"
      (let [{:keys [status result]} (dispatcher/invoke {:op :expdb/upload :user "alice"
                                                        :params {:file-name "cases.csv" :content csv :csv {:separator ";" :quote "\""}}})]
        (is (= :ok status))
        (is (= :ok (:status (dispatcher/invoke {:op :expdb/set-mapping :user "alice"
                                                :params {:file-name "cases.csv" :mapping (:suggestion result)}}))))
        (is (= :ok (:status (dispatcher/invoke {:op :expdb/cancel :user "alice" :params {}}))))))))
