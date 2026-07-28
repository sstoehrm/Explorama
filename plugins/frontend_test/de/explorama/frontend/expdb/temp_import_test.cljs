(ns de.explorama.frontend.expdb.temp-import-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [de.explorama.frontend.expdb.path :as path]
            [de.explorama.frontend.expdb.temp-import.core :as sut]))

(def ^:private mapping
  {:meta-data {:file-format :csv
               :csv {:separator ";" :quote "\""}}
   :mapping {:datasource {:name [:value "Cases"]
                          :global-id [:value "source-cases"]}
             :items [{:global-id [:field "id"]
                      :features [{:facts []
                                  :locations []
                                  :contexts []
                                  :dates []
                                  :texts []}]}]}})

(deftest apply-agent-mapping-test
  (testing "the agent mapping replaces the heuristic one and clears pending"
    (let [db (-> {}
                 (assoc-in path/agent-pending? true)
                 (assoc-in path/current-data [{:row-number 0 "id" "1"}])
                 (sut/apply-agent-mapping mapping))]
      (is (false? (get-in db path/agent-pending?)))
      (is (= mapping (get-in db path/raw-mapping)))
      (is (= (:meta-data mapping) (get-in db path/meta-data)))
      (is (= (get-in mapping [:mapping :datasource]) (get-in db path/datasource)))
      (is (some? (get-in db path/current-mapping)))
      (is (= [{:row-number 0 "id" "1"}] (get-in db path/current-data))
          "the preview rows are untouched"))))

(deftest agent-failure-test
  (testing "a failure clears pending and records the message"
    (let [db (-> {}
                 (assoc-in path/agent-pending? true)
                 (sut/set-agent-error "no idea"))]
      (is (false? (get-in db path/agent-pending?)))
      (is (= "no idea" (get-in db path/agent-error)))))
  (testing "starting a new request clears a previous error"
    (let [db (-> {}
                 (sut/set-agent-error "no idea")
                 (sut/set-agent-pending))]
      (is (true? (get-in db path/agent-pending?)))
      (is (nil? (get-in db path/agent-error))))))

(deftest handle-agent-mapping-result-test
  (testing "the matching-id happy path still applies the mapping exactly as today"
    (let [db (-> {}
                 (assoc-in path/agent-pending? true)
                 (assoc-in path/agent-request-id "current-id")
                 (assoc-in path/current-data [{:row-number 0 "id" "1"}])
                 (sut/handle-agent-mapping-result (assoc mapping :id "current-id")))]
      (is (false? (get-in db path/agent-pending?)))
      (is (nil? (get-in db path/agent-request-id)))
      (is (= mapping (get-in db path/raw-mapping)))
      (is (= (:meta-data mapping) (get-in db path/meta-data)))
      (is (= (get-in mapping [:mapping :datasource]) (get-in db path/datasource)))
      (is (some? (get-in db path/current-mapping)))
      (is (= [{:row-number 0 "id" "1"}] (get-in db path/current-data))
          "the preview rows are untouched")))
  (testing "a result whose id does not match the stored id changes nothing"
    (let [db (-> {}
                 (assoc-in path/agent-pending? true)
                 (assoc-in path/agent-request-id "current-id")
                 (assoc-in path/current-data [{:row-number 0 "id" "1"}]))
          result (sut/handle-agent-mapping-result db (assoc mapping :id "stale-id"))]
      (is (= db result)))))

(deftest handle-agent-mapping-failed-test
  (testing "a failure whose id does not match the stored id changes nothing"
    (let [db (-> {}
                 (assoc-in path/agent-pending? true)
                 (assoc-in path/agent-request-id "current-id"))
          result (sut/handle-agent-mapping-failed db {:id "stale-id" :error "no idea"})]
      (is (= db result))))
  (testing "a failure whose id matches records the message and clears the id"
    (let [db (-> {}
                 (assoc-in path/agent-pending? true)
                 (assoc-in path/agent-request-id "current-id")
                 (sut/handle-agent-mapping-failed {:id "current-id" :error "no idea"}))]
      (is (false? (get-in db path/agent-pending?)))
      (is (= "no idea" (get-in db path/agent-error)))
      (is (nil? (get-in db path/agent-request-id))))))

(deftest handle-agent-mapping-timeout-test
  (testing "a timeout scheduled for an earlier id does not clear the pending state of a later request"
    (let [db (-> {}
                 (assoc-in path/agent-pending? true)
                 (assoc-in path/agent-request-id "second-id"))
          result (sut/handle-agent-mapping-timeout db "first-id")]
      (is (true? (get-in result path/agent-pending?)))
      (is (= "second-id" (get-in result path/agent-request-id)))
      (is (nil? (get-in result path/agent-error))))))

(deftest cancel-agent-mapping-test
  (testing "cancelling invalidates the request id so a late-arriving result changes nothing"
    (let [db (-> {}
                 (assoc-in path/agent-pending? true)
                 (assoc-in path/agent-request-id "cancelled-id")
                 (sut/cancel-agent-mapping))
          result (sut/handle-agent-mapping-result db (assoc mapping :id "cancelled-id"))]
      (is (false? (get-in db path/agent-pending?)))
      (is (nil? (get-in db path/agent-request-id)))
      (is (= db result)
          "the late result is ignored because the id no longer matches"))))
