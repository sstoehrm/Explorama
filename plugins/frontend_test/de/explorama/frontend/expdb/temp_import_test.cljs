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
