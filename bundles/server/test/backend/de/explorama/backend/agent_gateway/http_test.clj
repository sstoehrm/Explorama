(ns de.explorama.backend.agent-gateway.http-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [de.explorama.backend.agent-gateway.dispatcher :as dispatcher]
            [de.explorama.backend.agent-gateway.http :as sut]
            [de.explorama.shared.agent-gateway.catalog :as catalog]
            [ring.mock.request :as mock]))

(def ^:private test-ops
  {:test/echo {:op :test/echo :doc "echo" :side :backend
               :input [:map {:closed true} [:text string?]] :output [:map [:text string?]]}
   :test/front {:op :test/front :doc "front" :side :frontend :input [:map] :output :any}})

(use-fixtures :each (fn [f]
                      (dispatcher/reset-registry!)
                      (dispatcher/register-op! :test/echo (fn [{:keys [params]}] {:text (:text params)}))
                      (with-redefs [sut/allowed-principals #{"agent-service"}
                                    catalog/ops test-ops]
                        (f))
                      (dispatcher/reset-registry!)))

(defn- body-edn [response]
  (edn/read-string (:body response)))

(defn- GET-as [principal path]
  (sut/handler (cond-> (mock/request :get path)
                 principal (mock/header "X-Auth-Request-User" principal))))

(defn- POST-as [principal path body]
  (sut/handler (cond-> (-> (mock/request :post path)
                           (mock/content-type "application/edn")
                           (mock/body (pr-str body)))
                 principal (mock/header "X-Auth-Request-User" principal))))

(deftest auth-test
  (testing "no header is 403 unauthorized"
    (let [response (GET-as nil "/api/agent/ops")]
      (is (= 403 (:status response)))
      (is (= :unauthorized (get-in (body-edn response) [:error :type])))))
  (testing "an unlisted principal is 403"
    (is (= 403 (:status (GET-as "intruder" "/api/agent/ops")))))
  (testing "an empty allow-list denies everyone"
    (with-redefs [sut/allowed-principals #{}]
      (is (= 403 (:status (GET-as "agent-service" "/api/agent/ops")))))))

(deftest catalog-test
  (let [response (GET-as "agent-service" "/api/agent/ops")
        {:keys [status result]} (body-edn response)]
    (is (= 200 (:status response)))
    (is (= "application/edn" (get-in response [:headers "Content-Type"])))
    (is (= :ok status))
    (is (= [:test/echo :test/front] (mapv :op (:ops result))))
    (is (= [:map {:closed true} [:text 'string?]] (:input (first (:ops result)))))))

(deftest sessions-test
  (with-redefs [sut/sessions-fn (fn [user] (when (= "alice" user) [{:client-id "c1" :role "user" :connected-at 1}]))]
    (is (= {:status :ok :result {:sessions [{:client-id "c1" :role "user" :connected-at 1}]}}
           (body-edn (GET-as "agent-service" "/api/agent/sessions?user=alice"))))
    (is (= {:status :ok :result {:sessions []}}
           (body-edn (GET-as "agent-service" "/api/agent/sessions?user=bob"))))
    (testing "user is required"
      (is (= 400 (:status (GET-as "agent-service" "/api/agent/sessions")))))))

(deftest invoke-test
  (testing "a backend op runs and answers 200"
    (let [response (POST-as "agent-service" "/api/agent/ops/test/echo" {:user "alice" :params {:text "hi"}})]
      (is (= 200 (:status response)))
      (is (= {:status :ok :result {:text "hi"}} (body-edn response)))))
  (testing "error types map to their statuses"
    (is (= 404 (:status (POST-as "agent-service" "/api/agent/ops/test/nope" {:user "alice"}))))
    (is (= 400 (:status (POST-as "agent-service" "/api/agent/ops/test/echo" {:user "alice" :params {:text 1}}))))
    (is (= 409 (:status (POST-as "agent-service" "/api/agent/ops/test/front" {:user "ghost"}))))
    (is (= 400 (:status (POST-as "agent-service" "/api/agent/ops/test/front" {})))))
  (testing "the relay is called for frontend ops with the body's user and client-id"
    (let [seen (atom nil)]
      (dispatcher/set-relay! (fn [& args] (reset! seen args) {:status :ok :result :relayed}))
      (POST-as "agent-service" "/api/agent/ops/test/front" {:user "alice" :client-id "c1" :params {}})
      (is (= ["alice" "c1" :test/front {} catalog/default-timeout-ms] @seen))))
  (testing "a malformed body is 400"
    (let [response (sut/handler (-> (mock/request :post "/api/agent/ops/test/echo")
                                    (mock/content-type "application/edn")
                                    (mock/header "X-Auth-Request-User" "agent-service")
                                    (mock/body "{:user")))]
      (is (= 400 (:status response)))
      (is (= :invalid-params (get-in (body-edn response) [:error :type])))))
  (testing "a nested op path is one op keyword"
    (is (= 404 (:status (POST-as "agent-service" "/api/agent/ops/test/echo/extra" {:user "alice"}))))))

(deftest path-scope-test
  (is (nil? (sut/handler (mock/request :get "/api/agentX"))))
  (is (nil? (sut/handler (mock/request :get "/ws")))))
