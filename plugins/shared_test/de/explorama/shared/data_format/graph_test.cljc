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
                                 {:nodes {} :edges {[:a :b] {} [:b :a] {:direction :<-}}})))))))
