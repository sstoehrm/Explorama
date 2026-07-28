(ns de.explorama.backend.agent-requests.queue-test
  (:require [clojure.test :refer [deftest is testing]]
            [de.explorama.backend.agent-requests.queue :as sut]))

(def ^:private base
  {:id "r1"
   :type :test/type
   :input {:a 1}
   :user "tester"
   :context {:reply :some-fn}
   :lease-ms 1000
   :ttl-ms 10000
   :max-rejections 3})

(defn- with-request [now]
  (first (sut/create {} now base)))

(deftest create-test
  (testing "a created request is open and carries its deadlines"
    (let [[state request] (sut/create {} 100 base)]
      (is (= :open (:status request)))
      (is (= 100 (:created-at request)))
      (is (= 10100 (:expires-at request)))
      (is (= 0 (:rejections request)))
      (is (= request (get state "r1"))))))

(deftest open-requests-test
  (let [state (with-request 100)]
    (testing "open requests are listed"
      (is (= ["r1"] (mapv :id (sut/open-requests state 200 nil)))))
    (testing "the type filter excludes other types"
      (is (empty? (sut/open-requests state 200 :other/type))))
    (testing "claimed requests are hidden"
      (let [[state] (sut/claim state 200 "r1" "agent-1")]
        (is (empty? (sut/open-requests state 300 nil)))))))

(deftest claim-test
  (let [state (with-request 100)]
    (testing "claiming an open request succeeds and sets the lease"
      (let [[state {request :ok}] (sut/claim state 200 "r1" "agent-1")]
        (is (= :claimed (:status request)))
        (is (= "agent-1" (:claimed-by request)))
        (is (= 1200 (:lease-expires-at request)))
        (is (= :claimed (:status (get state "r1"))))))
    (testing "a second agent conflicts while the lease holds"
      (let [[state] (sut/claim state 200 "r1" "agent-1")
            [_ result] (sut/claim state 300 "r1" "agent-2")]
        (is (= {:error :conflict} result))))
    (testing "an expired lease returns the request to open"
      (let [[state] (sut/claim state 200 "r1" "agent-1")
            [_ {request :ok}] (sut/claim state 1300 "r1" "agent-2")]
        (is (= "agent-2" (:claimed-by request)))))
    (testing "an unknown id is not found"
      (is (= {:error :not-found} (second (sut/claim state 200 "nope" "agent-1")))))
    (testing "a cancelled request is gone"
      (let [[state] (sut/cancel state 150 "r1")]
        (is (= {:error :gone} (second (sut/claim state 200 "r1" "agent-1"))))))))

(deftest submit-test
  (let [state (with-request 100)
        ok (constantly nil)
        nope (constantly {:mapping ["missing"]})]
    (testing "submitting a valid result fulfils the request"
      (let [[state] (sut/claim state 200 "r1" "agent-1")
            [state {request :ok}] (sut/submit state 300 "r1" {:m 1} ok)]
        (is (= :fulfilled (:status request)))
        (is (= {:m 1} (:result request)))
        (is (= :fulfilled (:status (get state "r1"))))))
    (testing "an invalid result is rejected and counted, request stays claimed"
      (let [[state] (sut/claim state 200 "r1" "agent-1")
            [state result] (sut/submit state 300 "r1" {:m 1} nope)]
        (is (= :invalid (:error result)))
        (is (= {:mapping ["missing"]} (:explanation result)))
        (is (= :claimed (:status (get state "r1"))))
        (is (= 1 (:rejections (get state "r1"))))))
    (testing "exceeding max-rejections fails the request"
      (let [[state] (sut/claim state 200 "r1" "agent-1")
            state (reduce (fn [state n]
                            (first (sut/submit state (+ 300 n) "r1" {:m 1} nope)))
                          state
                          [0 1 2])]
        (is (= :failed (:status (get state "r1"))))
        (is (= {:mapping ["missing"]} (:reason (get state "r1"))))))
    (testing "submitting without a claim is refused"
      (is (= {:error :not-claimed}
             (second (sut/submit state 300 "r1" {:m 1} ok)))))))

(deftest sweep-test
  (let [state (with-request 100)]
    (testing "a request past its ttl expires"
      (let [state (sut/sweep state 20000)]
        (is (= :expired (:status (get state "r1"))))))
    (testing "expiry wins over an active claim"
      (let [[state] (sut/claim state 9000 "r1" "agent-1")
            state (sut/sweep state 20000)]
        (is (= :expired (:status (get state "r1"))))))
    (testing "sweeping is idempotent"
      (is (= (sut/sweep state 20000)
             (sut/sweep (sut/sweep state 20000) 21000))))))

(deftest user-requests-test
  (let [[state] (sut/create {} 100 base)
        [state] (sut/create state 200 (assoc base :id "r2"))
        [state] (sut/create state 300 (assoc base :id "r3" :user "other"))]
    (testing "only the user's own requests are returned, newest first"
      (is (= ["r2" "r1"] (mapv :id (sut/user-requests state 400 "tester")))))))
