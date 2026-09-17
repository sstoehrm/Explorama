(ns de.explorama.frontend.search.agent-ops-test
  (:require [cljs.test :refer-macros [deftest is]]
            [de.explorama.frontend.search.agent-ops :as sut]
            [de.explorama.frontend.search.path :as spath]
            [de.explorama.frontend.woco.path :as wpath]))

(def ^:private fid {:frame-id "search-1" :vertical "search"})

(def ^:private with-frame-db (assoc-in {} (wpath/frame-desc fid) {}))

(deftest form-state-test
  (let [db (assoc-in {} (spath/frame-search-rows fid) {["country" "Context"] {:values ["Germany"]}})]
    (is (= {["country" "Context"] {:values ["Germany"]}} (sut/form-state db fid)))
    (is (= {} (sut/form-state db {:frame-id "other"})))))

(deftest set-form-test
  (let [{events :dispatch-n} (sut/set-form {:db with-frame-db :params {:frame-id fid :formdata {:a 1}} :ok identity :fail identity})]
    (is (= [:de.explorama.frontend.search.views.formdata/formdata fid {:a 1}] (first events)))
    (is (= :de.explorama.frontend.woco.agent-ops/reply-with (first (second events))))))

(deftest set-form-unknown-frame-test
  (let [failed (atom nil)]
    (is (= {} (sut/set-form {:db {} :params {:frame-id fid :formdata {:a 1}} :ok identity
                             :fail (fn [type message] (reset! failed [type message]))})))
    (is (= [:invalid-params "unknown frame-id"] @failed))))

(deftest run-test
  (let [db (-> with-frame-db
               (assoc-in (spath/frame-search-rows fid) {})
               (assoc-in spath/search-enabled-datasources #{"ds"}))
        {new-db :db events :dispatch-n} (sut/run {:db db :params {:frame-id fid} :ok identity :fail identity})]
    (is (true? (get-in new-db (spath/di-creation-pending fid))))
    (is (= #{:create} (get-in new-db (spath/frame-wait-callback-keys fid))))
    (is (= :de.explorama.frontend.woco.agent-ops/reply-with (first (get-in new-db (spath/frame-wait-callback fid)))))
    (is (= :de.explorama.shared.search.ws-api/create-di (first (last events))))
    (is (= [:de.explorama.frontend.search.backend.di/wait-callback fid :create] (last (last events))))))
