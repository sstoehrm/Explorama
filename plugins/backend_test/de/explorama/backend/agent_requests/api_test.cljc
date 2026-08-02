(ns de.explorama.backend.agent-requests.api-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [de.explorama.backend.agent-requests.api :as sut]
            [de.explorama.backend.agent-requests.registry :as registry]
            [de.explorama.backend.agent-requests.store :as store]
            [de.explorama.backend.expdb.temp-import.api :as import-api]
            [de.explorama.backend.expdb.temp-import.mapping-request :as mapping-request]))

(use-fixtures :each (fn [f]
                      (registry/reset-types!)
                      (store/reset-store!)
                      (registry/register-type! {:id :test/greeting
                                                :description "Return a greeting"
                                                :output-schema [:map [:greeting string?]]
                                                :output-example {:greeting "hello"}
                                                :on-fulfilled (fn [_ _] nil)})
                      (f)))

(defn- create! [user]
  (store/create! {:type :test/greeting
                  :input {:secret "do not leak"}
                  :user user
                  :context {:reply :callback}}))

(deftest list-requests-test
  (testing "only the caller's requests are listed, without input or context"
    (let [{:keys [id]} (create! "tester")
          _ (create! "other")
          answered (atom nil)]
      (sut/list-requests {:client-callback #(reset! answered %)}
                         [{:username "tester"}])
      (is (= [id] (mapv :id @answered)))
      (is (= #{:id :type :status :created-at :claimed-by}
             (set (keys (first @answered))))))))

(deftest list-requests-without-user-test
  (testing "a caller without a username sees nothing rather than everything"
    (create! "tester")
    (create! "other")
    (let [answered (atom :untouched)]
      (sut/list-requests {:client-callback #(reset! answered %)} [nil])
      (is (= [] @answered)))
    (let [answered (atom :untouched)]
      (sut/list-requests {:client-callback #(reset! answered %)} [{:username "  "}])
      (is (= [] @answered)))))

(deftest cancel-request-test
  (testing "the owner can cancel and gets the refreshed list"
    (let [{:keys [id]} (create! "tester")
          answered (atom nil)]
      (sut/cancel-request {:client-callback #(reset! answered %)}
                          [{:username "tester"} id])
      (is (= :cancelled (:status (store/get-request id))))
      (is (= [:cancelled] (mapv :status @answered)))))
  (testing "a stranger cannot cancel someone else's request"
    (let [{:keys [id]} (create! "tester")
          answered (atom nil)]
      (sut/cancel-request {:client-callback #(reset! answered %)}
                          [{:username "intruder"} id])
      (is (= :open (:status (store/get-request id))))
      (is (empty? @answered)))))

(deftest cancel-request-nil-user-test
  (testing "a caller without a username cannot cancel anything"
    (let [{:keys [id]} (create! "tester")
          answered (atom nil)]
      (sut/cancel-request {:client-callback #(reset! answered %)} [nil id])
      (is (= :open (:status (store/get-request id))))
      (is (empty? @answered))))
  (testing "a caller with a blank username cannot cancel anything"
    (let [{:keys [id]} (create! "tester")
          answered (atom nil)]
      (sut/cancel-request {:client-callback #(reset! answered %)} [{:username ""} id])
      (is (= :open (:status (store/get-request id))))
      (is (empty? @answered)))))

(deftest no-request-is-stored-without-a-user-test
  (testing "the store refuses to file a request that carries no user"
    (is (nil? (create! nil)))
    (is (nil? (create! "")))
    (is (empty? (store/open-requests nil)))))

(deftest filed-request-is-scoped-to-its-filer-test
  (registry/reset-types!)
  (mapping-request/register!)
  (import-api/upload-file {:client-callback (fn [& _])
                           :failed-callback (fn [& _])}
                          [{:name "cases.csv" :extention "csv"}
                           "id;country\n1;Germany\n"])
  (testing "a mapping request filed through the real route is listed for its filer only"
    (import-api/request-mapping {:client-callback (fn [& _])
                                 :failed-callback (fn [& _])}
                                [{:username "tester"}
                                 {:name "cases.csv" :csv {:separator ";" :quote "\""}}
                                 "correlation-1"])
    (let [answered (atom nil)]
      (sut/list-requests {:client-callback #(reset! answered %)} [{:username "tester"}])
      (is (= [mapping-request/request-type] (mapv :type @answered))))
    (let [answered (atom nil)]
      (sut/list-requests {:client-callback #(reset! answered %)} [{:username "someone-else"}])
      (is (= [] @answered)))))

(deftest request-without-user-is-refused-test
  (registry/reset-types!)
  (mapping-request/register!)
  (import-api/upload-file {:client-callback (fn [& _])
                           :failed-callback (fn [& _])}
                          [{:name "cases.csv" :extention "csv"}
                           "id;country\n1;Germany\n"])
  (testing "filing without a user tells the caller instead of queueing an unowned request"
    (let [failed (atom nil)]
      (import-api/request-mapping {:client-callback (fn [& _])
                                   :failed-callback #(reset! failed %)}
                                  [nil
                                   {:name "cases.csv" :csv {:separator ";" :quote "\""}}
                                   "correlation-2"])
      (is (= "correlation-2" (:id @failed)))
      (is (empty? (store/open-requests nil))))))
