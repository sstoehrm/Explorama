(ns de.explorama.backend.indicator.graph-request-test
  (:require #?(:clj  [clojure.test :as t :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :as t :refer [deftest testing is use-fixtures] :include-macros true])
            [de.explorama.backend.agent-requests.registry :as registry]
            [de.explorama.backend.agent-requests.store :as agent-store]
            [de.explorama.backend.indicator.graph-request :as graph-request]
            [de.explorama.shared.agent-requests.schema :as ar-schema]))

(use-fixtures :each (fn [f]
                      (registry/reset-types!)
                      (agent-store/reset-store!)
                      (f)
                      (registry/reset-types!)
                      (agent-store/reset-store!)))

(deftest register-test
  (testing "declaration is valid"
    (is (map? (graph-request/register!)))
    (is (= :indicator/aggregation-graph
           (:id (registry/type-declaration :indicator/aggregation-graph)))))
  (testing "output example satisfies output schema"
    (let [{:keys [output-schema output-example]}
          (registry/type-declaration :indicator/aggregation-graph)]
      (is (nil? (ar-schema/explain-value output-schema output-example))))))

(deftest on-fulfilled-test
  (graph-request/register!)
  (let [{:keys [on-fulfilled]} (registry/type-declaration :indicator/aggregation-graph)
        received (atom nil)]
    (on-fulfilled {:context {:client-callback #(reset! received %)}} {:nodes {} :edges {}})
    (is (= {:nodes {} :edges {}} @received))))

(deftest request-generation-test
  (graph-request/register!)
  (let [failed (atom nil)]
    (graph-request/request-generation
     {:client-callback (fn [_]) :failed-callback #(reset! failed %)}
     [{:username ""} "make me a graph" [] "corr-1"])
    (is (= "corr-1" (:id @failed)))))

(deftest request-generation-correlation-test
  (graph-request/register!)
  (testing "the client-generated id is echoed back alongside a fulfilled graph"
    (let [answered (atom nil)]
      (graph-request/request-generation
       {:client-callback #(reset! answered %) :failed-callback (fn [_] nil)}
       [{:username "tester"} "make me a graph" [] "corr-1"])
      (let [{:keys [id]} (first (agent-store/open-requests graph-request/request-type))]
        (agent-store/claim! id "agent-1")
        (agent-store/submit! id "agent-1" {:nodes {} :edges {}}))
      (is (= {:graph {:nodes {} :edges {}} :id "corr-1"} @answered))))
  (testing "the client-generated id is echoed back alongside an explicit failure"
    (let [failed (atom nil)]
      (graph-request/request-generation
       {:client-callback (fn [_] nil) :failed-callback #(reset! failed %)}
       [{:username "tester"} "make me a graph" [] "corr-2"])
      (let [{:keys [id]} (first (agent-store/open-requests graph-request/request-type))]
        (agent-store/claim! id "agent-1")
        (agent-store/fail! id "agent-1" "no idea"))
      (is (= "corr-2" (:id @failed)))
      (is (= "no idea" (:error @failed))))))
