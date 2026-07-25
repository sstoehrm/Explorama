(ns de.explorama.frontend.search.views.components.location)

;; Stage-1 Pixi map cutover (task 9): the OpenLayers-based region-drawing
;; widget (draw-a-bounding-box-on-a-mini-map input for the search plugin's
;; `:location` attribute type) has been stubbed out rather than ported. It was
;; an independent OL/ol-ext consumer, unrelated to the map plugin's
;; OpenLayers->Pixi backend swap, and out of scope for that migration.
;;
;; `location-input` keeps its external contract (same fn name, single props
;; map argument, still renders `:extra-style`/`:child`) so
;; `search_selection_component.cljs`'s `(def location-input loc/location-input)`
;; and its `:location [location-input (assoc props :class "input--w100")]`
;; call site need no changes. No region can be drawn/selected any more; the
;; `on-change`/commit-apply-cancel flow this widget used to drive is gone
;; entirely, so it simply never fires. Tracked as deferred work pending a
;; Pixi-based port (see stage-1 task-9 report/issue).
(def ^:private placeholder-classes
  (str "w-full h-full flex items-center justify-center text-center text-sm "
       "text-gray-600 bg-white border border-gray-300 rounded-xs p-2.5"))

(defn location-input [{:keys [extra-style child]}]
  [:<>
   [:div {:class placeholder-classes
          :style extra-style}
    "Region selection is not yet available in the new map renderer."]
   child])
