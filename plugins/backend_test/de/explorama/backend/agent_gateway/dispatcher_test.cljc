(ns de.explorama.backend.agent-gateway.dispatcher-test
  (:require #?(:clj [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [de.explorama.backend.agent-gateway.dispatcher :as sut]
            [de.explorama.shared.agent-gateway.catalog :as catalog]))

(def ^:private test-ops
  {:test/echo {:op :test/echo :doc "echo" :side :backend
               :input [:map {:closed true} [:text string?]] :output [:map [:text string?]]}
   :test/boom {:op :test/boom :doc "boom" :side :backend :input [:map] :output :any}
   :test/typed {:op :test/typed :doc "typed" :side :backend :input [:map] :output :any}
   :test/front {:op :test/front :doc "front" :side :frontend :input [:map] :output :any :timeout-ms 1234}})

(use-fixtures :each (fn [f] (sut/reset-registry!) (f) (sut/reset-registry!)))

(defn- invoke [request]
  (with-redefs [catalog/ops test-ops]
    (sut/invoke request)))

(deftest unknown-op-test
  (is (= :unknown-op (get-in (invoke {:op :test/nope :params {} :user "u"}) [:error :type]))))

(deftest invalid-params-test
  (let [response (invoke {:op :test/echo :params {:text 1} :user "u"})]
    (is (= :invalid-params (get-in response [:error :type])))
    (is (= {:text ["should be a string"]} (get-in response [:error :explain])))))

(deftest backend-op-test
  (sut/register-op! :test/echo (fn [{:keys [user params]}] {:text (str user ":" (:text params))}))
  (is (= {:status :ok :result {:text "u:hi"}} (invoke {:op :test/echo :params {:text "hi"} :user "u"})))
  (testing "a backend op without a registered handler is unknown"
    (is (= :unknown-op (get-in (invoke {:op :test/boom :params {} :user "u"}) [:error :type])))))

(deftest failing-backend-op-test
  (sut/register-op! :test/boom (fn [_] (throw (ex-info "boom" {}))))
  (sut/register-op! :test/typed (fn [_] (throw (ex-info "gone" {:gateway-error :no-session :user "u"}))))
  (testing "a plain exception is op-failed with the message"
    (let [response (invoke {:op :test/boom :params {} :user "u"})]
      (is (= :op-failed (get-in response [:error :type])))
      (is (= "boom" (get-in response [:error :message])))))
  (testing "ex-data with :gateway-error picks the type and carries the extra keys"
    (let [response (invoke {:op :test/typed :params {} :user "u"})]
      (is (= :no-session (get-in response [:error :type])))
      (is (= "u" (get-in response [:error :user]))))))

(deftest frontend-op-test
  (testing "frontend ops need a user"
    (is (= :invalid-params (get-in (invoke {:op :test/front :params {}}) [:error :type]))))
  (testing "without a relay there is no session"
    (is (= :no-session (get-in (invoke {:op :test/front :params {} :user "u"}) [:error :type]))))
  (testing "the relay receives user, client-id, op, params and the declared timeout"
    (let [seen (atom nil)]
      (sut/set-relay! (fn [& args] (reset! seen args) {:status :ok :result :relayed}))
      (is (= {:status :ok :result :relayed} (invoke {:op :test/front :params {} :user "u" :client-id "c"})))
      (is (= ["u" "c" :test/front {} 1234] @seen)))))
