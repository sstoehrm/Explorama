(ns de.explorama.shared.agent-requests.age-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [de.explorama.shared.agent-requests.age :as sut]))

(deftest age-label-test
  (testing "the unit follows the elapsed time"
    (is (= "0s" (sut/age-label 1000 1000)))
    (is (= "12s" (sut/age-label 1000 13000)))
    (is (= "5m" (sut/age-label 0 (* 5 60 1000))))
    (is (= "2h" (sut/age-label 0 (* 2 60 60 1000))))
    (is (= "3d" (sut/age-label 0 (* 3 24 60 60 1000)))))
  (testing "a clock skewed behind the request does not produce a negative age"
    (is (= "0s" (sut/age-label 5000 1000))))
  (testing "a request without a creation time has no age to show"
    (is (nil? (sut/age-label nil 1000)))
    (is (nil? (sut/age-label 1000 nil)))))
