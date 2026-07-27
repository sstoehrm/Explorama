(ns de.explorama.frontend.map.map.impl.pixi.instance
  (:require [clojure.string :as str]
            [de.explorama.frontend.map.pixi.engine :as engine]
            [de.explorama.frontend.map.pixi.geo :as geo]
            [de.explorama.frontend.map.pixi.projection :as proj]
            [de.explorama.frontend.map.pixi.style :as style]
            [taoensso.timbre :refer-macros [warn]]))

(defonce ^:private registry (atom {}))

(defn new-instance-state [frame-id extra-fns]
  (atom {:frame-id frame-id
         :extra-fns extra-fns
         :engine nil
         :headless? true
         :marker-data {}
         :created-marker-ids #{}
         :event-cache {}
         :highlighted #{}
         :visible-ids nil
         :cluster? true
         :base-layers {}
         :current-base-layer nil
         :feature-data {}
         :filtered-feature-data {}
         :vector-layers {}
         :active-overlayers #{}
         :active-feature-layers #{}
         :stub-feature-layers #{}
         :pending []
         :pending-render-listeners []}))

(defn register! [frame-id state-atom]
  (swap! registry assoc frame-id state-atom))

(defn lookup [frame-id]
  (get @registry frame-id))

(defn unregister! [frame-id]
  (swap! registry dissoc frame-id))

(defn enqueue-pending [state-map op]
  (update state-map :pending (fnil conj []) op))

(defn drain-pending [state-map]
  [(assoc state-map :pending [])
   (get state-map :pending [])])

(defn valid-move-to?
  "true when both zoom and position are present - move-to is a no-op
   (rather than resetting the viewport to zoom-nil garbage) otherwise.
   Replay/popup flows call move-to with nils for frames that never moved."
  [zoom position]
  (boolean (and zoom position)))

(defn push-markers!
  "Push the current marker-data (with highlight/visibility filters applied) to
   the booted engine. No-op while headless (:engine nil).

   The object-manager and state-handler share this instance state but own
   different keys: state-handler exclusively owns :marker-data (the full
   cache, written by set-marker-data), while the object-manager exclusively
   owns :created-marker-ids (the create/remove/clear bookkeeping set). What's
   actually rendered is the intersection of the two - entries of :marker-data
   whose id has been \"created\" via the object-manager - mirroring the
   render semantics the OpenLayers implementation had."
  [state-map]
  (when-let [eng (:engine state-map)]
    (let [renderable (select-keys (:marker-data state-map) (:created-marker-ids state-map))]
      (engine/set-markers! eng
                           (style/markers-map->engine-markers
                            renderable (:highlighted state-map) (:visible-ids state-map))))))

