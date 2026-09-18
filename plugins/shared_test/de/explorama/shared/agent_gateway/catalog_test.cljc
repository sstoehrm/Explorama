(ns de.explorama.shared.agent-gateway.catalog-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [malli.core :as m]
            [de.explorama.shared.agent-gateway.catalog :as sut]
            [de.explorama.shared.agent-gateway.errors :as errors]))

(deftest declarations-are-well-formed-test
  (testing "every declaration matches the declaration schema"
    (doseq [d sut/plugin-declarations]
      (is (nil? (m/explain sut/declaration-schema d)) (pr-str (:op d)))))
  (testing "op names are unique"
    (is (= (count sut/plugin-declarations) (count sut/ops))))
  (testing "input and output schemas compile"
    (doseq [{:keys [op input output]} sut/plugin-declarations]
      (is (m/schema input) (str op " input"))
      (is (m/schema output) (str op " output")))))

(deftest explain-input-test
  (with-redefs [sut/ops {:test/echo {:op :test/echo :doc "echo" :side :backend
                                     :input [:map {:closed true} [:text string?]]
                                     :output [:map [:text string?]]}}]
    (testing "valid params explain to nil"
      (is (nil? (sut/explain-input :test/echo {:text "hi"}))))
    (testing "invalid params explain to a humanized map"
      (is (= {:text ["should be a string"]} (sut/explain-input :test/echo {:text 1}))))
    (testing "an unknown op explains to nil so the caller reports unknown-op instead"
      (is (nil? (sut/explain-input :test/nope {}))))
    (testing "the public catalog serves schemas as forms"
      (is (= [{:op :test/echo :doc "echo" :side :backend
               :input [:map {:closed true} [:text 'string?]]
               :output [:map [:text 'string?]]}]
             (sut/public-catalog))))
    (testing "timeout falls back to the default"
      (is (= sut/default-timeout-ms (sut/timeout-ms :test/echo))))))

(deftest errors-test
  (is (= {:status :ok :result 1} (errors/ok 1)))
  (is (= {:status :error :error {:type :timeout :message "late"}} (errors/error :timeout "late")))
  (is (= {:status :error :error {:type :invalid-params :message "bad" :explain {:a ["x"]}}}
         (errors/error :invalid-params "bad" {:explain {:a ["x"]}})))
  (is (errors/error? (errors/error :timeout "late")))
  (is (not (errors/error? (errors/ok 1))))
  (is (= 504 (errors/http-status :timeout))))
