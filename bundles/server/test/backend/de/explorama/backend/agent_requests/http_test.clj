(ns de.explorama.backend.agent-requests.http-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [de.explorama.backend.agent-requests.auth :as auth]
            [de.explorama.backend.agent-requests.http :as sut]
            [de.explorama.backend.agent-requests.proxy-auth :as proxy-auth]
            [de.explorama.backend.agent-requests.registry :as registry]
            [de.explorama.backend.agent-requests.store :as store]
            [org.httpkit.client :as client]
            [org.httpkit.server :as hk]
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
                      (try
                        (f)
                        (finally
                          (auth/reset-authenticator!)))))

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
    (is (nil? (sut/handler (mock/request :get "/ws"))))
    (is (nil? (sut/handler (mock/request :get "/api/agent-requestsX")))
        "a path that merely shares the prefix is not this api's to answer")))

(deftest forbidden-test
  (testing "a principal outside a configured allow-list is forbidden, not unauthorized"
    (with-redefs [proxy-auth/allowed-principals #{"someone-else"}]
      (let [response (GET "/api/agent-requests")]
        (is (= 403 (:status response)))
        (is (= {:error :forbidden} (body-edn response)))))))

(deftest agent-principal-attached-test
  (testing "the authenticated principal reaches the handler on the request"
    (let [seen (atom nil)]
      (with-redefs [sut/types-handler (fn [request] (reset! seen request) {:status 200 :headers {} :body ""})]
        (GET "/api/agent-requests/types"))
      (is (= "agent-service" (:agent-principal @seen))))))

(defn- deferred-GET
  "Runs a long-polling GET with the async responder stubbed out. Returns an
  atom that stays empty until the deferred answer is sent. The stub answers
  with a map rather than a bare keyword because the route still runs the
  return value through compojure's response rendering, which only knows how
  to render Ring-shaped values."
  [path]
  (let [delivered (atom nil)]
    (binding [sut/*async-respond-fn* (fn [_request respond]
                                       (respond (fn [response] (reset! delivered response)))
                                       {:body :deferred})]
      (is (= :deferred (:body (GET path)))))
    delivered))

(deftest long-poll-test
  (testing "existing work answers immediately without waiting"
    (let [{:keys [id]} (create!)
          response (GET "/api/agent-requests?wait=30")]
      (is (= 200 (:status response)))
      (is (= [id] (mapv :id (:requests (body-edn response)))))))
  (testing "an empty queue defers the answer"
    (store/reset-store!)
    (is (nil? @(deferred-GET "/api/agent-requests?wait=30"))))
  (testing "a newly filed request wakes the waiting agent"
    (store/reset-store!)
    (let [delivered (deferred-GET "/api/agent-requests?wait=30")
          {:keys [id]} (create!)]
      (is (= [id] (mapv :id (:requests (body-edn @delivered)))))))
  (testing "a request of another type does not wake a filtered wait"
    (store/reset-store!)
    (let [delivered (deferred-GET "/api/agent-requests?type=:other/type&wait=30")]
      (create!)
      (is (nil? @delivered)))))

(deftest wait-clamp-test
  (testing "a wait beyond the cap is clamped"
    (is (= sut/max-wait-seconds (sut/clamp-wait "9999"))))
  (testing "a missing or unparseable wait is zero"
    (is (= 0 (sut/clamp-wait nil)))
    (is (= 0 (sut/clamp-wait "soon")))
    (is (= 0 (sut/clamp-wait "-5")))))

(deftest lost-wakeup-race-test
  (testing "a request created exactly while the watcher is being registered still wakes the waiter"
    (store/reset-store!)
    (let [created (atom nil)
          original-watch! store/watch!
          delivered (with-redefs [store/watch! (fn [key callback]
                                                 (reset! created (create!))
                                                 (original-watch! key callback))]
                     (deferred-GET "/api/agent-requests?wait=30"))]
      (is (some? @created)
          "the redef must have actually run for this test to mean anything")
      (is (some? @delivered)
          "without a re-check after registration, a request created in this
          exact window is lost until the wait elapses")
      (is (= [(:id @created)] (mapv :id (:requests (body-edn @delivered))))))))

(deftest deferred-cleanup-contract-test
  (testing "the cleanup handed back by deferred-list answers exactly once and truly unregisters the watcher"
    (store/reset-store!)
    (let [delivered (atom [])
          cleanup (atom nil)]
      (binding [sut/*async-respond-fn* (fn [_request respond]
                                         (reset! cleanup
                                                 (respond (fn [response] (swap! delivered conj response))))
                                         {:body :deferred})]
        (GET "/api/agent-requests?wait=30"))
      (testing "simulating a channel close (and a timeout racing behind it) answers only once"
        (@cleanup)
        (@cleanup)
        (is (= 1 (count @delivered))
            "a second call to the same cleanup fn (e.g. an unclosed timer firing after
            close already ran) must not send a second answer"))
      (testing "the watcher was actually removed, not just short-circuited"
        (create!)
        (is (= 1 (count @delivered))
            "if unwatch! had not really run, this create! would deliver a second answer")))))

(deftest real-async-channel-test
  (testing "a deferred answer travels over a real http-kit channel end to end, no *async-respond-fn* stub"
    (store/reset-store!)
    (let [watch-registered (promise)
          original-watch! store/watch!
          server (hk/run-server sut/handler {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
      (try
        (with-redefs [store/watch! (fn [key callback]
                                     (original-watch! key callback)
                                     (deliver watch-registered true))]
          (let [port (hk/server-port server)
                response (client/get (str "http://127.0.0.1:" port "/api/agent-requests?wait=5")
                                     {:headers {"X-Auth-Request-User" "agent-service"}
                                      :as :text
                                      :timeout 10000})]
            (is (= true (deref watch-registered 2000 :timed-out))
                "the server must actually be waiting before we create work, or this
                test would only exercise the immediate, non-deferred path")
            (let [{:keys [id]} (create!)
                  {:keys [status body error]} (deref response 8000 {:status :timed-out})]
              (is (nil? error))
              (is (= 200 status))
              (is (= [id] (mapv :id (:requests (edn/read-string body))))))))
        (finally
          (hk/server-stop! server))))))
