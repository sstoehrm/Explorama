(ns de.explorama.frontend.ui-base-harness
  "Render harness for the phase-2 migration: mounts batch-1 ui_base
   components in all catalog variants, then serializes computed styles of
   every node into #computed-styles so `chromium --dump-dom` captures them.

   Catalog rule: for each component, render one instance per combination of
   the variant axes its parameter-definition declares as :characteristics,
   with a stable :data-harness id per instance. Required params are filled
   with fixed literal values so the render is deterministic."
  (:require [reagent.dom :as rdom]
            [clojure.string :as str]
            [de.explorama.frontend.ui-base.utils.subs :as ui-subs]
            [de.explorama.frontend.ui-base.components.misc.chip :refer [chip]]
            [de.explorama.frontend.ui-base.components.misc.hint :refer [hint]]
            [de.explorama.frontend.ui-base.components.common.tooltip :refer [tooltip]]
            [de.explorama.frontend.ui-base.components.formular.button :refer [button]]
            [de.explorama.frontend.ui-base.components.formular.button-group :refer [button-group]]
            [de.explorama.frontend.ui-base.components.formular.card :refer [card]]
            [de.explorama.frontend.ui-base.components.formular.checkbox :refer [checkbox]]
            [de.explorama.frontend.ui-base.components.formular.collapsible-list :refer [collapsible-list]]
            [de.explorama.frontend.ui-base.components.formular.input-field :refer [input-field]]
            [de.explorama.frontend.ui-base.components.formular.textarea :refer [textarea]]
            [de.explorama.frontend.ui-base.components.formular.radio :refer [radio]]
            [de.explorama.frontend.ui-base.components.formular.select :refer [select]]))

(def ^:private noop (fn [& _]))

(defn- kw-part [k]
  (if k (name k) "default"))

(defn- section [title & children]
  (into [:div {:style {:padding "16px" :border-bottom "1px solid #ccc"}}
         [:h3 title]]
        children))

(defn- instance [id & body]
  (into [:div {:data-harness id
               :style {:margin "6px"
                       :display "inline-block"
                       :vertical-align "top"
                       :min-width "120px"}}]
        body))

;; ---------------------------------------------------------------- chip
(defn- chip-section []
  (section "chip"
           (for [variant [:primary :secondary]
                 size [:extra-small :small :normal :big]
                 brightness [nil :light :dark]]
             ^{:key (str variant size brightness)}
             [instance (str "chip-" (name variant) "-" (name size) "-" (kw-part brightness))
              [chip (cond-> {:variant variant :size size :label "Chip"}
                      brightness (assoc :brightness brightness))]])))

;; ---------------------------------------------------------------- button
(defn- button-section []
  (section "button"
           (for [variant [:primary :secondary :tertiary :back]
                 btype [:normal :warning]
                 size [:small :normal :big]
                 disabled? [false true]]
             ^{:key (str variant btype size disabled?)}
             [instance (str "button-" (name variant) "-" (name btype) "-" (name size)
                            "-" (if disabled? "disabled" "enabled"))
              [button {:variant variant :type btype :size size
                       :disabled? disabled? :label "Label" :on-click noop}]])))

;; ---------------------------------------------------------------- button-group
(def ^:private bg-items
  [{:id "one" :label "One" :on-click noop}
   {:id "two" :label "Two" :on-click noop}
   {:id "three" :label "Three" :on-click noop}])

(defn- button-group-section []
  (section "button-group"
           ^{:key "plain"}
           [instance "button-group-plain"
            [button-group {:items bg-items}]]
           ^{:key "active"}
           [instance "button-group-active"
            [button-group {:items bg-items :active-item "two"}]]
           ^{:key "disabled"}
           [instance "button-group-disabled"
            [button-group {:items bg-items :disabled? true}]]))

;; ---------------------------------------------------------------- card
(def ^:private carousel-items
  [{:title "Slide 1" :content "First slide content"}
   {:title "Slide 2" :content "Second slide content"}])

