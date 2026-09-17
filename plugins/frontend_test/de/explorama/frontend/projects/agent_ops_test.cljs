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
