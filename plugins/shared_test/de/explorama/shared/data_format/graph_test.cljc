(ns de.explorama.shared.data-format.graph-test
  (:require #?(:clj  [clojure.test :as t :refer [deftest testing is]]
               :cljs [cljs.test :as t :refer [deftest testing is] :include-macros true])
            [de.explorama.shared.data-format.graph :as graph]))

(def valid-graph
  {:nodes {:src   {:type :datasource :dataset 1}
           :group {:type :operation :op :group-by :params {:attributes ["year"]}}
           :sums  {:type :operation :op :sum :params {:attribute "fact-1"}}
           :out   {:type :result :name "my-result"}}
   :edges {[:src :group]  {:direction :->}
           [:group :sums] {}
           [:sums :out]   {:as "indicator"}}})

(deftest parse-test
  (testing "well-formed edn"
    (is (= {:ok {:nodes {}}} (graph/parse "{:nodes {}}"))))
  (testing "malformed edn returns error"
    (let [{:keys [error]} (graph/parse "{:nodes {")]
      (is (string? (:message error)))))
  (testing "no code execution"
    (is (contains? (graph/parse "#=(inc 1)") :error))))

(deftest schema-test
  (testing "valid graph passes"
    (is (nil? (graph/explain-schema valid-graph))))
  (testing "unknown node type rejected"
    (is (some? (graph/explain-schema
                {:nodes {:a {:type :box}} :edges {}}))))
  (testing "extra node keys rejected"
    (is (some? (graph/explain-schema
                {:nodes {:a {:type :datasource :dataset 1 :foo 1}} :edges {}}))))
  (testing "result requires name"
    (is (some? (graph/explain-schema
                {:nodes {:a {:type :result}} :edges {}}))))
  (testing "string ids allowed"
    (is (nil? (graph/explain-schema
               {:nodes {"a" {:type :datasource :dataset 1}
                        "out" {:type :result :name "x"}}
                :edges {["a" "out"] {}}})))))

(deftest allowed-operations-test
  (let [ops (graph/allowed-operations)]
    (testing "category ops exposed"
      (is (every? ops [:group-by :sort-by :sum :min :max :median :average
                       :count-events :+ :- :* :/
                       :union :intersection :difference :sym-difference])))
    (testing "curated internals exposed"
      (is (every? ops [:distinct :normalize :filter])))
    (testing "heal-event and other internals excluded"
      (is (not-any? ops [:heal-event :select :take-first :take-last
                         :sort-by-frequencies :apply-layout :intersection-by])))))

(deftest operation-metadata-test
  (let [md (graph/operation-metadata)]
    (testing "vpl steering carried over"
      (is (= 1 (get-in md [:sum :arguments])))
      (is (= 0 (get-in md [:/ :arguments])))
      (is (= :meta-group-values
             (get-in md [:sum :input->output :default :meta-group-list-events]))))
    (testing "supplemental steering for curated internals"
      (is (= 1 (get-in md [:distinct :arguments])))
      (is (= 1 (get-in md [:normalize :arguments])))
      (is (= 1 (get-in md [:filter :arguments])))
      (is (map? (get-in md [:distinct :input->output :default])))
      (is (= :meta-list-events
             (get-in md [:filter :input->output :default :meta-list-events]))))
    (testing "distinct mirrors the :aggregation category's shape table"
      (is (= :meta-group-values
             (get-in md [:distinct :input->output :default :meta-group-list-events])))
      (is (= :meta-primitive-value
             (get-in md [:distinct :input->output :default :meta-list-events])))
      (is (= :meta-primitive-value
             (get-in md [:distinct :input->output :dependent 1 :meta-group-list-events])))
      (is (= :meta-primitive-value
             (get-in md [:distinct :input->output :dependent 3 :meta-group-list-events])))
      (is (= (get-in md [:sum :input->output])
             (get-in md [:distinct :input->output]))))
    (testing "normalize's default omits the untyped list-events case"
      (is (= {:meta-group-list-events :meta-group-list-events}
             (get-in md [:normalize :input->output :default]))))))

