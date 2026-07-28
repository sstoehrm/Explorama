(ns de.explorama.backend.agent-requests.auth-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [de.explorama.backend.agent-requests.auth :as sut]
            [ring.mock.request :as mock]))

(use-fixtures :each (fn [f] (sut/reset-cache!) (f) (sut/reset-cache!)))

(def ^:private ok-handler
  (sut/wrap-auth (fn [request] {:status 200 :body (:agent-principal request)})))

(defn- request-with [token]
  (cond-> (mock/request :get "/api/agent-requests")
    token (mock/header "Authorization" (str "Bearer " token))))

(deftest missing-token-test
  (testing "no header is 401"
    (is (= 401 (:status (ok-handler (request-with nil))))))
  (testing "a non-bearer header is 401"
    (is (= 401 (:status (ok-handler (-> (mock/request :get "/api/agent-requests")
                                        (mock/header "Authorization" "Basic abc"))))))))

(deftest invalid-token-test
  (testing "an inactive token is 401"
    (binding [sut/*introspect-fn* (constantly {:active false})]
      (is (= 401 (:status (ok-handler (request-with "t")))))))
  (testing "an unreachable provider is 401"
    (binding [sut/*introspect-fn* (constantly nil)]
      (is (= 401 (:status (ok-handler (request-with "t"))))))))

(deftest principal-test
  (testing "an allowed principal passes and is put on the request"
    (with-redefs [sut/allowed-principals #{"agent-service"}]
      (binding [sut/*introspect-fn* (constantly {:active true :sub "agent-service"})]
        (let [response (ok-handler (request-with "t"))]
          (is (= 200 (:status response)))
          (is (= "agent-service" (:body response)))))))
  (sut/reset-cache!)
  (testing "a valid token for another principal is 403"
    (with-redefs [sut/allowed-principals #{"agent-service"}]
      (binding [sut/*introspect-fn* (constantly {:active true :sub "someone-else"})]
        (is (= 403 (:status (ok-handler (request-with "t"))))))))
  (sut/reset-cache!)
  (testing "an empty principal set allows any active token"
    (with-redefs [sut/allowed-principals #{}]
      (binding [sut/*introspect-fn* (constantly {:active true :sub "whoever"})]
        (is (= 200 (:status (ok-handler (request-with "t")))))))))

(deftest cache-test
  (testing "a repeated token is introspected once"
    (let [calls (atom 0)]
      (with-redefs [sut/allowed-principals #{}]
        (binding [sut/*introspect-fn* (fn [_]
                                        (swap! calls inc)
                                        {:active true :sub "whoever"})]
          (ok-handler (request-with "t"))
          (ok-handler (request-with "t"))
          (is (= 1 @calls)))))))
