(ns de.explorama.backend.agent-requests.proxy-auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [de.explorama.backend.agent-requests.auth :as auth]
            [de.explorama.backend.agent-requests.proxy-auth :as sut]))

(defn- request-with [header-value]
  {:headers (cond-> {"host" "localhost"}
              header-value (assoc "x-auth-request-user" header-value))})

(deftest missing-header-test
  (testing "no header is unauthorized"
    (is (= {:error :unauthorized} (sut/authenticate (request-with nil)))))
  (testing "a blank header is unauthorized"
    (is (= {:error :unauthorized} (sut/authenticate (request-with ""))))
    (is (= {:error :unauthorized} (sut/authenticate (request-with "   "))))))

(deftest principal-test
  (with-redefs [sut/allowed-principals #{"agent-service"}]
    (testing "the header value becomes the principal"
      (is (= {:principal "agent-service"} (sut/authenticate (request-with "agent-service")))))
    (testing "surrounding whitespace is trimmed"
      (is (= {:principal "agent-service"} (sut/authenticate (request-with "  agent-service  ")))))))

(deftest empty-allow-list-denies-test
  (testing "an empty allow-list denies every principal, so the api is inert until one is named"
    (is (= #{} sut/allowed-principals)
        "the shipped default must stay closed")
    (is (= {:error :forbidden} (sut/authenticate (request-with "whoever"))))
    (is (= {:error :forbidden} (sut/authenticate (request-with "agent-service"))))))

(deftest allow-list-test
  (with-redefs [sut/allowed-principals #{"agent-service"}]
    (testing "a listed principal passes"
      (is (= {:principal "agent-service"} (sut/authenticate (request-with "agent-service")))))
    (testing "an unlisted principal is forbidden, not unauthorized"
      (is (= {:error :forbidden} (sut/authenticate (request-with "intruder")))))
    (testing "a missing header is still unauthorized"
      (is (= {:error :unauthorized} (sut/authenticate (request-with nil)))))))

(deftest init-test
  (testing "init installs the authenticator into the gate"
    (auth/reset-authenticator!)
    (sut/init)
    (with-redefs [sut/allowed-principals #{"agent-service"}]
      (is (= {:principal "agent-service"}
             (auth/authenticate (request-with "agent-service")))))
    (auth/reset-authenticator!)))
