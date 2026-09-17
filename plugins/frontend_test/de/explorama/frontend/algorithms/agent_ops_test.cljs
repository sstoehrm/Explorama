(ns de.explorama.frontend.algorithms.agent-ops-test
  (:require [cljs.test :refer-macros [deftest is]]
            [de.explorama.frontend.algorithms.agent-ops :as sut]
            [de.explorama.frontend.algorithms.event-logging :as event-logging]
            [de.explorama.frontend.woco.path :as wpath]))

(def ^:private fid {:frame-id "algorithms-1" :vertical "algorithms"})

(def ^:private with-frame-db (assoc-in {} (wpath/frame-desc fid) {}))

(deftest set-value-test
  (with-redefs [event-logging/ui-value-changed-event (fn [db frame-id callback-vec params]
                                                       {:db (assoc db :seen [frame-id callback-vec params]) :fx []})]
    (let [{new-db :db fx :fx} (sut/set-value {:db with-frame-db :params {:frame-id fid :state-key :settings :path [:horizon] :value 5} :ok identity :fail identity})]
      (is (= [fid nil {:state-key :settings :path [:horizon] :value 5}] (:seen new-db)))
      (is (= :de.explorama.frontend.woco.agent-ops/reply-with (first (second (last fx))))))))

(deftest set-value-unknown-frame-test
  (let [failed (atom nil)]
    (is (= {} (sut/set-value {:db {} :params {:frame-id fid :state-key :settings :path [:horizon] :value 5} :ok identity
                              :fail (fn [type message] (reset! failed [type message]))})))
    (is (= [:invalid-params "unknown frame-id"] @failed))))

(deftest submit-task-test
  (let [{event :dispatch} (sut/submit-task {:db with-frame-db :params {:frame-id fid :task {:algorithm "lr"}} :ok identity :fail identity})]
    (is (= [:de.explorama.frontend.algorithms.components.main/submit-task fid {:algorithm "lr"} false] (subvec event 0 4)))
    (is (= :de.explorama.frontend.woco.agent-ops/reply-with (first (nth event 4))))))

(deftest submit-task-unknown-frame-test
  (let [failed (atom nil)]
    (is (= {} (sut/submit-task {:db {} :params {:frame-id fid :task {:algorithm "lr"}} :ok identity
                                :fail (fn [type message] (reset! failed [type message]))})))
    (is (= [:invalid-params "unknown frame-id"] @failed))))
