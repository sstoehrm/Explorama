(ns de.explorama.frontend.agent-gateway.registry-test
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [de.explorama.frontend.agent-gateway.registry :as sut]
            [de.explorama.frontend.common.frontend-interface :as fi]
            [re-frame.core :as re-frame]))

(use-fixtures :each (fn [f] (sut/reset-ops!) (f) (sut/reset-ops!)))

(defn- with-normal-mode [normal? f]
  (with-redefs [fi/call-api (fn [api & _]
                              (case api
                                [:interaction-mode :normal-db-get?] normal?
                                [:interaction-mode :current-db-get?] (if normal? :normal :read-only)
                                :client-id-db-get "client-1"
                                nil))]
    (f)))

(deftest busy-workspace-test
  (testing "a non-normal interaction mode refuses before looking the op up"
    (with-normal-mode false
      #(let [{[event request-id response] :dispatch} (sut/run-command {} "r1" :test/echo {})]
         (is (= ::sut/send-result event))
         (is (= "r1" request-id))
         (is (= :workspace-busy (get-in response [:error :type])))
         (is (= :read-only (get-in response [:error :interaction-mode]))))))
  (testing ":woco/workspace is exempt and runs even while busy"
    (sut/register-op! :woco/workspace (fn [{:keys [ok]}] (ok {:interaction-mode :read-only}) {}))
    (with-normal-mode false
      #(let [dispatched (atom [])]
         (with-redefs [re-frame/dispatch (fn [e] (swap! dispatched conj e))]
           (sut/run-command {} "r1" :woco/workspace {})
           (is (= [[::sut/send-result "r1" {:status :ok :result {:interaction-mode :read-only}}]] @dispatched)))))))

(deftest unknown-op-test
  (with-normal-mode true
    #(let [{[_ _ response] :dispatch} (sut/run-command {} "r1" :test/nope {})]
       (is (= :unknown-op (get-in response [:error :type]))))))

(deftest sync-handler-test
  (testing "a handler that answers synchronously sends the result"
    (sut/register-op! :test/echo (fn [{:keys [params ok]}] (ok {:text (:text params)}) {}))
    (let [dispatched (atom [])]
      (with-redefs [re-frame/dispatch (fn [e] (swap! dispatched conj e))]
        (with-normal-mode true
          #(do (sut/run-command {} "r1" :test/echo {:text "hi"})
               (is (= [[::sut/send-result "r1" {:status :ok :result {:text "hi"}}]] @dispatched))))))))

(deftest failing-handler-test
  (testing "fail reports the given type"
    (sut/register-op! :test/fail (fn [{:keys [fail]}] (fail :op-failed "nope") {}))
    (let [dispatched (atom [])]
      (with-redefs [re-frame/dispatch (fn [e] (swap! dispatched conj e))]
        (with-normal-mode true
          #(do (sut/run-command {} "r1" :test/fail {})
               (is (= :op-failed (get-in (first @dispatched) [2 :error :type]))))))))
  (testing "a throwing handler is reported as op-failed with its message"
    (sut/register-op! :test/throw (fn [_] (throw (ex-info "boom" {}))))
    (with-normal-mode true
      #(let [{[_ _ response] :dispatch} (sut/run-command {} "r1" :test/throw {})]
         (is (= :op-failed (get-in response [:error :type])))
         (is (= "boom" (get-in response [:error :message])))))))

(deftest handler-fx-test
  (testing "the handler's fx map is returned so it can update the db and dispatch"
    (sut/register-op! :test/db (fn [{:keys [db ok]}] (ok :done) {:db (assoc db :x 1) :dispatch [:later]}))
    (with-normal-mode true
      #(let [fx (sut/run-command {} "r1" :test/db {})]
         (is (= {:x 1} (:db fx)))
         (is (= [:later] (:dispatch fx)))))))

(deftest send-result-test
  (testing "the reply goes over the backend tube with the client-id in the metas"
    (with-normal-mode true
      #(is (= {:backend-tube [:de.explorama.shared.agent-gateway.ws-api/result {:client-id "client-1"} "r1" {:status :ok :result 1}]}
              (sut/send-result-fx {:db {}} [nil "r1" {:status :ok :result 1}]))))))
