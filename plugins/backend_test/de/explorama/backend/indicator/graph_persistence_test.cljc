(ns de.explorama.backend.indicator.graph-persistence-test
  (:require #?(:clj  [clojure.test :as t :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :as t :refer [deftest testing is use-fixtures] :include-macros true])
            [de.explorama.backend.indicator.persistence.graphs :as graphs]
            [de.explorama.backend.indicator.calculate :as calc]
            [de.explorama.backend.expdb.middleware.db :as expdb]
            [de.explorama.shared.data-format.graph :as graph]))

(def user {:username "alice"})
(def other {:username "bob"})

(def artifact
  {:id "g-1" :name "death-rate" :creator "alice"
   :graph-text "{:nodes {:s {:type :datasource :dataset 1} :g {:type :operation :op :group-by :params {:attributes [\"year\"]}} :a {:type :operation :op :sum :params {:attribute \"f\"}} :o {:type :result :name \"death-rate\"}} :edges {[:s :g] {} [:g :a] {} [:a :o] {}}}"
   :dis {"di-1" {:di/data-tile-ref {"di-1" {:di/identifier "search"}}}}
   :calculation-desc [:heal-event {} [:sum {:attribute "f"} [:group-by {:attributes ["year"]} "di-1"]]]})

(use-fixtures :each (fn [f] (f) (expdb/del-bucket "/indicator/aggregation-graphs/")))

(deftest crud-test
  (testing "create + list + read"
    (is (= :success (:status (graphs/create-new-graph user artifact))))
    (is (= ["g-1"] (mapv :id (graphs/all-user-graphs user))))
    (is (= "death-rate" (:name (graphs/read-graph "g-1")))))
  (testing "invalid artifact rejected"
    (is (= :failed (:status (graphs/create-new-graph user (dissoc artifact :graph-text))))))
  (testing "graph-text that does not validate is rejected"
    (is (= :failed (:status (graphs/create-new-graph
                              user (assoc artifact :graph-text "{:nodes {} :edges {}}"))))))
  (testing "update by non-creator fails"
    (graphs/create-new-graph user artifact)
    (is (= :failed (:status (graphs/update-graph other (assoc artifact :name "x"))))))
  (testing "delete"
    (graphs/create-new-graph user artifact)
    (is (= :success (:status (graphs/delete-graph user {:id "g-1"}))))
    (is (empty? (graphs/all-user-graphs user)))))

(deftest share-test
  (graphs/create-new-graph user artifact)
  (let [{:keys [status data]} (graphs/share-with-user user other artifact)]
    (is (= :success status))
    (is (= "bob" (:creator data)))
    (is (= "alice" (:shared-by data)))
    (is (not= "g-1" (:id data)))))

(deftest publish-graph-di-test
  (let [parsed (:ok (graph/parse (:graph-text artifact)))
        {:keys [calculation-desc]} (graph/compile-graph parsed {1 "di-1"})
        stored (assoc artifact :calculation-desc calculation-desc)
        _ (graphs/create-new-graph user stored)
        result (atom nil)]
    (calc/create-graph-di-and-acs
     {:client-callback (fn [di project? desc] (reset! result [di project? desc]))}
     ["g-1" false])
    (let [[di _ desc] @result]
      (is (= "g-1" (:id desc)))
      (is (vector? (:di/operations di)))
      (is (= :heal-event (first (:di/operations di)))))))
