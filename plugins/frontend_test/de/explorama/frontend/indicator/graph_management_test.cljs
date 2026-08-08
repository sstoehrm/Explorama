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

(deftest update-graph-prop-draft-test
  (testing "a prop edit stages under graph-editor-state, leaving the persisted graph-desc untouched"
    (let [edited-db (gm/update-graph-prop db "g-1" :name "draft name")]
      (is (= "draft name" (:name (gm/graph-meta edited-db "g-1"))))
      (is (= "my-result" (:name (get-in edited-db (ip/graph-desc "g-1")))))))
  (testing "graph-artifact->final picks up the draft prop over the persisted one"
    (let [edited-db (-> db
                        (gm/validate-graph-state "g-1")
                        (gm/update-graph-prop "g-1" :name "draft name"))
          {:keys [artifact errors]} (gm/graph-artifact->final edited-db "g-1")]
      (is (nil? errors))
      (is (= "draft name" (:name artifact)))))
  (testing "a prop draft alone makes the graph dirty, even with unchanged text"
    (let [saved-db (assoc-in db (conj (ip/graph-desc "g-1") :graph-text) graph-text)
          edited-db (gm/update-graph-prop saved-db "g-1" :name "draft name")]
      (is (false? (gm/text-dirty? edited-db "g-1")))
      (is (true? (gm/dirty? edited-db "g-1"))))))

(deftest discard-changes-test
  (let [saved-db (assoc-in db (conj (ip/graph-desc "g-1") :graph-text) graph-text)
        edited-db (-> saved-db
                      (gm/update-graph-prop "g-1" :name "draft name")
                      (assoc-in (ip/graph-text "g-1") "{:nodes {} :edges {}}"))
        discarded (gm/discard-changes edited-db "g-1")]
    (testing "reverts the draft name back to the persisted one"
      (is (= "my-result" (:name (gm/graph-meta discarded "g-1")))))
    (testing "reverts the buffered text back to the persisted one"
      (is (= graph-text (get-in discarded (ip/graph-text "g-1")))))
    (testing "no longer dirty after discarding both"
      (is (false? (gm/dirty? discarded "g-1"))))))

(deftest graph-exist-test
  (testing "a persisted graph (present in ip/graphs) exists regardless of what's active"
    (let [persisted-db (assoc-in {} ip/graphs {"g-1" {:id "g-1"}})]
      (is (true? (gm/graph-exist? persisted-db "g-1")))))
  (testing "an unsaved graph staged as the active {:id .. :kind :graph} artifact exists"
    (let [staged-db (assoc-in {} ip/active-indicator {:id "g-new" :kind :graph})]
      (is (true? (gm/graph-exist? staged-db "g-new")))))
  (testing "neither persisted nor active-as-a-graph is not an existing graph"
    (is (false? (gm/graph-exist? {} "g-unknown"))))
  (testing "an active indicator (not a graph) with the same id doesn't count"
    (let [indicator-active-db (assoc-in {} ip/active-indicator {:id "i-1" :kind :indicator})]
      (is (false? (gm/graph-exist? indicator-active-db "i-1"))))))

(deftest store-graph-artifact-test
  (testing "always stamps write-access? so a freshly saved graph's card can show delete without an all-graphs refetch"
    (let [stored (gm/store-graph-artifact {} {:id "g-1" :name "n"})]
      (is (true? (get-in stored (conj (ip/graph-desc "g-1") :write-access?))))))
  (testing "overrides an explicit false coming from the compiled artifact"
    (let [stored (gm/store-graph-artifact {} {:id "g-1" :name "n" :write-access? false})]
      (is (true? (get-in stored (conj (ip/graph-desc "g-1") :write-access?))))))
  (testing "clears any pending prop draft now that it's reflected in the saved artifact"
    (let [drafted-db (gm/update-graph-prop db "g-1" :name "draft name")
          stored (gm/store-graph-artifact drafted-db {:id "g-1" :name "draft name"})]
      (is (false? (gm/prop-dirty? stored "g-1")))
      (is (= "draft name" (:name (gm/graph-meta stored "g-1")))))))

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
