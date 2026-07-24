(ns de.explorama.frontend.map.pixi.markers
  (:require [de.explorama.frontend.map.pixi.viewport :as vp]
            ["pixi.js-legacy" :refer [Graphics Sprite]]))

(def ^:const base-radius 6)

(defn circle-texture [^js app radius]
  (let [g (Graphics.)]
    (.beginFill g 0xffffff)
    (.drawCircle g radius radius radius)
    (.endFill g)
    (.generateTexture (.-renderer app) g)))

(defn- ensure-sprite [^js container ^js texture index id]
  (or (get @index id)
      (let [s (Sprite. texture)]
        (set! (.-anchor.x s) 0.5)
        (set! (.-anchor.y s) 0.5)
        (.addChild container s)
        (swap! index assoc id s)
        s)))

(defn render-markers!
  [^js _app ^js container ^js texture index markers vpt]
  (let [wanted (set (map :id markers))]
    (doseq [[id ^js sprite] @index
            :when (not (contains? wanted id))]
      (.removeChild container sprite)
      (.destroy sprite)
      (swap! index dissoc id))
    (doseq [{:keys [id lon lat color highlighted?]} markers]
      (let [^js s (ensure-sprite container texture index id)
            [sx sy] (vp/->screen vpt lon lat)
            scale (if highlighted? 1.6 1.0)]
        (set! (.-x s) sx)
        (set! (.-y s) sy)
        (set! (.-tint s) (or color 0x000000))
        (set! (.-alpha s) 0.7)
        (set! (.-scale.x s) scale)
        (set! (.-scale.y s) scale)))))
