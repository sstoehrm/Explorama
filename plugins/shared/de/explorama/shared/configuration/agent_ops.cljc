(ns de.explorama.shared.configuration.agent-ops)

(def ops
  [{:op :configuration/labels :side :frontend :doc "Attribute labels in the user's language." :input [:map {:closed true}] :output :any}
   {:op :configuration/theme :side :frontend :doc "The active theme." :input [:map {:closed true}] :output :any}
   {:op :configuration/languages :side :frontend :doc "Available languages." :input [:map {:closed true}] :output :any}
   {:op :configuration/entries :side :backend
    :doc "List the user's stored configuration entries for :config-types, e.g. #{:i18n :layouts :theme :overlayers :topics}."
    :input [:map {:closed true} [:config-types [:set keyword?]]] :output :any}
   {:op :configuration/entry :side :backend :doc "One entry." :input [:map {:closed true} [:config-type keyword?] [:config-id :any]] :output :any}
   {:op :configuration/set-entry :side :backend
    :doc "Create or replace one entry; the store validates it per config type. Read the existing entry first and modify it. Returns the stored entry."
    :input [:map {:closed true} [:config-type keyword?] [:config-id :any] [:entry :any]] :output :any}])
