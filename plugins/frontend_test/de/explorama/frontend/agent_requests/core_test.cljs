(ns de.explorama.frontend.agent-requests.core-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [de.explorama.frontend.agent-requests.core :as sut]
            [de.explorama.frontend.agent-requests.path :as path]
            [de.explorama.frontend.agent-requests.refresh :as refresh]))

(def ^:private requests
  [{:id "r1" :type :data-transformer/mapping :status :open :created-at 1000 :claimed-by nil}
   {:id "r2" :type :data-transformer/mapping :status :claimed :created-at 900 :claimed-by "agent-1"}
   {:id "r3" :type :data-transformer/mapping :status :fulfilled :created-at 800 :claimed-by "agent-1"}])

(deftest list-result-test
  (testing "the result event stores the list"
    (let [db (sut/set-requests {} requests)]
      (is (= requests (get-in db path/requests))))))

(defn- with-running-refresh [f]
  (refresh/start! refresh/state (constantly nil) [:noop]
                   (fn [cb _ms] cb) refresh/default-interval-ms)
  (try
    (f)
    (finally
      (refresh/stop!))))

(deftest clean-workspace-test
  (testing "clears the plugin's db subtree and stops any pending refresh"
    (with-running-refresh
      (fn []
        (is (true? (refresh/running?)))
        (let [db (sut/set-requests {} requests)
              {new-db :db dispatch :dispatch} (sut/clean-workspace-fx {:db db} [nil [:follow] :logout])]
          (is (= [] (get-in new-db path/requests)))
          (is (= [:follow ::sut/clean-workspace] dispatch))
          (is (false? (refresh/running?))
              "clean-workspace stops the refresh loop"))))))

(deftest logout-test
  (testing "stops any pending refresh"
    (with-running-refresh
      (fn []
        (is (true? (refresh/running?)))
        (sut/logout-fx nil nil)
        (is (false? (refresh/running?)))))))
