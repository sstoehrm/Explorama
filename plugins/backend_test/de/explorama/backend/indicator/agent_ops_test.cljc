(ns de.explorama.backend.indicator.agent-ops-test
  (:require #?(:clj [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [de.explorama.backend.agent-gateway.dispatcher :as dispatcher]
            [de.explorama.backend.indicator.agent-ops :as sut]
            [de.explorama.backend.indicator.persistence.graphs :as graphs]))

(use-fixtures :each (fn [f] (dispatcher/reset-registry!) (sut/register!) (f) (dispatcher/reset-registry!)))

(def ^:private graph-text
  (pr-str {:nodes {:src {:type :datasource :dataset 1}
                   :grouped {:type :operation :op :group-by :params {:attributes ["year"]}}
                   :total {:type :operation :op :sum :params {:attribute "cases"}}
                   :out {:type :result :name "cases-per-year"}}
           :edges {[:src :grouped] {:direction :->}
                   [:grouped :total] {:direction :->}
                   [:total :out] {:direction :-> :as "indicator"}}}))

(def ^:private cyclic-graph-text
  (pr-str {:nodes {:a {:type :operation :op :group-by :params {:attributes ["year"]}}
                   :b {:type :operation :op :sum :params {:attribute "cases"}}
                   :c {:type :operation :op :distinct :params {:attribute "cases"}}}
           :edges {[:a :b] {:direction :->}
                   [:b :c] {:direction :->}
                   [:c :a] {:direction :->}}}))

(deftest validate-test
  (testing "a well-formed graph validates without errors and carries the operation reference"
    (let [{:keys [status result]} (dispatcher/invoke {:op :indicator/validate-graph :user "alice"
                                                      :params {:graph-text graph-text :dataset-count 1}})]
      (is (= :ok status))
      (is (empty? (:errors result)))
      (is (map? (:operations result)))))
  (testing "unreadable text is invalid-params"
    (is (= :invalid-params (get-in (dispatcher/invoke {:op :indicator/validate-graph :user "alice" :params {:graph-text "{" :dataset-count 1}}) [:error :type])))))

(deftest create-test
  (with-redefs [graphs/create-new-graph (fn [user artifact] {:status :success :data (assoc artifact :creator (:username user))})]
    (is (= "alice" (get-in (dispatcher/invoke {:op :indicator/create-graph :user "alice"
                                               :params {:artifact {:id "g1" :name "G" :graph-text graph-text :dataset-bindings {}}}})
                           [:result :creator]))))
  (with-redefs [graphs/create-new-graph (fn [_ _] {:status :failed :msg :graph-not-valid :data {:reason [{:code :cycle :message "graph contains a cycle"}]}})]
    (let [response (dispatcher/invoke {:op :indicator/create-graph :user "alice"
                                       :params {:artifact {:id "g1" :name "G" :graph-text graph-text :dataset-bindings {}}}})]
      (is (= :invalid-params (get-in response [:error :type])))
      (is (= :graph-not-valid (get-in response [:error :msg])))
      (is (= [{:code :cycle :message "graph contains a cycle"}] (get-in response [:error :data :reason]))))))

(deftest create-new-graph-real-failure-shape-test
  (testing "the real persistence layer's failure shape is {:status :failed :msg .. :data {:reason ..}}, not :errors - grounds the mock above; exercised without a persisted expdb because a cyclic graph fails validation before the store is ever touched"
    (let [artifact {:id "g1" :name "G" :creator "alice" :graph-text cyclic-graph-text
                    :dis {} :calculation-desc [] :dataset-bindings {}}
          {:keys [status msg data]} (graphs/create-new-graph {:username "alice"} artifact)]
      (is (= :failed status))
      (is (= :graph-not-valid msg))
      (is (= :cycle (:code (first (:reason data))))))))

(deftest graph-unknown-id-test
  (with-redefs [graphs/read-graph (fn [_] nil)]
    (is (= :invalid-params (get-in (dispatcher/invoke {:op :indicator/graph :user "alice" :params {:id "missing"}})
                                   [:error :type])))))

(deftest publish-graph-unknown-id-test
  (with-redefs [graphs/read-graph (fn [_] nil)]
    (is (= :invalid-params (get-in (dispatcher/invoke {:op :indicator/publish-graph :user "alice" :params {:id "missing"}})
                                   [:error :type])))))
