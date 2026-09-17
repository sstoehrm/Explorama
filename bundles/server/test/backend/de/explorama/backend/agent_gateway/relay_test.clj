(ns de.explorama.backend.agent-gateway.relay-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [de.explorama.backend.agent-gateway.relay :as sut]
            [de.explorama.shared.agent-gateway.ws-api :as ws-api]
            [pneumatic-tubes.core :as tubes]))

(def ^:private tube-ids (atom []))

(defn- fake-tube! [data]
  (let [received (promise)
        id (tubes/add-tube! (fn [event] (deliver received event)) data)]
    (swap! tube-ids conj id)
    {:id id :received received}))

(use-fixtures :each (fn [f]
                      (sut/reset-pending!)
                      (try (f)
                           (finally
                             (doseq [id @tube-ids] (tubes/rm-tube! id))
                             (reset! tube-ids [])
                             (sut/reset-pending!)))))

(deftest sessions-test
  (fake-tube! {:username "alice" :role "user" :client-id "c1" :connected-at 1})
  (fake-tube! {:username "alice" :role "user" :client-id "c2" :connected-at 2})
  (fake-tube! {:username "bob" :role "user" :client-id "c3" :connected-at 3})
  (testing "only the user's tubes, without tube internals"
    (is (= [{:client-id "c1" :role "user" :connected-at 1}
            {:client-id "c2" :role "user" :connected-at 2}]
           (sort-by :client-id (sut/sessions "alice")))))
  (testing "a tube without identity never shows up"
    (fake-tube! {})
    (is (= 1 (count (sut/sessions "bob"))))))

(deftest no-session-test
  (is (= :no-session (get-in (sut/invoke! "nobody" nil :woco/frames {} 100) [:error :type]))))

(deftest ambiguous-session-test
  (fake-tube! {:username "alice" :client-id "c1" :connected-at 1})
  (fake-tube! {:username "alice" :client-id "c2" :connected-at 2})
  (let [response (sut/invoke! "alice" nil :woco/frames {} 100)]
    (is (= :ambiguous-session (get-in response [:error :type])))
    (is (= #{"c1" "c2"} (set (map :client-id (get-in response [:error :sessions]))))))
  (testing "naming the client-id resolves it"
    (is (= :timeout (get-in (sut/invoke! "alice" "c2" :woco/frames {} 50) [:error :type])))))

(deftest wrong-client-id-test
  (fake-tube! {:username "alice" :client-id "c1" :connected-at 1})
  (is (= :no-session (get-in (sut/invoke! "alice" "other" :woco/frames {} 100) [:error :type]))))

(deftest round-trip-test
  (let [{:keys [received]} (fake-tube! {:username "alice" :client-id "c1" :connected-at 1})
        answer (future
                 (let [[event request-id op params] (deref received 2000 nil)]
                   (is (= ws-api/command event))
                   (is (= :woco/frames op))
                   (is (= {:a 1} params))
                   (sut/deliver-result! request-id {:status :ok :result [:frame]})))]
    (is (= {:status :ok :result [:frame]} (sut/invoke! "alice" nil :woco/frames {:a 1} 2000)))
    @answer
    (is (empty? (sut/pending-request-ids)) "a delivered request is forgotten")))

(deftest timeout-test
  (fake-tube! {:username "alice" :client-id "c1" :connected-at 1})
  (is (= :timeout (get-in (sut/invoke! "alice" nil :woco/frames {} 50) [:error :type])))
  (is (empty? (sut/pending-request-ids)) "a timed-out request is swept"))

(deftest tube-destroy-test
  (let [{:keys [id received]} (fake-tube! {:username "alice" :client-id "c1" :connected-at 1})
        result (future (sut/invoke! "alice" nil :woco/frames {} 2000))]
    (deref received 2000 nil)
    (sut/on-tube-destroy! (tubes/get-tube id))
    (is (= :no-session (get-in @result [:error :type])))))

(deftest unknown-result-test
  (testing "a result for an unknown request is ignored"
    (is (nil? (sut/deliver-result! "ghost" {:status :ok :result 1})))))
