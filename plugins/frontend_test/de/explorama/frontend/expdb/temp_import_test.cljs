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

(def ^:private heuristic-db
  (-> {}
      (assoc-in path/meta-data {:file-format :csv
                                :csv {:separator "," :quote "'"}})
      (assoc-in path/datasource {:name [:value "cases"]
                                 :global-id [:value "source-cases"]})))

(deftest mapping-seed-test
  (testing "the seed mirrors what the mapping step currently holds"
    (is (= {:generation 0
            :data-source "cases"
            :options {:type :csv :separator "," :quote "'"}}
           (sut/mapping-seed heuristic-db))))
  (testing "an agent's datasource and csv options become the new seed"
    (let [db (sut/apply-agent-mapping heuristic-db mapping)]
      (is (= {:generation 1
              :data-source "Cases"
              :options {:type :csv :separator ";" :quote "\""}}
             (sut/mapping-seed db))
          "an agent that corrects the separator or the datasource must not have
          its correction discarded by the stale local atoms"))))

(deftest reseed-mapping-inputs-test
  (testing "a new generation replaces the local atoms with the agent's values"
    (let [seeded (atom 0)
          data-source (atom "cases")
          options (atom {:type :csv :separator "," :quote "'"})
          seed (sut/mapping-seed (sut/apply-agent-mapping heuristic-db mapping))]
      (is (true? (sut/reseed-mapping-inputs! seeded data-source options seed)))
      (is (= "Cases" @data-source))
      (is (= {:type :csv :separator ";" :quote "\""} @options))
      (is (= 1 @seeded))))
  (testing "an unchanged generation leaves the user's own edits alone"
    (let [seeded (atom 0)
          data-source (atom "typed by the user")
          options (atom {:type :csv :separator "|" :quote "'"})]
      (is (nil? (sut/reseed-mapping-inputs! seeded data-source options
                                            (sut/mapping-seed heuristic-db))))
      (is (= "typed by the user" @data-source))
      (is (= {:type :csv :separator "|" :quote "'"} @options)))))

(deftest agent-failure-test
  (testing "a failure clears pending and keeps the agent's own prose to show"
    (let [db (-> {}
                 (assoc-in path/agent-pending? true)
                 (sut/set-agent-error "no idea"))]
      (is (false? (get-in db path/agent-pending?)))
      (is (= {:reason :agent :message "no idea"} (get-in db path/agent-error)))))
  (testing "starting a new request clears a previous error"
    (let [db (-> {}
                 (sut/set-agent-error "no idea")
                 (sut/set-agent-pending))]
      (is (true? (get-in db path/agent-pending?)))
      (is (nil? (get-in db path/agent-error))))))

(deftest agent-error-info-test
  (testing "a malli explanation is never shown as raw edn"
    (is (= {:reason :invalid}
           (sut/agent-error-info {:mapping {:items ["missing required key"]}})))
    (is (contains? sut/agent-error-labels :invalid)))
  (testing "a terminal status keeps its own explanation"
    (is (= {:reason :timeout} (sut/agent-error-info :timeout)))
    (is (= {:reason :expired} (sut/agent-error-info :expired)))
    (is (= {:reason :cancelled} (sut/agent-error-info :cancelled))))
  (testing "the agent's own prose is shown as given"
    (is (= {:reason :agent :message "cannot infer a date column"}
           (sut/agent-error-info "cannot infer a date column"))))
  (testing "anything else falls back to a generic reason"
    (is (= {:reason :unknown} (sut/agent-error-info nil)))
    (is (= {:reason :unknown} (sut/agent-error-info 42)))))

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
      (is (= {:reason :agent :message "no idea"} (get-in db path/agent-error)))
      (is (nil? (get-in db path/agent-request-id))))))

(deftest handle-agent-mapping-timeout-test
  (testing "a timeout scheduled for an earlier id does not clear the pending state of a later request"
    (let [db (-> {}
                 (assoc-in path/agent-pending? true)
                 (assoc-in path/agent-request-id "second-id"))
          result (sut/handle-agent-mapping-timeout db "first-id")]
      (is (true? (get-in result path/agent-pending?)))
      (is (= "second-id" (get-in result path/agent-request-id)))
      (is (nil? (get-in result path/agent-error)))))
  (testing "a timeout for the current request is shown as a timeout, not as raw edn"
    (let [db (-> {}
                 (assoc-in path/agent-pending? true)
                 (assoc-in path/agent-request-id "current-id")
                 (sut/handle-agent-mapping-timeout "current-id"))]
      (is (= {:reason :timeout} (get-in db path/agent-error))))))

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
