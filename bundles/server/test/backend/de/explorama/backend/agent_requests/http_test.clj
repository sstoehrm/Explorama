(ns de.explorama.backend.agent-requests.http-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [de.explorama.backend.agent-requests.auth :as auth]
            [de.explorama.backend.agent-requests.http :as sut]
            [de.explorama.backend.agent-requests.proxy-auth :as proxy-auth]
            [de.explorama.backend.agent-requests.registry :as registry]
            [de.explorama.backend.agent-requests.store :as store]
            [ring.mock.request :as mock]))

(use-fixtures :each (fn [f]
                      (registry/reset-types!)
                      (store/reset-store!)
                      (registry/register-type! {:id :test/greeting
                                                :description "Return a greeting"
                                                :output-schema [:map [:greeting string?]]
                                                :output-example {:greeting "hello"}
                                                :on-fulfilled (fn [_ _] nil)})
                      (proxy-auth/init)
                      (f)
                      (auth/reset-authenticator!)))

(defn- create! []
  (store/create! {:type :test/greeting
                  :input {:name "Ada"}
                  :user "tester"
                  :context {:reply :callback}}))

(defn- body-edn [response]
  (edn/read-string (:body response)))

(defn- GET [path]
  (sut/handler (-> (mock/request :get path)
                   (mock/header "X-Auth-Request-User" "agent-service"))))

(defn- POST [path body]
  (sut/handler (-> (mock/request :post path)
                   (mock/content-type "application/edn")
                   (mock/header "X-Auth-Request-User" "agent-service")
                   (mock/body (pr-str body)))))

(deftest types-test
  (testing "declared types are served without their handler fn"
    (let [response (GET "/api/agent-requests/types")
          {[declaration] :types} (body-edn response)]
      (is (= 200 (:status response)))
      (is (= "application/edn" (get-in response [:headers "Content-Type"])))
      (is (= :test/greeting (:id declaration)))
      (is (= {:greeting "hello"} (:output-example declaration)))
      (is (= :map (first (:output-schema declaration)))
          "the schema is served as its malli form, not as an opaque object")
      (is (not (contains? declaration :on-fulfilled))))))

(deftest list-test
  (testing "open requests are served with their input but nothing private"
    (let [{:keys [id]} (create!)
          {:keys [requests]} (body-edn (GET "/api/agent-requests"))]
      (is (= [id] (mapv :id requests)))
      (is (= {:name "Ada"} (:input (first requests))))
      (is (= #{:id :type :input :created-at} (set (keys (first requests)))))))
  (testing "the type filter is applied"
    (is (empty? (:requests (body-edn (GET "/api/agent-requests?type=:other/type")))))))

(deftest list-store-exception-not-mislabeled-test
  (testing "an exception from the listing path itself is not reported as a malformed type filter"
    (with-redefs [store/open-requests (fn [_] (throw (ex-info "boom" {})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
                            (GET "/api/agent-requests?type=:test/greeting"))))))

(deftest claim-test
  (testing "claiming returns the lease"
    (let [{:keys [id]} (create!)
          response (POST (str "/api/agent-requests/" id "/claim") {:agent "agent-1"})]
      (is (= 200 (:status response)))
      (is (number? (:lease-expires-at (body-edn response))))))
  (testing "a second claim conflicts"
    (let [{:keys [id]} (create!)]
      (POST (str "/api/agent-requests/" id "/claim") {:agent "agent-1"})
      (is (= 409 (:status (POST (str "/api/agent-requests/" id "/claim") {:agent "agent-2"}))))))
  (testing "an unknown id is 404"
    (is (= 404 (:status (POST "/api/agent-requests/nope/claim" {:agent "agent-1"})))))
  (testing "a cancelled request is 410"
    (let [{:keys [id]} (create!)]
      (store/cancel! id)
      (is (= 410 (:status (POST (str "/api/agent-requests/" id "/claim") {:agent "agent-1"})))))))

(deftest result-test
  (testing "a valid result is accepted"
    (let [{:keys [id]} (create!)]
      (POST (str "/api/agent-requests/" id "/claim") {:agent "agent-1"})
      (let [response (POST (str "/api/agent-requests/" id "/result") {:greeting "moin"})]
        (is (= 200 (:status response)))
        (is (= {:status :fulfilled} (body-edn response)))
        (is (= :fulfilled (:status (store/get-request id)))))))
  (testing "an invalid result is 422 and explains itself"
    (let [{:keys [id]} (create!)]
      (POST (str "/api/agent-requests/" id "/claim") {:agent "agent-1"})
      (let [response (POST (str "/api/agent-requests/" id "/result") {:greeting 42})]
        (is (= 422 (:status response)))
        (is (= :invalid (:error (body-edn response))))
        (is (some? (:explanation (body-edn response)))))))
  (testing "a result without a claim is 409"
    (let [{:keys [id]} (create!)]
      (is (= 409 (:status (POST (str "/api/agent-requests/" id "/result") {:greeting "moin"}))))))
  (testing "a malformed body is 400"
    (let [{:keys [id]} (create!)
          response (sut/handler (-> (mock/request :post (str "/api/agent-requests/" id "/result"))
                                    (mock/content-type "application/edn")
                                    (mock/header "X-Auth-Request-User" "agent-service")
                                    (mock/body "{:greeting")))]
      (is (= 400 (:status response))))))

(deftest list-malformed-type-test
  (testing "a malformed type filter is a 400 with a structured error, not an uncaught exception"
    (let [response (GET "/api/agent-requests?type=%5B")]
      (is (= 400 (:status response)))
      (is (= "application/edn" (get-in response [:headers "Content-Type"])))
      (is (= {:error :malformed-type} (body-edn response))))))

(deftest deeply-nested-body-test
  (testing "a pathologically nested body is a 400, not an uncaught StackOverflowError"
    (let [{:keys [id]} (create!)
          response (sut/handler (-> (mock/request :post (str "/api/agent-requests/" id "/claim"))
                                    (mock/content-type "application/edn")
                                    (mock/header "X-Auth-Request-User" "agent-service")
                                    (mock/body (apply str (repeat 200000 "[")))))]
      (is (= 400 (:status response)))
      (is (= {:error :malformed-body} (body-edn response))))))

(deftest fail-test
  (testing "an agent can give up and the reason is kept"
    (let [{:keys [id]} (create!)]
      (POST (str "/api/agent-requests/" id "/claim") {:agent "agent-1"})
      (is (= 200 (:status (POST (str "/api/agent-requests/" id "/fail") {:reason "no idea"}))))
      (is (= :failed (:status (store/get-request id))))
      (is (= "no idea" (:reason (store/get-request id)))))))

(deftest unauthenticated-test
  (testing "the api is closed without a principal header"
    (let [response (sut/handler (mock/request :get "/api/agent-requests"))]
      (is (= 401 (:status response)))
      (is (= {:error :unauthorized} (body-edn response))))))

(deftest other-paths-fall-through-test
  (testing "a path this api does not own is not answered at all"
    (is (nil? (sut/handler (mock/request :get "/some/other/path"))))
    (is (nil? (sut/handler (mock/request :get "/ws"))))))
