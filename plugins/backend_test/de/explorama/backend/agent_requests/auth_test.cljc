(ns de.explorama.backend.agent-requests.auth-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [de.explorama.backend.agent-requests.auth :as sut]))

(use-fixtures :each (fn [f] (sut/reset-authenticator!) (f) (sut/reset-authenticator!)))

(deftest no-authenticator-test
  (testing "without an installed authenticator every request is unauthorized"
    (is (= {:error :unauthorized} (sut/authenticate {:headers {"x" "y"}})))))

(deftest delegation-test
  (testing "the installed authenticator decides"
    (sut/set-authenticator! (fn [request]
                              (if (get-in request [:headers "who"])
                                {:principal (get-in request [:headers "who"])}
                                {:error :unauthorized})))
    (is (= {:principal "agent-service"}
           (sut/authenticate {:headers {"who" "agent-service"}})))
    (is (= {:error :unauthorized} (sut/authenticate {:headers {}}))))
  (testing "resetting removes it again"
    (sut/set-authenticator! (constantly {:principal "whoever"}))
    (sut/reset-authenticator!)
    (is (= {:error :unauthorized} (sut/authenticate {:headers {}})))))
