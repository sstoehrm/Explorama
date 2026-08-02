(ns de.explorama.backend.agent-requests.store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [de.explorama.backend.agent-requests.config :as config]
            [de.explorama.backend.agent-requests.registry :as registry]
            [de.explorama.backend.agent-requests.store :as sut]))

(def ^:private fulfilled (atom []))

(defn- declare-type! []
  (registry/register-type!
   {:id :test/greeting
    :description "Return a greeting"
    :output-schema [:map [:greeting string?]]
    :output-example {:greeting "hello"}
    :on-fulfilled (fn [request result]
                    (swap! fulfilled conj [(:id request) result]))}))

(use-fixtures :each (fn [f]
                      (registry/reset-types!)
                      (sut/reset-store!)
                      (reset! fulfilled [])
                      (declare-type!)
                      (f)))

(defn- create! []
  (sut/create! {:type :test/greeting
                :input {:name "Ada"}
                :user "tester"
                :context {:reply :callback}}))

(deftest create-test
  (testing "a created request gets an id and shows up as open"
    (let [{:keys [id]} (create!)]
      (is (string? id))
      (is (= [id] (mapv :id (sut/open-requests nil))))
      (is (= [id] (mapv :id (sut/user-requests "tester"))))
      (is (empty? (sut/user-requests "someone-else"))))))

(deftest submit-runs-handler-test
  (testing "a valid result fulfils the request and runs :on-fulfilled"
    (let [{:keys [id]} (create!)]
      (sut/claim! id "agent-1")
      (is (:ok (sut/submit! id "agent-1" {:greeting "moin"})))
      (is (= [[id {:greeting "moin"}]] @fulfilled))
      (is (= :fulfilled (:status (sut/get-request id))))))
  (testing "an invalid result does not run :on-fulfilled"
    (reset! fulfilled [])
    (let [{:keys [id]} (create!)]
      (sut/claim! id "agent-1")
      (is (= :invalid (:error (sut/submit! id "agent-1" {:greeting 42}))))
      (is (empty? @fulfilled))))
  (testing "a throwing handler does not corrupt the request state"
    (registry/register-type!
     {:id :test/boom
      :description "Throws"
      :output-schema [:map [:x int?]]
      :output-example {:x 1}
      :on-fulfilled (fn [_ _] (throw (ex-info "boom" {})))})
    (let [{:keys [id]} (sut/create! {:type :test/boom
                                     :input {}
                                     :user "tester"
                                     :context {}})]
      (sut/claim! id "agent-1")
      (is (:ok (sut/submit! id "agent-1" {:x 1})))
      (is (= :fulfilled (:status (sut/get-request id)))))))

(deftest clock-test
  (testing "requests expire once the injected clock passes the ttl"
    (binding [sut/*now-fn* (constantly 0)]
      (let [{:keys [id]} (create!)]
        (binding [sut/*now-fn* (constantly 10)]
          (is (= [id] (mapv :id (sut/open-requests nil)))))
        (binding [sut/*now-fn* (constantly (* 60 60 1000))]
          (is (empty? (sut/open-requests nil)))
          (is (= :expired (:status (sut/get-request id)))))))))

(deftest watch-test
  (testing "watchers run on state changes and stop after unwatch"
    (let [calls (atom 0)]
      (sut/watch! ::test #(swap! calls inc))
      (create!)
      (is (= 1 @calls))
      (sut/unwatch! ::test)
      (create!)
      (is (= 1 @calls)))))

(defn- create-watched! []
  (let [failed (atom [])]
    [failed (sut/create! {:type :test/greeting
                          :input {:name "Ada"}
                          :user "tester"
                          :context {:failed-callback #(swap! failed conj %)}})]))

(deftest exhausted-rejections-test
  (testing "exhausting max-rejections notifies the waiting client exactly once and skips :on-fulfilled"
    (let [[failed {:keys [id]}] (create-watched!)]
      (sut/claim! id "agent-1")
      (dotimes [_ config/max-rejections]
        (sut/submit! id "agent-1" {:greeting 42}))
      (is (= :failed (:status (sut/get-request id))))
      (is (= 1 (count @failed)))
      (is (some? (:error (first @failed))))
      (is (empty? @fulfilled)))))

(deftest cancel-notifies-test
  (testing "cancelling a request with a waiting context notifies the failed-callback exactly once"
    (let [[failed {:keys [id]}] (create-watched!)]
      (sut/cancel! id)
      (is (= :cancelled (:status (sut/get-request id))))
      (is (= [{:error :cancelled}] @failed)))))

(deftest expiry-notifies-on-next-transaction-test
  (testing "a request that expires is only announced once another transaction touches the store"
    (let [[failed {:keys [id]}] (binding [sut/*now-fn* (constantly 0)]
                                  (create-watched!))]
      (binding [sut/*now-fn* (constantly (inc config/ttl-ms))]
        (is (empty? @failed))
        (let [[other-failed {other-id :id}] (create-watched!)]
          (sut/claim! other-id "agent-1")
          (is (= :expired (:status (sut/get-request id))))
          (is (= [{:error :expired}] @failed))
          (is (empty? @other-failed))
          (sut/claim! other-id "agent-1")
          (is (= [{:error :expired}] @failed))
          (is (empty? @other-failed)))))))

(deftest fulfilment-does-not-notify-failure-test
  (testing "a successful fulfilment does not invoke the failed-callback"
    (let [[failed {:keys [id]}] (create-watched!)]
      (sut/claim! id "agent-1")
      (sut/submit! id "agent-1" {:greeting "moin"})
      (is (= :fulfilled (:status (sut/get-request id))))
      (is (empty? @failed)))))

(deftest throwing-failed-callback-test
  (testing "a throwing failed-callback does not corrupt state or prevent the transition"
    (let [{:keys [id]} (sut/create! {:type :test/greeting
                                     :input {:name "Ada"}
                                     :user "tester"
                                     :context {:failed-callback (fn [_] (throw (ex-info "boom" {})))}})]
      (is (:ok (sut/cancel! id)))
      (is (= :cancelled (:status (sut/get-request id)))))))