(deftest normalized-edges-test
  (testing "default and explicit :-> keep order"
    (is (= {[:src :group] {} [:group :sums] {}}
           (:edges (graph/normalized-edges
                    {:nodes {} :edges {[:src :group] {:direction :->}
                                       [:group :sums] {}}})))))
  (testing ":<- flips"
    (is (= {[:b :a] {:order 1}}
           (:edges (graph/normalized-edges
                    {:nodes {} :edges {[:a :b] {:direction :<- :order 1}}})))))
  (testing ":<-> and :- rejected"
    (is (= [:bad-direction]
           (mapv :code (:errors (graph/normalized-edges
                                 {:nodes {} :edges {[:a :b] {:direction :<->}}}))))))
  (testing "duplicate connection either order rejected"
    (is (= [:duplicate-connection]
           (mapv :code (:errors (graph/normalized-edges
                                 {:nodes {} :edges {[:a :b] {} [:b :a] {:direction :<-}}}))))))
  (testing "opposite-direction pair between the same nodes is still a duplicate connection"
    (is (= [:duplicate-connection]
           (mapv :code (:errors (graph/normalized-edges
                                 {:nodes {} :edges {[:a :b] {} [:b :a] {}}})))))))

(defn- codes [graph n] (set (map :code (:errors (graph/validate graph n)))))
(defn- warning-codes [graph n] (set (map :code (:warnings (graph/validate graph n)))))

(deftest structural-validation-test
  (testing "valid graph has no errors"
    (is (empty? (:errors (graph/validate valid-graph 1)))))
  (testing "cycle"
    (is (contains? (codes {:nodes {:a {:type :operation :op :union}
                                   :b {:type :operation :op :union}
                                   :c {:type :operation :op :union}
                                   :s {:type :datasource :dataset 1}
                                   :o {:type :result :name "x"}}
                           :edges {[:s :a] {} [:a :b] {} [:b :c] {} [:c :a] {} [:a :o] {}}} 1)
                   :cycle)))
  (testing "empty graph has no result node"
    (is (contains? (codes {:nodes {} :edges {}} 0) :no-result)))
  (testing "two sinks"
    (is (contains? (codes {:nodes {:s {:type :datasource :dataset 1}
                                   :x {:type :operation :op :sum :params {:attribute "f"}}
                                   :o {:type :result :name "x"}}
                           :edges {[:s :x] {} [:s :o] {}}} 1)
                   :multiple-sinks)))
  (testing "no result node"
    (is (contains? (codes {:nodes {:s {:type :datasource :dataset 1}
                                   :x {:type :operation :op :sum :params {:attribute "f"}}}
                           :edges {[:s :x] {}}} 1)
                   :no-result)))
  (testing "datasource with incoming edge"
    (is (contains? (codes {:nodes {:s {:type :datasource :dataset 1}
                                   :s2 {:type :datasource :dataset 1}
                                   :o {:type :result :name "x"}}
                           :edges {[:s :s2] {} [:s2 :o] {}}} 1)
                   :datasource-has-input)))
  (testing "unbound dataset ref"
    (is (contains? (codes valid-graph 0) :unbound-dataset)))
  (testing "unknown op and heal-event both rejected"
    (is (contains? (codes (assoc-in valid-graph [:nodes :sums :op] :frobnicate) 1)
                   :unknown-op))
    (is (contains? (codes (assoc-in valid-graph [:nodes :sums :op] :heal-event) 1)
                   :unknown-op)))
  (testing "unknown param key"
    (is (contains? (codes (assoc-in valid-graph [:nodes :sums :params :bogus] 1) 1)
                   :unknown-param)))
  (testing "arity: sum with two inputs"
    (is (contains? (codes {:nodes {:s1 {:type :datasource :dataset 1}
                                   :s2 {:type :datasource :dataset 1}
                                   :x {:type :operation :op :sum :params {:attribute "f"}}
                                   :o {:type :result :name "x"}}
                           :edges {[:s1 :x] {} [:s2 :x] {} [:x :o] {}}} 1)
                   :arity-mismatch)))
  (testing "order-sensitive multi-input op without :order"
    (is (contains? (codes {:nodes {:s1 {:type :datasource :dataset 1}
                                   :s2 {:type :datasource :dataset 1}
                                   :x {:type :operation :op :/}
                                   :o {:type :result :name "x"}}
                           :edges {[:s1 :x] {} [:s2 :x] {} [:x :o] {}}} 1)
                   :order-missing)))
  (testing ":order not a 1..n permutation"
    (is (contains? (codes {:nodes {:s1 {:type :datasource :dataset 1}
                                   :s2 {:type :datasource :dataset 1}
                                   :x {:type :operation :op :/}
                                   :o {:type :result :name "x"}}
                           :edges {[:s1 :x] {:order 1} [:s2 :x] {:order 3} [:x :o] {}}} 1)
                   :order-invalid)))
  (testing "multi-branch result without :as"
    (is (contains? (codes {:nodes {:s {:type :datasource :dataset 1}
                                   :g {:type :operation :op :group-by :params {:attributes ["year"]}}
                                   :a {:type :operation :op :sum :params {:attribute "f"}}
                                   :b {:type :operation :op :min :params {:attribute "f"}}
                                   :o {:type :result :name "x"}}
                           :edges {[:s :g] {} [:g :a] {} [:g :b] {}
                                   [:a :o] {:order 1} [:b :o] {:order 2}}} 1)
                   :as-missing)))
  (testing "unused dataset warning"
    (is (contains? (warning-codes valid-graph 2) :unused-dataset)))
  (testing "schema violations surface as :schema errors"
    (is (contains? (codes {:nodes {:a {:type :box}} :edges {}} 1) :schema))))

