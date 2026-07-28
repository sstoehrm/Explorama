(ns de.explorama.backend.agent-requests.round-trip-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [de.explorama.backend.agent-requests.auth :as auth]
            [de.explorama.backend.agent-requests.http :as http]
            [de.explorama.backend.agent-requests.proxy-auth :as proxy-auth]
            [de.explorama.backend.agent-requests.registry :as registry]
            [de.explorama.backend.agent-requests.store :as store]
            [de.explorama.backend.expdb.temp-import.mapping-request :as mapping-request]
            [de.explorama.shared.data-transformer.schema :as dt-schema]
            [ring.mock.request :as mock]))

(use-fixtures :each (fn [f]
                      (registry/reset-types!)
                      (store/reset-store!)
                      (mapping-request/register!)
                      (proxy-auth/init)
                      (with-redefs [proxy-auth/allowed-principals #{"agent-service"}]
                        (f))
                      (auth/reset-authenticator!)))

(def ^:private csv
  (str "id;country;date;cases\n"
       "1;Germany;01.02.2021;12\n"
       "2;France;02.02.2021;7\n"))

(def ^:private agent-mapping
  {:meta-data {:file-format :csv
               :csv {:separator ";" :quote "\""}}
   :mapping {:datasource {:name [:value "Cases"]
                          :global-id [:value "source-cases"]}
             :items [{:global-id [:field "id"]
                      :features [{:facts [{:value [:field "cases"]
                                           :name [:value "cases"]
                                           :type [:value "integer"]}]
                                  :locations []
                                  :contexts [{:name [:field "country"]
                                              :global-id [:id-generate ["country" :text] :name]
                                              :type [:value "country"]}]
                                  :dates [{:value [:date-schema "dd.MM.YYYY" [:field "date"]]
                                           :type [:value "occured-at"]}]
                                  :texts []}]}]}})

(defn- GET [path]
  (http/handler (-> (mock/request :get path)
                    (mock/header "X-Auth-Request-User" "agent-service"))))

(defn- POST [path body]
  (http/handler (-> (mock/request :post path)
                    (mock/header "X-Auth-Request-User" "agent-service")
                    (mock/content-type "application/edn")
                    (mock/body (pr-str body)))))

(deftest round-trip-test
  (let [answered (atom nil)]
    (store/create! {:type mapping-request/request-type
                    :input (mapping-request/input "cases.csv" csv {:file-format :csv
                                                                   :csv {:separator ";" :quote "\""}})
                    :user "tester"
                    :context {:client-callback #(reset! answered %)
                              :failed-callback (fn [_] nil)
                              :file-name "cases.csv"}})
    (testing "the agent sees the request with the raw head"
      (let [{[request] :requests} (edn/read-string (:body (GET "/api/agent-requests")))]
        (is (= "cases.csv" (get-in request [:input :file-name])))
        (is (= "id;country;date;cases" (first (get-in request [:input :raw-head]))))
        (testing "the listed request carries only its public fields, nothing internal"
          (is (= #{:id :type :input :created-at} (set (keys request)))
              "a future field added to the stored request must not silently start leaking
              through the public listing"))
        (testing "claiming and submitting a valid mapping answers the waiting client"
          (is (= 200 (:status (POST (str "/api/agent-requests/" (:id request) "/claim")
                                    {:agent "agent-1"}))))
          (is (= 200 (:status (POST (str "/api/agent-requests/" (:id request) "/result")
                                    agent-mapping))))
          (is (= agent-mapping @answered))
          (testing "and the mapping is one the importer accepts"
            (is (nil? (dt-schema/explain agent-mapping)))))))))

(deftest types-endpoint-describes-the-mapping-type
  (testing "the served example validates against the served schema"
    (let [{[declaration] :types} (edn/read-string (:body (GET "/api/agent-requests/types")))]
      (is (= :data-transformer/mapping (:id declaration)))
      (is (seq (:description declaration)))
      (is (nil? (dt-schema/explain (:output-example declaration))))
      (testing "the declaration carries only its public fields, not the handler fn"
        (is (= #{:id :description :output-schema :output-example} (set (keys declaration)))
            "a future field added to a type declaration must not silently start leaking
            through the public /types listing")))))