(defn- card-section []
  (section "card"
           (concat
            [^{:key "text"}
             [instance "card-text"
              [card {:type :text :content "Some text card content."}]]
             ^{:key "childs"}
             [instance "card-childs"
              [card {:type :childs} [:p "Child paragraph"]]]
             ^{:key "carousel"}
             [instance "card-carousel"
              [:div {:style {:width "260px" :height "120px"}}
               [card {:type :carousel :items carousel-items :auto-slide? false}]]]]
            (for [orientation [:horizontal :vertical]
                  icon-position [:start :end]
                  disabled? [false true]]
              ^{:key (str "btn" orientation icon-position disabled?)}
              [instance (str "card-button-" (name orientation) "-" (name icon-position)
                             "-" (if disabled? "disabled" "enabled"))
               [card {:type :button
                      :title "Card title"
                      :content "Card content"
                      :icon :cog
                      :orientation orientation
                      :icon-position icon-position
                      :disabled? disabled?
                      :on-click noop}]]))))

;; ---------------------------------------------------------------- checkbox
(defn- checkbox-section []
  (section "checkbox"
           (for [box-position [:left :right]
                 as-toggle? [false true]
                 checked? [false true]
                 disabled? [false true]]
             (let [id (str "checkbox-" (name box-position) "-"
                           (if as-toggle? "toggle" "box") "-"
                           (if checked? "on" "off") "-"
                           (if disabled? "disabled" "enabled"))]
               ^{:key id}
               [instance id
                [checkbox {:id id
                           :box-position box-position
                           :as-toggle? as-toggle?
                           :checked? checked?
                           :disabled? disabled?
                           :label "Label"
                           :on-change noop}]]))))

;; ---------------------------------------------------------------- collapsible-list
(defn- collapsible-list-section []
  (section "collapsible-list"
           ^{:key "cl"}
           [instance "collapsible-list-default"
            [:div {:style {:width "280px" :height "160px"}}
             [collapsible-list
              {:items [{:id "p1" :label "Parent 1"}
                       {:id "p2" :label "Parent 2"}]
               :default-open-id "p1"
               :collapse-items-fn (fn [pid]
                                    [{:id (str pid "-c1") :label "Child 1"}
                                     {:id (str pid "-c2") :label "Child 2"}])
               :on-click noop}]]]))

;; ---------------------------------------------------------------- input-field
(defn- input-value [type]
  (case type
    :number 42
    :color "#3366ff"
    "Value"))

(defn- input-field-section []
  (section "input-field"
           (for [type [:text :number :color :password]
                 disabled? [false true]
                 compact? [false true]]
             (let [id (str "input-field-" (name type) "-"
                           (if disabled? "disabled" "enabled") "-"
                           (if compact? "compact" "regular"))]
               ^{:key id}
               [instance id
                [input-field {:id id
                              :type type
                              :label "Label"
                              :placeholder "placeholder"
                              :value (input-value type)
                              :disabled? disabled?
                              :compact? compact?
                              :on-change noop
                              :on-blur noop}]]))))

;; ---------------------------------------------------------------- textarea
(defn- textarea-section []
  (section "textarea"
           (for [borderless? [false true]
                 disabled? [false true]]
             (let [id (str "textarea-"
                           (if borderless? "borderless" "bordered") "-"
                           (if disabled? "disabled" "enabled"))]
               ^{:key id}
               [instance id
                [textarea {:id id
                           :label "Label"
                           :placeholder "placeholder"
                           :value "Text content"
                           :borderless? borderless?
                           :disabled? disabled?
                           :on-change noop}]]))))

;; ---------------------------------------------------------------- radio
;; :label-variant :img is skipped: it requires an image :src, which introduces
;; async image loading (nondeterministic paint). Only :simple is rendered.
(defn- radio-section []
  (section "radio"
           (for [strong? [false true]
                 checked? [false true]
                 disabled? [false true]]
             (let [id (str "radio-"
                           (if strong? "strong" "plain") "-"
                           (if checked? "on" "off") "-"
                           (if disabled? "disabled" "enabled"))]
               ^{:key id}
               [instance id
                [radio (cond-> {:id id
                                :name "radio-group"
                                :label "Label"
                                :aria-label "aria"
                                :checked? checked?
                                :disabled? disabled?
                                :on-change noop}
                         strong? (assoc :strong-label "Strong"))]]))))

;; ---------------------------------------------------------------- select
(def ^:private select-options
  [{:label "Alpha" :value "a"}
   {:label "Beta" :value "b"}
   {:label "Gamma" :value "c"}])

(defn- select-values [is-multi?]
  (if is-multi?
    (vec (take 2 select-options))
    (first select-options)))

