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
  (testing "single dataset with no :timestamp falls back to the tie-break"
    (is (= {1 "di-1"}
           (gm/dataset-bindings db "g-1"))))
  (testing "no :timestamp on any entry ties-break by di-id string"
    (let [multi-db (assoc-in db (ip/indicator-data "g-2")
                             {"di-a" {} "di-b" {}})]
      (is (= {1 "di-a" 2 "di-b"}
             (gm/dataset-bindings multi-db "g-2")))))
  (testing "numbers datasets by :timestamp, not by connected-data key order"
    (let [ts-db (assoc-in db (ip/indicator-data "g-3")
                          {"di-z" {:timestamp 200}
                           "di-a" {:timestamp 100}})]
      (is (= {1 "di-a" 2 "di-z"}
             (gm/dataset-bindings ts-db "g-3")))))
  (testing "9+ datasets (past the cljs array-map/hash-map cutover) still number deterministically by :timestamp"
    (let [entries (into {}
                        (map (fn [i] [(str "di-" (- 9 i)) {:timestamp i}]))
                        (range 9))
          many-db (assoc-in db (ip/indicator-data "g-many") entries)
          bindings (gm/dataset-bindings many-db "g-many")]
      (is (= (set (range 1 10)) (set (keys bindings))))
      (is (= "di-9" (get bindings 1)))
      (is (= "di-1" (get bindings 9))))))

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

(deftest validate-now-stale-guard-test
  (testing "no-ops when the text changed since the debounce was scheduled"
    (let [edited-db (assoc-in db (ip/graph-text "g-1") "{:nodes {} :edges {}}")
          result (gm/validate-now edited-db "g-1" graph-text)]
      (is (= edited-db result))
      (is (nil? (get-in result (ip/graph-validation "g-1"))))))
  (testing "validates when the text still matches what was scheduled"
    (let [result (gm/validate-now db "g-1" graph-text)]
      (is (nil? (get-in result (conj (ip/graph-validation "g-1") :parse-error))))
      (is (empty? (get-in result (conj (ip/graph-validation "g-1") :errors)))))))

(deftest graph-generation-result-stale-guard-test
  (let [agent-db (assoc-in db (conj (ip/graph-agent "g-1") :correlation-id) "corr-real")
        fresh-graph {:nodes {} :edges {}}]
    (testing "ignores a stale correlation id"
      (let [result (gm/handle-graph-generation-result agent-db "g-1" {:graph fresh-graph :id "corr-stale"})]
        (is (= agent-db result))
        (is (nil? (get-in result (ip/graph-proposal "g-1"))))))
    (testing "applies a matching correlation id"
      (let [result (gm/handle-graph-generation-result agent-db "g-1" {:graph fresh-graph :id "corr-real"})]
        (is (= fresh-graph (get-in result (conj (ip/graph-proposal "g-1") :graph))))
        (is (nil? (get-in result (conj (ip/graph-agent "g-1") :correlation-id))))))))

(deftest change-active-graph-seed-test
  (let [persisted-db (assoc-in db (conj (ip/graph-desc "g-2") :graph-text) "persisted-text")]
    (testing "seeds editor text from the persisted artifact when nothing is open yet"
      (let [result (gm/change-active-graph persisted-db "g-2")]
        (is (= "persisted-text" (get-in result (ip/graph-text "g-2"))))
        (is (= {:id "g-2" :kind :graph} (get-in result ip/active-indicator)))))
    (testing "does not clobber in-progress edits on re-activation"
      (let [editing-db (assoc-in persisted-db (ip/graph-text "g-2") "user-edit")
            result (gm/change-active-graph editing-db "g-2")]
        (is (= "user-edit" (get-in result (ip/graph-text "g-2"))))))
    (testing "nil clears the active graph"
      (is (nil? (get-in (gm/change-active-graph persisted-db nil) ip/active-indicator))))))
