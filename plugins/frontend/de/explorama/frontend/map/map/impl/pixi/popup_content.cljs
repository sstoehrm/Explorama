(ns de.explorama.frontend.map.map.impl.pixi.popup-content
  (:require [clojure.string :as str]
            [de.explorama.frontend.map.utils :refer [font-color]]
            [de.explorama.frontend.ui-base.utils.interop :refer [format]]))

(defn- date-attr? [attribute-label]
  (#{"month" "year" "day" "date"} attribute-label))

(defn- attribute-desc [localize-num-fn attribute-label-fn [attribute attribute-value]]
  (let [attribute-label (attribute-label-fn attribute)
        value-text (cond
                     (and (vector? attribute-value)
                          (number? (first attribute-value))) (str/join ", "
                                                                       (map localize-num-fn attribute-value))
                     (vector? attribute-value) (str/join ", " attribute-value)
                     (and (number? attribute-value)
                          (not (date-attr? attribute))) (localize-num-fn attribute-value)
                     :else attribute-value)]
    (str "<dt>" attribute-label "</dt>"
         "<dd>" value-text "</dd>")))

(defn gen-popup-content [localize-num-fn
                         attribute-label-fn
                         color event
                         title-attributes
                         display-attributes]
  (let [selected-attributes (cond (and (= display-attributes :all)
                                       (object? event)) ;special case configuration for feature-layer
                                  (array-seq (js-keys event))

                                  (= display-attributes :all)
                                  (keys event)

                                  (vector? (first display-attributes)) ;field-assignment from marker-layouts
                                  (mapv second display-attributes)

                                  :else display-attributes) ;configured feature-layer propertie-list
        title-attributes-set (set title-attributes)
        attribute-desc-fn (partial attribute-desc localize-num-fn attribute-label-fn)]
    (when (and (seq event)
               (or (seq title-attributes)
                   (seq selected-attributes)))
      (format "<div class=\"popup-content\" style=\"width: 350px;\"> %s %s </div>"
              (if (and (seq color) title-attributes)
                (str "<dl class=\"colored-bg\" style=\"background-color: " color "; color: " (font-color color) ";\">"
                     (str/join (mapv attribute-desc-fn
                                     (sort-by first
                                              (filter identity
                                                      (select-keys event title-attributes)))))
                     "</dl>")
                "")
              (if (seq selected-attributes)
                (str "<dl>"
                     (str/join (mapv attribute-desc-fn
                                     (sort-by first
                                              (select-keys event
                                                           (filterv #(not (title-attributes-set %))
                                                                    selected-attributes)))))
                     "</dl>")
                "")))))
