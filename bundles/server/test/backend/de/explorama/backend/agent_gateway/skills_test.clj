(ns de.explorama.backend.agent-gateway.skills-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [de.explorama.backend.agent-gateway.skills :as sut]))

(deftest example-test
  (is (= {:frame-id '<value> :action :group-by :params '<value>}
         (sut/example [:map {:closed true} [:frame-id :any] [:action [:enum :group-by :ungroup]] [:params {:optional true} map?]])))
  (is (= {:rows [["text" '<value>]] :title "text" :n 1 :flag true :ids #{"text"}}
         (sut/example [:map [:rows [:vector [:tuple string? :any]]] [:title [:string {:min 1}]] [:n int?] [:flag boolean?] [:ids [:set string?]]]))))

(deftest rendered-skills-are-committed-test
  (testing "agent/.claude/skills matches the catalog; run `bb agent-skills` after changing an op"
    (doseq [[path content] (sut/render-all)]
      (let [file (io/file sut/skills-dir path)]
        (is (.exists file) (str path " is missing"))
        (when (.exists file)
          (is (= content (slurp file)) (str path " is stale")))))))
