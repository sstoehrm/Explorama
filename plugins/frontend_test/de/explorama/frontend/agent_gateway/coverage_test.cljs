(ns de.explorama.frontend.agent-gateway.coverage-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [de.explorama.frontend.agent-gateway.registry :as registry]
            [de.explorama.frontend.algorithms.agent-ops :as algorithms]
            [de.explorama.frontend.charts.agent-ops :as charts]
            [de.explorama.frontend.configuration.agent-ops :as configuration]
            [de.explorama.frontend.data-atlas.agent-ops :as data-atlas]
            [de.explorama.frontend.indicator.agent-ops :as indicator]
            [de.explorama.frontend.map.agent-ops :as map-ops]
            [de.explorama.frontend.mosaic.agent-ops :as mosaic]
            [de.explorama.frontend.projects.agent-ops :as projects]
            [de.explorama.frontend.search.agent-ops :as search]
            [de.explorama.frontend.table.agent-ops :as table]
            [de.explorama.frontend.woco.agent-ops :as woco]
            [de.explorama.shared.agent-gateway.catalog :as catalog]))

(use-fixtures :each (fn [f] (registry/reset-ops!) (f) (registry/reset-ops!)))

(deftest every-frontend-op-has-a-handler-test
  (doseq [register! [algorithms/register! charts/register! configuration/register! data-atlas/register!
                     indicator/register! map-ops/register! mosaic/register! projects/register!
                     search/register! table/register! woco/register!]]
    (register!))
  (is (= (catalog/ops-with-side :frontend) (registry/registered-ops))
      "declared frontend ops and registered handlers must be the same set"))