(deftest shape-inference-test
  (testing "happy path shapes"
    (let [{:keys [shapes errors]} (graph/validate valid-graph 1)]
      (is (empty? errors))
      (is (= :meta-list-events (get shapes :src)))
      (is (= :meta-group-list-events (get shapes :group)))
      (is (= :meta-group-values (get shapes :sums)))))
  (testing "sum on ungrouped events is primitive - not heal-able"
    (let [g {:nodes {:s {:type :datasource :dataset 1}
                     :x {:type :operation :op :sum :params {:attribute "f"}}
                     :o {:type :result :name "r"}}
             :edges {[:s :x] {} [:x :o] {}}}
          {:keys [errors]} (graph/validate g 1)]
      (is (some #(= :result-shape (:code %)) errors))))
  (testing "op applied to impossible shape"
    (let [g {:nodes {:s {:type :datasource :dataset 1}
                     :a {:type :operation :op :sum :params {:attribute "f"}}
                     :b {:type :operation :op :group-by :params {:attributes ["year"]}}
                     :o {:type :result :name "r"}}
             :edges {[:s :a] {} [:a :b] {} [:b :o] {}}}
          {:keys [errors]} (graph/validate g 1)]
      (is (some #(and (= :shape-mismatch (:code %)) (= :b (:node %))) errors))))
  (testing "multi-input op with mixed shapes"
    (let [g {:nodes {:s1 {:type :datasource :dataset 1}
                     :g1 {:type :operation :op :group-by :params {:attributes ["year"]}}
                     :a  {:type :operation :op :sum :params {:attribute "f"}}
                     :u  {:type :operation :op :union}
                     :o  {:type :result :name "r"}}
             :edges {[:s1 :g1] {} [:g1 :a] {} [:s1 :u] {} [:a :u] {} [:u :o] {}}}
          {:keys [errors]} (graph/validate g 1)]
      (is (some #(= :shape-heterogeneous (:code %)) errors))))
  (testing "dependent transition: sum with :join? true on sub-groups"
    (let [g {:nodes {:s {:type :datasource :dataset 1}
                     :g1 {:type :operation :op :group-by :params {:attributes ["country"]}}
                     :g2 {:type :operation :op :group-by :params {:attributes ["year"]}}
                     :a {:type :operation :op :sum :params {:attribute "f" :join? true}}
                     :o {:type :result :name "r"}}
             :edges {[:s :g1] {} [:g1 :g2] {} [:g2 :a] {} [:a :o] {}}}
          {:keys [shapes errors]} (graph/validate g 1)]
      (is (empty? errors))
      (is (= :meta-sub-group-list-events (get shapes :g2)))
      (is (= :meta-group-values (get shapes :a))))))

(deftest result-policy-test
  (is (= :merge (graph/result-policy [:meta-group-values :meta-group-list-values])))
  (is (= :vals (graph/result-policy [:meta-group-list-events])))
  (is (nil? (graph/result-policy [:meta-primitive-value])))
  (is (nil? (graph/result-policy [:meta-group-values :meta-group-list-events]))))
