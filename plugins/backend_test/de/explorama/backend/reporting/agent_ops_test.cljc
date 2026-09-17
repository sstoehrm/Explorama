(ns de.explorama.backend.reporting.agent-ops-test
  (:require #?(:clj [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [de.explorama.backend.agent-gateway.dispatcher :as dispatcher]
            [de.explorama.backend.reporting.agent-ops :as sut]
            [de.explorama.backend.reporting.persistence.api :as api]))

(use-fixtures :each (fn [f] (dispatcher/reset-registry!) (sut/register!) (f) (dispatcher/reset-registry!)))

(deftest save-dashboard-success-test
  (with-redefs [api/save-dashboard (fn [{:keys [client-callback]} _] (client-callback :success))
                api/all-dashboards (fn [{:keys [client-callback]} _] (client-callback {:created {}}))]
    (is (= {:status :ok :result {:created {}}}
           (dispatcher/invoke {:op :reporting/save-dashboard :params {:desc {:id "d1" :name "n"}} :user "alice"})))))

(deftest save-dashboard-no-rights-test
  (with-redefs [api/save-dashboard (fn [{:keys [client-callback]} _] (client-callback :error :no-rights))]
    (is (= :unauthorized
           (get-in (dispatcher/invoke {:op :reporting/save-dashboard :params {:desc {:id "d1" :name "n"}} :user "alice"})
                   [:error :type])))))
