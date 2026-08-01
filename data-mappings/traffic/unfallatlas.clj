(ns unfallatlas
  (:require [clojure.string :as str]))

(def bundesland
  {"01" "Schleswig-Holstein"
   "02" "Hamburg"
   "03" "Niedersachsen"
   "04" "Bremen"
   "05" "Nordrhein-Westfalen"
   "06" "Hessen"
   "07" "Rheinland-Pfalz"
   "08" "Baden-Württemberg"
   "09" "Bayern"
   "10" "Saarland"
   "11" "Berlin"
   "12" "Brandenburg"
   "13" "Mecklenburg-Vorpommern"
   "14" "Sachsen"
   "15" "Sachsen-Anhalt"
   "16" "Thüringen"})

(def wochentag
  {"1" "Sonntag"
   "2" "Montag"
   "3" "Dienstag"
   "4" "Mittwoch"
   "5" "Donnerstag"
   "6" "Freitag"
   "7" "Samstag"})

(def unfallkategorie
  {"1" "Unfall mit Getöteten"
   "2" "Unfall mit Schwerverletzten"
   "3" "Unfall mit Leichtverletzten"})

(def unfallart
  {"0" "Unfall anderer Art"
   "1" "Zusammenstoß mit anfahrendem/anhaltendem/ruhendem Fahrzeug"
   "2" "Zusammenstoß mit vorausfahrendem/wartendem Fahrzeug"
   "3" "Zusammenstoß mit seitlich in gleicher Richtung fahrendem Fahrzeug"
   "4" "Zusammenstoß mit entgegenkommendem Fahrzeug"
   "5" "Zusammenstoß mit einbiegendem/kreuzendem Fahrzeug"
   "6" "Zusammenstoß zwischen Fahrzeug und Fußgänger"
   "7" "Aufprall auf Fahrbahnhindernis"
   "8" "Abkommen von Fahrbahn nach rechts"
   "9" "Abkommen von Fahrbahn nach links"})

(def unfalltyp
  {"1" "Fahrunfall"
   "2" "Abbiegeunfall"
   "3" "Einbiegen/Kreuzen-Unfall"
   "4" "Überschreiten-Unfall"
   "5" "Unfall durch ruhenden Verkehr"
   "6" "Unfall im Längsverkehr"
   "7" "sonstiger Unfall"})

(def lichtverhaeltnisse
  {"0" "Tageslicht"
   "1" "Dämmerung"
   "2" "Dunkelheit"})

(def strassenzustand
  {"0" "trocken"
   "1" "nass/feucht/schlüpfrig"
   "2" "winterglatt"})

(def beteiligung
  [["IstRad" "Fahrrad"]
   ["IstPKW" "Pkw"]
   ["IstFuss" "Fußgänger"]
   ["IstKrad" "Kraftrad"]
   ["IstGkfz" "Güterkraftfahrzeug"]
   ["IstSonstige" "Sonstige"]])

(defn cell [row column]
  (str/trim (str (get row column ""))))

(defn label [table column]
  (fn [row]
    (get table (cell row column) "unbekannt")))

(defn beteiligte [row]
  (mapv second
        (filter (fn [[column _]] (= "1" (cell row column)))
                beteiligung)))

(defn int-str [row column]
  (let [digits (str/replace (cell row column) #"[^0-9]" "")
        digits (str/replace digits #"^0+(?=\d)" "")]
    (if (= "" digits) "0" digits)))

(defn dec-str [row column]
  (let [value (str/replace (cell row column) "," ".")]
    (if (str/includes? value ".")
      value
      (str value ".0"))))

;; Der Unfallatlas enthält bewusst keinen Unfalltag, nur Jahr und Monat.
(defn unfalldatum [row]
  (let [monat (cell row "UMONAT")
        monat (if (= 1 (count monat)) (str "0" monat) monat)]
    (str (cell row "UJAHR") "-" monat "-01")))

(def desc
  {:meta-data {:file-format :csv
               :csv {:separator ";"
                     :quote "\""
                     :encoding "UTF-8"
                     :limit 1000}}
   :mapping
   {:datasource {:name [:value "Unfallatlas"]
                 :global-id [:value "source-unfallatlas"]}
    :items
    [{:global-id [:field "UIDENTSTLAE"]
      :features
      [{:facts [{:name [:value "Unfallstunde"]
                 :type [:value "integer"]
                 :value [:convert (fn [row] (int-str row "USTUNDE"))]}]
        :locations [{:point [:convert (fn [row]
                                        [(dec-str row "YGCSWGS84")
                                         (dec-str row "XGCSWGS84")])]}]
        :contexts
        [{:name [:convert (label bundesland "ULAND")]
          :global-id [:id-generate ["bundesland" :text] :name]
          :type [:value "Bundesland"]}
         {:name [:convert (label unfallkategorie "UKATEGORIE")]
          :global-id [:id-generate ["unfallkategorie" :text] :name]
          :type [:value "Unfallkategorie"]}
         {:name [:convert (label unfallart "UART")]
          :global-id [:id-generate ["unfallart" :text] :name]
          :type [:value "Unfallart"]}
         {:name [:convert (label unfalltyp "UTYP1")]
          :global-id [:id-generate ["unfalltyp" :text] :name]
          :type [:value "Unfalltyp"]}
         {:name [:convert (label lichtverhaeltnisse "ULICHTVERH")]
          :global-id [:id-generate ["lichtverhaeltnisse" :text] :name]
          :type [:value "Lichtverhältnisse"]}
         {:name [:convert (label strassenzustand "IstStrassenzustand")]
          :global-id [:id-generate ["strassenzustand" :text] :name]
          :type [:value "Straßenzustand"]}
         {:name [:convert (label wochentag "UWOCHENTAG")]
          :global-id [:id-generate ["wochentag" :text] :name]
          :type [:value "Wochentag"]}
         {:name [:convert beteiligte]
          :global-id [:id-generate ["beteiligung" :text] :name]
          :type [:value "Beteiligung"]}]
        :dates [{:value [:convert unfalldatum]
                 :type [:value "occured-at"]}]}]}]}})

desc
