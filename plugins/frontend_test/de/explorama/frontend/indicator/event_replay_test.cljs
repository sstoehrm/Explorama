(ns de.explorama.frontend.indicator.event-replay-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [de.explorama.frontend.indicator.event-replay :as event-replay]
            [de.explorama.frontend.indicator.path :as ip]))

(deftest restore-graph-desc-test
  (testing "writes the graph into the project-graphs map, stripped of write access"
    (let [graph {:id "g-1" :name "shared aggregation" :write-access? true}
          result (event-replay/restore-graph-desc {} nil [::callback] {:graph graph} nil)]
      (is (= (assoc graph :write-access? false)
             (get-in (:db result) (ip/project-graph-desc "g-1"))))
      (is (= [::callback] (:dispatch result)))))
  (testing "does not touch the owned graphs map"
    (let [graph {:id "g-2" :name "shared aggregation"}
          result (event-replay/restore-graph-desc {} nil [::callback] {:graph graph} nil)]
      (is (nil? (get-in (:db result) (ip/graph-desc "g-2"))))))
  (testing "is registered under [\"restore-graph-desc\" 1]"
    (is (= event-replay/restore-graph-desc
           (event-replay/event-func "restore-graph-desc" 1)))))
