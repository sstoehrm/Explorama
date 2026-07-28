(ns de.explorama.frontend.agent-requests.core-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [de.explorama.frontend.agent-requests.core :as sut]
            [de.explorama.frontend.agent-requests.path :as path]))

(def ^:private requests
  [{:id "r1" :type :data-transformer/mapping :status :open :created-at 1000 :claimed-by nil}
   {:id "r2" :type :data-transformer/mapping :status :claimed :created-at 900 :claimed-by "agent-1"}
   {:id "r3" :type :data-transformer/mapping :status :fulfilled :created-at 800 :claimed-by "agent-1"}])

(deftest list-result-test
  (testing "the result event stores the list"
    (let [db (sut/set-requests {} requests)]
      (is (= requests (get-in db path/requests))))))

(deftest open-count-test
  (testing "open and claimed requests count as pending"
    (is (= 2 (sut/open-count requests))))
  (testing "an empty list counts as none"
    (is (= 0 (sut/open-count [])))))

(deftest sidebar-state-test
  (testing "opening marks the sidebar open, closing clears the list"
    (let [db (-> {}
                 (sut/set-open true)
                 (sut/set-requests requests))]
      (is (true? (get-in db path/open?)))
      (let [db (sut/set-open db false)]
        (is (false? (get-in db path/open?)))))))
