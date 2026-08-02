(ns de.explorama.shared.data-transformer.util
  #?(:cljs (:require [cljs-time.format :as f]))
  #?(:clj (:import [java.time LocalDate]
                   [java.time.format DateTimeFormatter DateTimeFormatterBuilder]
                   [java.time.temporal ChronoField]
                   [java.util Locale])))

(def string->char first)

#?(:clj
   (defn formatter [fmt-str]
     (-> (DateTimeFormatterBuilder.)
         (.parseCaseInsensitive)
         (.appendPattern fmt-str)
         (.parseDefaulting ChronoField/MONTH_OF_YEAR 1)
         (.parseDefaulting ChronoField/DAY_OF_MONTH 1)
         (.toFormatter Locale/ENGLISH)))
   :cljs (def formatter f/formatter))

#?(:clj (defn parse [fmt s] (LocalDate/parse s ^DateTimeFormatter fmt))
   :cljs (def parse f/parse))

#?(:clj (defn unparse [fmt d] (.format ^DateTimeFormatter fmt d))
   :cljs (def unparse f/unparse))

(def date-format "yyyy-MM-dd")