(defn- select-section []
  (section "select"
           (for [is-multi? [false true]
                 is-searchable? [false true]
                 disabled? [false true]]
             (let [id (str "select-"
                           (if is-multi? "multi" "single") "-"
                           (if is-searchable? "search" "nosearch") "-"
                           (if disabled? "disabled" "enabled"))]
               ^{:key id}
               [instance id
                [:div {:style {:width "220px"}}
                 [select {:options select-options
                          :values (select-values is-multi?)
                          :aria-label "aria"
                          :label "Label"
                          :is-multi? is-multi?
                          :is-searchable? is-searchable?
                          :disabled? disabled?
                          :on-change noop}]]]))))

;; ---------------------------------------------------------------- hint
(defn- hint-section []
  (section "hint"
           (for [variant [:default :info :warning :error]
                 with-title? [false true]]
             (let [id (str "hint-" (name variant) "-"
                           (if with-title? "titled" "plain"))]
               ^{:key id}
               [instance id
                [hint (cond-> {:variant variant
                               :content "Hint content"}
                        with-title? (assoc :title "Hint title"))]]))))

;; ---------------------------------------------------------------- tooltip
;; The tooltip popup only appears on hover (manual); the static capture covers
;; the wrapped trigger element only.
(defn- tooltip-section []
  (section "tooltip"
           ^{:key "t-span"}
           [instance "tooltip-span"
            [tooltip {:text "Tooltip text" :direction :up}
             [:span "Hover me"]]]
           ^{:key "t-chip"}
           [instance "tooltip-chip"
            [tooltip {:text "Tooltip text" :direction :down}
             [chip {:label "Chip" :variant :primary}]]]
           ^{:key "t-button"}
           [instance "tooltip-button"
            [tooltip {:text "Tooltip text" :direction :right}
             [:span [button {:label "Button" :on-click noop}]]]]))

(def ^:private sections
  [["chip" chip-section]
   ["button" button-section]
   ["button-group" button-group-section]
   ["card" card-section]
   ["checkbox" checkbox-section]
   ["collapsible-list" collapsible-list-section]
   ["input-field" input-field-section]
   ["textarea" textarea-section]
   ["radio" radio-section]
   ["select" select-section]
   ["hint" hint-section]
   ["tooltip" tooltip-section]])

(defn- only-filter []
  ;; ?only=chip,button renders just those sections (debug aid). No param = all.
  (let [q (.-search js/location)
        m (re-find #"[?&]only=([^&]+)" q)]
    (when m
      (set (str/split (js/decodeURIComponent (second m)) #",")))))

(defn- app []
  (let [only (only-filter)]
    (into [:div#harness-root]
          (for [[nm f] sections
                :when (or (nil? only) (contains? only nm))]
            ^{:key nm} [f]))))

(defn- serialize-computed-styles! []
  (let [nodes (.querySelectorAll js/document "#harness-root *")
        entries (for [i (range (.-length nodes))
                      :let [n (aget nodes i)
                            cs (js/getComputedStyle n)]]
                  ;; stable node key: nearest data-harness ancestor + tag + index
                  [(str (some-> (.closest n "[data-harness]") (.getAttribute "data-harness"))
                        "|" (.-tagName n) "|" i)
                   (into {} (for [j (range (.-length cs))
                                  :let [prop (.item cs j)]]
                              [prop (.getPropertyValue cs prop)]))])
        pre (.createElement js/document "pre")]
    (set! (.-id pre) "computed-styles")
    (set! (.-textContent pre) (js/JSON.stringify (clj->js (into {} entries))))
    (.appendChild (.-body js/document) pre)))

(defn ^:export init []
  ;; ui_base logs an error for every keyword aria-label unless a translation
  ;; function is registered; supply an identity one (keyword -> name string).
  (ui-subs/set-translation-fn (fn [word-key] (atom (name word-key))))
  (rdom/render [app] (.getElementById js/document "app"))
  ;; wait a tick for reagent to flush, then serialize
  (js/setTimeout serialize-computed-styles! 1000))

;; Auto-run at load time. The :simple bundle is a single self-contained script
;; placed after <div id="app"> in the body, so the namespace evaluates
;; synchronously once #app exists -- no reliance on the exported global
;; surviving the webpack module wrapper.
(defonce ^:private started
  (do (init) true))
