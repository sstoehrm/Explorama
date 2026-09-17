(ns de.explorama.backend.expdb.agent-ops-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [de.explorama.backend.agent-gateway.dispatcher :as dispatcher]
            [de.explorama.backend.expdb.agent-ops :as sut]
            [de.explorama.backend.expdb.persistence.backend-indexed :as backend-indexed]
            [de.explorama.backend.expdb.persistence.backend-simple :as backend-simple]
            [de.explorama.backend.expdb.test-util :as test-util]))

(def ^:private csv
  "id;country;date;cases\n1;Germany;01.02.2020;3\n2;France;02.02.2020;5\n")

(def ^:private simple-db-key "de.explorama.backend.expdb.agent-ops-test.simple.rocksdb")
(def ^:private indexed-db-key "de.explorama.backend.expdb.agent-ops-test.indexed.rocksdb")

(defn- db-fixture [test-fn]
  (with-redefs [de.explorama.backend.expdb.persistence.backend-simple/db-key simple-db-key
                de.explorama.backend.expdb.persistence.backend-indexed/db-key indexed-db-key]
    (reset! @#'backend-simple/store {})
    (try
      (test-fn)
      (finally
        (reset! @#'backend-simple/store {})
        (doseq [f [simple-db-key indexed-db-key]]
          (test-util/cleanup-db! f))))))

(use-fixtures :each (fn [f]
                      (dispatcher/reset-registry!)
                      (sut/register!)
                      (db-fixture f)
                      (dispatcher/reset-registry!)))

(deftest round-trip-test
  (let [{:keys [status result]} (dispatcher/invoke {:op :expdb/upload :user "alice"
                                                    :params {:file-name "cases.csv" :content csv :csv {:separator ";" :quote "\""}}})]
    (is (= :ok status))
    (is (map? (get-in result [:suggestion :mapping])))
    (is (= 2 (count (:preview result))))
    (testing "the suggestion stages, commits and lands in a bucket"
      (let [staged (dispatcher/invoke {:op :expdb/set-mapping :user "alice"
                                       :params {:file-name "cases.csv" :mapping (:suggestion result)}})]
        (is (= :ok (:status staged)) (pr-str staged))
        (is (= :ok (:status (dispatcher/invoke {:op :expdb/commit :user "alice" :params {}}))))
        (is (= :ok (:status (dispatcher/invoke {:op :expdb/buckets :user "alice" :params {}})))))))
  (testing "a mapping that fails the schema is invalid-params"
    (let [response (dispatcher/invoke {:op :expdb/set-mapping :user "alice" :params {:file-name "cases.csv" :mapping {:nonsense true}}})]
      (is (= :invalid-params (get-in response [:error :type]))))))
