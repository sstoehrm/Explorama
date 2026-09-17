(ns de.explorama.shared.agent-gateway.catalog
  (:require [de.explorama.shared.algorithms.agent-ops :as algorithms-ops]
            [de.explorama.shared.charts.agent-ops :as charts-ops]
            [de.explorama.shared.data-atlas.agent-ops :as data-atlas-ops]
            [de.explorama.shared.indicator.agent-ops :as indicator-ops]
            [de.explorama.shared.map.agent-ops :as map-ops]
            [de.explorama.shared.mosaic.agent-ops :as mosaic-ops]
            [de.explorama.shared.search.agent-ops :as search-ops]
            [de.explorama.shared.table.agent-ops :as table-ops]
            [de.explorama.shared.woco.agent-ops :as woco-ops]
            [malli.core :as m]
            [malli.error :as me]))

(def default-timeout-ms 30000)

(def declaration-schema
  [:map {:closed true}
   [:op qualified-keyword?]
   [:doc [:string {:min 1}]]
   [:side [:enum :frontend :backend]]
   [:input :any]
   [:output :any]
   [:timeout-ms {:optional true} pos-int?]])

(def plugin-declarations
  (vec (concat woco-ops/ops search-ops/ops mosaic-ops/ops map-ops/ops table-ops/ops charts-ops/ops
               algorithms-ops/ops data-atlas-ops/ops indicator-ops/ops)))

(def ops
  (into {} (map (juxt :op identity)) plugin-declarations))

(defn declaration [op]
  (get ops op))

(defn explain-input [op params]
  (when-let [{:keys [input]} (declaration op)]
    (some-> (m/explain input params) me/humanize)))

(defn timeout-ms [op]
  (or (:timeout-ms (declaration op)) default-timeout-ms))

(defn ops-with-side [side]
  (into #{} (comp (filter #(= side (:side %))) (map :op)) (vals ops)))

(defn- public-declaration [{:keys [input output] :as d}]
  (-> (select-keys d [:op :doc :side :timeout-ms])
      (assoc :input (m/form input) :output (m/form output))))

(defn public-catalog []
  (->> (vals ops)
       (sort-by (comp str :op))
       (mapv public-declaration)))
