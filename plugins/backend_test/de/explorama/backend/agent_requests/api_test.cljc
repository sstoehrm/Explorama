(ns de.explorama.backend.agent-requests.api-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [de.explorama.backend.agent-requests.api :as sut]
            [de.explorama.backend.agent-requests.registry :as registry]
            [de.explorama.backend.agent-requests.store :as store]))

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
      (sut/list-requests {:client-callback #(reset! answered %)
                          :user-info {:username "tester"}}
                         [])
      (is (= [id] (mapv :id @answered)))
      (is (= #{:id :type :status :created-at :claimed-by}
             (set (keys (first @answered))))))))

(deftest cancel-request-test
  (testing "the owner can cancel and gets the refreshed list"
    (let [{:keys [id]} (create! "tester")
          answered (atom nil)]
      (sut/cancel-request {:client-callback #(reset! answered %)
                           :user-info {:username "tester"}}
                          [id])
      (is (= :cancelled (:status (store/get-request id))))
      (is (= [:cancelled] (mapv :status @answered)))))
  (testing "a stranger cannot cancel someone else's request"
    (let [{:keys [id]} (create! "tester")
          answered (atom nil)]
      (sut/cancel-request {:client-callback #(reset! answered %)
                           :user-info {:username "intruder"}}
                          [id])
      (is (= :open (:status (store/get-request id))))
      (is (empty? @answered)))))