;;;; Vector layers (overlayers + area/feature-coloring/movement/heatmap) ----
;;
;; All four kinds share ONE registry: this instance state's `:vector-layers
;; id -> {:kind :features/:style/:style-fn | :arrows | :points :visible?}`
;; mirror of the engine's own per-kind registries, needed because
;; overlayers/feature-layers are created and toggled long before the engine
;; boots (see map/core.cljs's headless init path) - the mirror is what
;; ensure-engine!'s boot replay (register-staged-vector-layers!) pushes into
;; the freshly-created engine. :overlayer/:area entries push through
;; engine/add-vector-layer!; :movement (arrows) and :heatmap (points) push
;; through their own engine/add-arrow-layer!/add-heatmap-layer! - see
;; engine-add-layer!/engine-remove-layer!/engine-set-layer-visible! below,
;; the single kind-dispatch point every mutator in this section goes through
;; so feature-layer-created?/get-feature-layer-obj/list-active-feature-layers
;; (object_manager.cljs/state_handler.cljs) stay kind-agnostic.

(defn arrow-desc->world
  "Converts one backend movement arrow desc (`:from`/`:to` as `[lat lon]` -
   the plugin boundary's coordinate order, NOT world coords) to the engine's
   arrow shape `{:id :fw [wx wy] :tw [wx wy] :weight :original :attribute}`.
   nil when either endpoint is missing - a half-drawn arrow has no
   direction to draw along, and the driver already filters most of these
   upstream (see backend overlayers.cljc's movement-layer-calc location-
   valid? guard) - this is just defense in depth."
  [{:keys [id from to weight original attribute]}]
  (when (and from to)
    (let [[from-lat from-lon] from
          [to-lat to-lon] to]
      {:id id
       :fw (proj/project from-lon from-lat)
       :tw (proj/project to-lon to-lat)
       :weight weight
       :original original
       :attribute attribute})))

(defn heatmap-point->world
  "Converts one backend heatmap point desc (`{:lat :lng <attr> v}`) to the
   engine's point shape `{:wx :wy :weight}`. weight is the desc's single
   non-:lat/:lng key's value when `extrema` is `:local` (falling back to 1
   when that key is missing or its value isn't a number - a mis-shaped
   point still renders, just unweighted), else a flat 1 (OL parity -
   heatmap-layer-calc's :global mode never asked for weighting)."
  [{:keys [lat lng] :as desc} extrema]
  (let [[wx wy] (proj/project lng lat)
        weight (if (= extrema :local)
                 (let [attr-key (first (keys (dissoc desc :lat :lng)))
                       v (get desc attr-key)]
                   (if (number? v) v 1))
                 1)]
    {:wx wx :wy wy :weight weight}))

(defn heatmap-extrema
  "`:local`|`:global` (default `:global`) read off a heatmap layer's static
   config desc (extra-fns' :feature-layer-desc, keyed by :layer-id) - OL
   parity, heatmap-layer.cljs's get-weight read the same key."
  [feature-layer-desc]
  (if (= :local (:extrema feature-layer-desc)) :local :global))

(defn- engine-add-layer!
  [engine id {:keys [kind] :as entry}]
  (case kind
    :movement (engine/add-arrow-layer! engine id entry)
    :heatmap (engine/add-heatmap-layer! engine id entry)
    (engine/add-vector-layer! engine id entry)))

(defn- engine-remove-layer!
  [engine id kind]
  (case kind
    :movement (engine/remove-arrow-layer! engine id)
    :heatmap (engine/remove-heatmap-layer! engine id)
    (engine/remove-vector-layer! engine id)))

(defn- engine-set-layer-visible!
  [engine id kind visible?]
  (case kind
    :movement (engine/set-arrow-layer-visible! engine id visible?)
    :heatmap (engine/set-heatmap-layer-visible! engine id visible?)
    (engine/set-vector-layer-visible! engine id visible?)))

(def overlayer-style
  "OL-parity static style for boundary/overlayer vector layers - solid
   strokes, since Pixi Graphics has no native line-dash support."
  {:stroke-width 1.5 :stroke-color 0x4b4f53 :stroke-alpha 0.9
   :fill-color 0x364655 :fill-alpha 0.15})

(defn area-style-fn
  "Pure per-feature style-fn for an area/feature-coloring vector layer (OL
   parity - the same join `render_helper/show-feature-layer-popup-fn-wrapper`
   uses to build that layer's popup content): a feature's string-keyed
   properties are looked up in `data-set` via `(select-keys props (keys
   feature-properties-config))`. No match -> nil, the vector-layer/draw! and
   engine/pick-vector-feature nil-skip convention (feature invisible and
   unpickable)."
  [feature-properties-config data-set]
  (fn [{:keys [properties]}]
    (let [data-key (select-keys properties (keys feature-properties-config))]
      (when-let [{:keys [color opacity]} (get data-set data-key)]
        {:fill-color (style/hex->int color)
         :fill-alpha (or opacity 0.5)
         :stroke-width 1
         :stroke-color 0x4b4f53
         :stroke-alpha 0.9}))))

(def ^:private default-esri-query
  "query?where=1%3D1&outFields=*&returnGeometry=true&f=geojson")

(defn- esri-query-url
  "OL parity (historical map/impl/openlayers/overlayer.cljs create-esri):
   server-url normalized to a trailing slash, plus either an explicit
   `:query` suffix or a default single unfiltered `f=geojson` fetch of the
   whole layer - Esri area-layer pagination is out of scope for stage 2 (see
   design doc)."
  [{:keys [server-url query]}]
  (let [base (if (str/ends-with? server-url "/") server-url (str server-url "/"))]
    (str base (if (seq query) query default-esri-query))))

(defn- resolve-geojson [extra-fns desc]
  (let [get-geojson (:geojson-object extra-fns)
        gj (when get-geojson (get-geojson (:file-path desc)))]
    (if gj
      (geo/parse-features gj)
      (do (warn "vector layer geojson source missing" {:desc desc})
          []))))

(defn- fetch-esri! [state-atom desc on-features]
  (if (str/blank? (:server-url desc))
    (do (warn "vector layer esri source missing :server-url" {:desc desc})
        (on-features []))
    (let [engine (:engine @state-atom)
          url (esri-query-url desc)]
      (when engine (engine/note-load-start! engine))
      (-> (js/fetch url)
          (.then (fn [resp] (.json resp)))
          (.then (fn [json]
                   (on-features (geo/parse-features json))
                   (when engine (engine/note-load-end! engine))))
          (.catch (fn [err]
                    (warn "vector layer esri fetch failed" {:desc desc :error err})
                    (on-features [])
                    (when engine (engine/note-load-end! engine))))))))

(defn resolve-geometry!
  "Resolves `desc`'s (`:type \"geojson\"|<esri>` plus `:file-path`/
   `:server-url`/`:query`) geometry and hands `[feature]` (per
   geo/parse-features, possibly `[]`) to `on-features`. \"geojson\" resolves
   synchronously via `(:geojson-object extra-fns)`; any other `:type` fetches
   an Esri FeatureServer query URL asynchronously (`js/fetch` -> `.json` ->
   geo/parse-features), noting settle load-start/end on whatever engine is
   already booted when the fetch begins - a layer resolved while headless
   doesn't block render-done on its own fetch. Never throws: a missing
   source or a failed fetch stages `[]` and logs a `warn` instead of
   crashing the caller."
  [state-atom extra-fns {:keys [type] :as desc} on-features]
  (if (= type "geojson")
    (on-features (resolve-geojson extra-fns desc))
    (fetch-esri! state-atom desc on-features)))

