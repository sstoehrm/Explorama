(ns de.explorama.backend.agent-requests.registry-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [de.explorama.backend.agent-requests.registry :as sut]))

(use-fixtures :each (fn [f] (sut/reset-types!) (f) (sut/reset-types!)))

(def ^:private declaration
  {:id :test/greeting
   :description "Return a greeting"
   :output-schema [:map [:greeting string?]]
   :output-example {:greeting "hello"}
   :on-fulfilled (fn [_request _result] nil)})

(deftest register-type-test
  (testing "a valid declaration is registered and retrievable"
    (sut/register-type! declaration)
    (is (= "Return a greeting" (:description (sut/type-declaration :test/greeting))))
    (is (= [:test/greeting] (mapv :id (sut/all-types)))))
  (testing "a declaration without a handler is rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid request type declaration"
                          (sut/register-type! (dissoc declaration :on-fulfilled)))))
  (testing "a declaration without an output schema is rejected"
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Invalid request type declaration"
                          (sut/register-type! (dissoc declaration :output-schema))))))

(deftest explain-result-test
  (sut/register-type! declaration)
  (testing "a matching result explains as nil"
    (is (nil? (sut/explain-result :test/greeting {:greeting "moin"}))))
  (testing "a mismatching result explains the problem"
    (is (some? (sut/explain-result :test/greeting {:greeting 42}))))
  (testing "an unknown type explains as unknown"
    (is (= {:type ["unknown request type"]}
           (sut/explain-result :test/nothing {})))))
