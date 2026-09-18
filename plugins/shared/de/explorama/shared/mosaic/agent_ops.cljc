(ns de.explorama.shared.mosaic.agent-ops
  (:require [de.explorama.shared.woco.agent-ops :as woco]))

(def ^:private frame-only [:map {:closed true} [:frame-id woco/frame-id]])

(def actions
  [:enum :group-by :ungroup :sub-group-by :unsub-group :sort-by :sort-group-by :sort-sub-group-by
   :couple :sync-coupled :decouple :scatter :scatter-axis :treemap :treemap-algorithm :raster :remove])

(def state
  [:map [:vertical string?] [:tool string?] [:local-filter :any] [:di :any] [:title :any]
   [:operation-desc :any] [:selected-layout :any]])

(def ops
  [{:op :mosaic/open :side :frontend
    :doc "Open a mosaic frame fed by :source-frame-id at :position. Returns the frame."
    :input [:map {:closed true} [:source-frame-id {:optional true} woco/frame-id] [:position {:optional true} [:tuple number? number?]]]
    :output woco/frame :timeout-ms 10000}
   {:op :mosaic/state :side :frontend
    :doc "The frame's operation description (grouping, sorting, scatter, treemap, raster), selected layouts, filter and data instance."
    :input frame-only :output state}
   {:op :mosaic/operation :side :frontend
    :doc "Run one toolbar operation and answer with the state once the mosaic finished it. :params per action: :group-by/:sub-group-by {:by attribute}, :sort-by {:by attribute}, :sort-group-by {:by :method :attr}, :scatter-axis {:path :axis}, :treemap-algorithm {:algo \"squared\"|\"binary\"|\"slice\"}, :remove {:group-type :grp-attr-val}; the others take {}."
    :input [:map {:closed true} [:frame-id woco/frame-id] [:action actions] [:params {:optional true} map?]]
    :output state :timeout-ms 60000}
   {:op :mosaic/set-layouts :side :frontend
    :doc "Replace the selected layouts with :layouts (layout ids). Layout ids come from :configuration/entries with #{:layouts}. Returns the state."
    :input [:map {:closed true} [:frame-id woco/frame-id] [:layouts [:vector :any]]] :output state :timeout-ms 60000}
   {:op :mosaic/remove-layout :side :frontend
    :doc "Deselect one layout. Layout ids come from :configuration/entries with #{:layouts}. Returns the state."
    :input [:map {:closed true} [:frame-id woco/frame-id] [:layout-id :any]] :output state :timeout-ms 60000}
   {:op :mosaic/filter :side :frontend
    :doc "Apply a local filter description to the frame. Returns the state."
    :input [:map {:closed true} [:frame-id woco/frame-id] [:filter-desc :any]] :output state :timeout-ms 60000}])