(defn stage-vector-layer!
  "Adds/replaces the `:vector-layers id` entry (kind :overlayer|:area|
   :movement|:heatmap) and registers it with the booted engine immediately -
   or leaves it purely staged while headless, for ensure-engine!'s boot
   replay to pick up."
  [state-atom id kind entry]
  (let [staged (assoc entry :kind kind)]
    (swap! state-atom assoc-in [:vector-layers id] staged)
    (when-let [engine (:engine @state-atom)]
      (engine-add-layer! engine id staged))))

(defn update-vector-layer-features!
  "Replaces just the :features of an already-staged vector layer - the
   resolve-geometry! completion callback for the async Esri path - and
   re-pushes the full entry to the engine if booted."
  [state-atom id features]
  (swap! state-atom update-in [:vector-layers id] assoc :features features)
  (when-let [engine (:engine @state-atom)]
    (when-let [entry (get-in @state-atom [:vector-layers id])]
      (engine/add-vector-layer! engine id entry))))

(defn set-vector-layer-visible!
  [state-atom id visible?]
  (let [kind (get-in @state-atom [:vector-layers id :kind])]
    (swap! state-atom update-in [:vector-layers id] assoc :visible? visible?)
    (when-let [engine (:engine @state-atom)]
      (engine-set-layer-visible! engine id kind visible?))))

(defn remove-vector-layer!
  [state-atom id]
  (let [kind (get-in @state-atom [:vector-layers id :kind])]
    (swap! state-atom update :vector-layers dissoc id)
    (when-let [engine (:engine @state-atom)]
      (engine-remove-layer! engine id kind))))

