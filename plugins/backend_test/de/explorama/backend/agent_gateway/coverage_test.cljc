(ns de.explorama.backend.agent-gateway.coverage-test
  (:require #?(:clj [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [de.explorama.backend.agent-gateway.dispatcher :as dispatcher]
            [de.explorama.backend.configuration.agent-ops :as configuration]
            [de.explorama.backend.expdb.agent-ops :as expdb]
            [de.explorama.backend.indicator.agent-ops :as indicator]
            [de.explorama.backend.projects.agent-ops :as projects]
            [de.explorama.backend.reporting.agent-ops :as reporting]
            [de.explorama.shared.agent-gateway.catalog :as catalog]))

(use-fixtures :each (fn [f] (dispatcher/reset-registry!) (f) (dispatcher/reset-registry!)))

(deftest every-backend-op-has-a-handler-test
  (doseq [register! [configuration/register! expdb/register! indicator/register! projects/register! reporting/register!]]
    (register!))
  (is (= (catalog/ops-with-side :backend) (dispatcher/registered-ops))))
