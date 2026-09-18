(ns de.explorama.frontend.projects.agent-ops-test
  (:require [cljs.test :refer-macros [deftest is]]
            [de.explorama.frontend.projects.agent-ops :as sut]
            [de.explorama.frontend.projects.path :as path]))

(deftest create-test
  (let [{new-db :db event :dispatch later :dispatch-later} (sut/create {:db {} :params {:title "T" :description "D"} :ok identity :fail identity})]
    (is (= "T" (get-in new-db path/new-project-title)))
    (is (= "D" (get-in new-db path/new-project-desc)))
    (is (= [:de.explorama.frontend.projects.views.create-project/create] event))
    (is (= :de.explorama.frontend.woco.agent-ops/reply-with (first (:dispatch (first later)))))))

(deftest load-unknown-project-test
  (let [failed (atom nil)]
    (is (= {} (sut/load {:db {} :params {:project-id "missing"} :ok identity
                         :fail (fn [type message] (reset! failed [type message]))})))
    (is (= [:invalid-params "unknown project-id"] @failed))))

(deftest load-known-project-test
  (let [project {:project-id "p1" :creator "alice"}
        db (assoc-in {} [:projects :projects :created-projects "p1"] project)
        {event :dispatch later :dispatch-later} (sut/load {:db db :params {:project-id "p1"} :ok identity :fail identity})]
    (is (= [:de.explorama.frontend.projects.core/start-loading-project project nil] event))
    (is (= :de.explorama.frontend.woco.agent-ops/reply-with (first (:dispatch (first later)))))))