(defn register-staged-vector-layers!
  "Boot replay (ensure-engine!, after markers are pushed): register every
   vector layer staged while headless (overlayers + area layers, whatever
   their current :visible? - display-overlayer!/display-feature-layer! may
   have already toggled it before boot) with the freshly created engine, in
   three passes - overlayer/area vector layers first, then movement arrow
   layers, then heatmap layers - so a staged arrow layer's Graphics always
   ends up ABOVE any polygon layer's in the shared vector container's child
   order (heatmap sprites sidestep this entirely, always inserted at the
   container's bottom via addChildAt 0 regardless of registration order)."
  [state-map engine]
  (let [entries (:vector-layers state-map)
        of-kind (fn [ks] (into [] (keep (fn [[id entry]]
                                          (when (contains? ks (:kind entry)) [id entry])))
                               entries))]
    (doseq [[id entry] (of-kind #{:overlayer :area})]
      (engine/add-vector-layer! engine id entry))
    (doseq [[id entry] (of-kind #{:movement})]
      (engine/add-arrow-layer! engine id entry))
    (doseq [[id entry] (of-kind #{:heatmap})]
      (engine/add-heatmap-layer! engine id entry))))

;;;; Movement (arrows) / heatmap feature layers ------------------------------

(defn stage-arrow-layer!
  "object-manager create-feature-layer's :movement branch: stages an empty
   arrow layer (invisible) for `id` - create-arrow-features! fills it in
   once the driver hands over the actual arrow descs (see map/core.cljs's
   movement-feature-layer)."
  [state-atom id]
  (stage-vector-layer! state-atom id :movement {:arrows [] :visible? false}))

(defn stage-heatmap-layer!
  "object-manager create-feature-layer's :heatmap branch - see
   stage-arrow-layer!."
  [state-atom id]
  (stage-vector-layer! state-atom id :heatmap {:points [] :visible? false}))

(defn create-arrow-features!
  "object-manager create-arrow-features: converts `arrow-descs` (backend
   movement.cljc shape) via arrow-desc->world (dropping descs missing an
   endpoint) and replaces the layer's :arrows, re-pushing to the engine if
   booted. Stages the layer (kind :movement) if it isn't already - the
   driver always calls create-feature-layer first on a genuinely new layer,
   but this keeps the fn correct standalone (e.g. from tests) too."
  [state-atom id arrow-descs]
  (let [arrows (into [] (keep arrow-desc->world) arrow-descs)]
    (swap! state-atom update-in [:vector-layers id]
           (fn [entry] (assoc (or entry {:visible? false}) :kind :movement :arrows arrows)))
    (when-let [engine (:engine @state-atom)]
      (engine/add-arrow-layer! engine id (get-in @state-atom [:vector-layers id])))))

(defn clear-arrow-features!
  "object-manager clear-arrow-features: empties the layer's :arrows, keeping
   the staged entry (and thus feature-layer-created?) intact."
  [state-atom id]
  (swap! state-atom assoc-in [:vector-layers id :arrows] [])
  (when-let [engine (:engine @state-atom)]
    (when-let [entry (get-in @state-atom [:vector-layers id])]
      (engine/add-arrow-layer! engine id entry))))

(defn create-heatmap-features!
  "object-manager create-heatmap-features: converts `heatmap-data` (backend
   heatmap.cljc shape, `{:lat :lng <attr> v}` points) via heatmap-point->world,
   reading :extrema off `((:feature-layer-desc extra-fns) id)` at create time
   (documented OL-parity equivalence - see design doc), and replaces the
   layer's :points, re-pushing to the engine if booted."
  [state-atom id heatmap-data]
  (let [extra-fns (:extra-fns @state-atom)
        get-desc (:feature-layer-desc extra-fns)
        extrema (heatmap-extrema (when get-desc (get-desc id)))
        points (mapv #(heatmap-point->world % extrema) heatmap-data)]
    (swap! state-atom update-in [:vector-layers id]
           (fn [entry] (assoc (or entry {:visible? false}) :kind :heatmap :points points)))
    (when-let [engine (:engine @state-atom)]
      (engine/add-heatmap-layer! engine id (get-in @state-atom [:vector-layers id])))))

(defn clear-heatmap-features!
  "object-manager clear-heatmap-features - see clear-arrow-features!."
  [state-atom id]
  (swap! state-atom assoc-in [:vector-layers id :points] [])
  (when-let [engine (:engine @state-atom)]
    (when-let [entry (get-in @state-atom [:vector-layers id])]
      (engine/add-heatmap-layer! engine id entry))))

(defn clear-feature-layer-data!
  "state-handler hide-feature-layer's :movement/:heatmap branch: empties the
   ENGINE-rendered data for a staged movement/heatmap layer (dispatching by
   its current :kind) while keeping the registry entry itself - visibility
   is toggled off separately (inst/set-vector-layer-visible!) so the driver's
   hide+reuse re-render path (map/core.cljs movement-feature-layer/heatmap-
   feature-layer) still sees feature-layer-created? true afterwards."
  [state-atom id]
  (case (get-in @state-atom [:vector-layers id :kind])
    :movement (clear-arrow-features! state-atom id)
    :heatmap (clear-heatmap-features! state-atom id)
    nil))

(defn create-overlayer!
  "object-manager create-overlayer: stages geometry for `desc` (`:name` the
   overlayer id; `:type`/`:file-path`/`:server-url` the source) under the
   static OL-parity overlayer-style, invisible until display-overlayer!."
  [state-atom {:keys [name] :as desc}]
  (let [extra-fns (:extra-fns @state-atom)]
    (stage-vector-layer! state-atom name :overlayer
                         {:features [] :style overlayer-style :visible? false})
    (resolve-geometry! state-atom extra-fns desc
                       (fn [features] (update-vector-layer-features! state-atom name features)))))

(defn resolve-area-config
  "Static per-layer-type geometry/join desc for an area feature layer -
   looked up by `layer-map`'s `:feature-layer-id`, NOT its `:layer-id`. The
   two are different things: `:layer-id` is the per-instance id this
   particular layer occurrence is addressed by (create/display/hide/remove-
   feature-layer, the :vector-layers registry key); `:feature-layer-id` is
   the STATIC per-layer-*type* config's key (backend
   overlayers.cljc's feature-coloring-layer-calc emits both on the same map;
   render_helper.cljs's show-feature-layer-popup-fn-wrapper looks up the same
   static config the same way, by :feature-layer-id, for the popup content)."
  [get-config {:keys [feature-layer-id]}]
  (when get-config (get-config feature-layer-id)))

(defn create-area-feature-layer!
  "object-manager create-feature-layer for layer type :feature (area
   coloring): looks up the layer's static geometry desc via extra-fns'
   :feature-layer-config (resolve-area-config, keyed by :feature-layer-id,
   not the per-instance :layer-id), builds this layer's area-style-fn from
   that desc's :feature-properties joined against :data-set, and stages the
   layer (keyed by :layer-id, the id create/display/hide/remove-feature-layer
   address it by) invisible until display-feature-layer!."
  [state-atom {:keys [layer-id data-set] :as feature-layer}]
  (let [extra-fns (:extra-fns @state-atom)
        get-config (:feature-layer-config extra-fns)
        desc (resolve-area-config get-config feature-layer)]
    (stage-vector-layer! state-atom layer-id :area
                         {:features []
                          :style-fn (area-style-fn (:feature-properties desc) data-set)
                          :visible? false})
    (if desc
      (resolve-geometry! state-atom extra-fns desc
                         (fn [features] (update-vector-layer-features! state-atom layer-id features)))
      (warn "feature-layer-config missing for area layer"
            {:layer-id layer-id :feature-layer-id (:feature-layer-id feature-layer)}))))
