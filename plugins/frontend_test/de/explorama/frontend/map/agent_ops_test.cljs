(ns de.explorama.frontend.map.agent-ops-test
  (:require [cljs.test :refer-macros [deftest is]]
            [de.explorama.frontend.map.agent-ops :as sut]
            [de.explorama.frontend.woco.path :as wpath]))

(def ^:private fid {:frame-id "map-1" :vertical "map"})

(def ^:private with-frame-db (assoc-in {} (wpath/frame-desc fid) {}))

(deftest operation-test
  (let [{new-db :db event :dispatch} (sut/operation {:db with-frame-db :params {:frame-id fid :action :base-layer :params {:layer "osm"}} :ok identity :fail identity})]
    (is (= [:de.explorama.frontend.map.operations.tasks/execute-wrapper fid :base-layer {:layer "osm"}] event))
    (is (some? (get-in new-db [:queue fid :event-meta :callback])))))

(deftest operation-unknown-frame-test
  (let [failed (atom nil)]
    (is (= {} (sut/operation {:db {} :params {:frame-id fid :action :base-layer :params {:layer "osm"}} :ok identity
                              :fail (fn [type message] (reset! failed [type message]))})))
    (is (= [:invalid-params "unknown frame-id"] @failed))))
