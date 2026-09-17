(ns de.explorama.frontend.mosaic.agent-ops-test
  (:require [cljs.test :refer-macros [deftest is]]
            [de.explorama.frontend.mosaic.agent-ops :as sut]
            [de.explorama.frontend.mosaic.path :as gp]
            [de.explorama.frontend.woco.path :as wpath]))

(def ^:private fid {:frame-id "mosaic-1" :vertical "mosaic"})

(def ^:private with-frame-db (assoc-in {} (wpath/frame-desc fid) {}))

(deftest operation-test
  (let [{new-db :db event :dispatch} (sut/operation {:db with-frame-db :params {:frame-id fid :action :group-by :params {:by "country"}} :ok identity :fail identity})]
    (is (= [:de.explorama.frontend.mosaic.operations.tasks/execute-wrapper (gp/top-level fid) :group-by {:by "country"}] event))
    (is (some? (get-in new-db [:queue fid :event-meta :callback])) "the reply is registered as the queue's completion callback")))

(deftest layouts-test
  (let [{event :dispatch} (sut/set-layouts {:db with-frame-db :params {:frame-id fid :layouts ["l1"]} :ok identity :fail identity})]
    (is (= [:de.explorama.frontend.mosaic.views.legend/change-layout fid ["l1"]] event))))

(deftest operation-unknown-frame-test
  (let [failed (atom nil)]
    (is (= {} (sut/operation {:db {} :params {:frame-id fid :action :group-by :params {:by "country"}} :ok identity
                              :fail (fn [type message] (reset! failed [type message]))})))
    (is (= [:invalid-params "unknown frame-id"] @failed))))
