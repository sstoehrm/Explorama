(ns de.explorama.frontend.charts.agent-ops-test
  (:require [cljs.test :refer-macros [deftest is]]
            [de.explorama.frontend.charts.agent-ops :as sut]
            [de.explorama.frontend.woco.path :as wpath]))

(def ^:private fid {:frame-id "charts-1" :vertical "charts"})

(def ^:private with-frame-db (assoc-in {} (wpath/frame-desc fid) {}))

(deftest set-state-test
  (let [{events :dispatch-n} (sut/set-state {:db with-frame-db :params {:frame-id fid :chart-desc {:type "bar"}} :ok identity :fail identity})]
    (is (= [:de.explorama.frontend.charts.vis-state/restore-vis-desc fid {:chart-desc {:type "bar"} :di nil :local-filter nil}] (first events)))
    (is (= :de.explorama.frontend.woco.agent-ops/reply-with (first (second events))))))

(deftest set-state-unknown-frame-test
  (let [failed (atom nil)]
    (is (= {} (sut/set-state {:db {} :params {:frame-id fid :chart-desc {:type "bar"}} :ok identity
                              :fail (fn [type message] (reset! failed [type message]))})))
    (is (= [:invalid-params "unknown frame-id"] @failed))))
