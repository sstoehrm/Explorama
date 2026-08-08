(ns de.explorama.frontend.indicator.graph-management-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [de.explorama.frontend.indicator.path :as ip]
            [de.explorama.frontend.indicator.views.graph-management :as gm]))

(def graph-text
  "{:nodes {:s {:type :datasource :dataset 1}\n         :g {:type :operation :op :group-by :params {:attributes [\"year\"]}}\n         :a {:type :operation :op :sum :params {:attribute \"fact-1\"}}\n         :o {:type :result :name \"my-result\"}}\n :edges {[:s :g] {} [:g :a] {} [:a :o] {}}}")

(def db
  (-> {}
      (assoc-in (ip/graph-desc "g-1") {:id "g-1" :name "my-result" :creator "u"})
      (assoc-in (ip/graph-text "g-1") graph-text)
      (assoc-in (ip/indicator-data "g-1") {"di-1" {:di/data-tile-ref {"di-1" {:di/identifier "search"}}}})))

(deftest validation-state-test
  (let [validated (gm/validate-graph-state db "g-1")]
    (testing "clean graph"
      (is (nil? (get-in validated (conj (ip/graph-validation "g-1") :parse-error))))
      (is (empty? (get-in validated (conj (ip/graph-validation "g-1") :errors)))))
    (testing "parse error keeps previous validation"
      (let [broken (assoc-in validated (ip/graph-text "g-1") "{:nodes {")
            revalidated (gm/validate-graph-state broken "g-1")]
        (is (string? (get-in revalidated (conj (ip/graph-validation "g-1") :parse-error))))
        (is (empty? (get-in revalidated (conj (ip/graph-validation "g-1") :errors))))))))

(deftest final-artifact-test
  (let [{:keys [artifact errors]} (gm/graph-artifact->final (gm/validate-graph-state db "g-1") "g-1")]
    (is (nil? errors))
    (is (= "g-1" (:id artifact)))
    (is (= graph-text (:graph-text artifact)))
    (is (= :heal-event (first (:calculation-desc artifact))))
    (is (= ["di-1"] (keys (:dis artifact))))))

(deftest dataset-bindings-test
  (testing "numbers datasets from connected-data key order"
    (is (= {1 "di-1"}
           (gm/dataset-bindings db "g-1"))))
  (testing "multiple datasets get consecutive numbers"
    (let [multi-db (assoc-in db (ip/indicator-data "g-2")
                             {"di-a" {} "di-b" {}})]
      (is (= {1 "di-a" 2 "di-b"}
             (gm/dataset-bindings multi-db "g-2"))))))

(deftest text-dirty-test
  (testing "no persisted text means new/unsaved text is dirty"
    (is (true? (gm/text-dirty? db "g-1"))))
  (testing "text matching the persisted artifact is not dirty"
    (let [saved-db (assoc-in db (conj (ip/graph-desc "g-1") :graph-text) graph-text)]
      (is (false? (gm/text-dirty? saved-db "g-1")))))
  (testing "edited text differs from the persisted artifact"
    (let [saved-db (-> db
                       (assoc-in (conj (ip/graph-desc "g-1") :graph-text) graph-text)
                       (assoc-in (ip/graph-text "g-1") "{:nodes {} :edges {}}"))]
      (is (true? (gm/text-dirty? saved-db "g-1"))))))
