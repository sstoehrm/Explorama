(ns de.explorama.backend.agent-requests.store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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
      (is (:ok (sut/submit! id {:greeting "moin"})))
      (is (= [[id {:greeting "moin"}]] @fulfilled))
      (is (= :fulfilled (:status (sut/get-request id))))))
  (testing "an invalid result does not run :on-fulfilled"
    (reset! fulfilled [])
    (let [{:keys [id]} (create!)]
      (sut/claim! id "agent-1")
      (is (= :invalid (:error (sut/submit! id {:greeting 42}))))
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
      (is (:ok (sut/submit! id {:x 1})))
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
