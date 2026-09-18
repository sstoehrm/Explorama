(ns de.explorama.backend.agent-gateway.skills
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [de.explorama.shared.agent-gateway.catalog :as catalog]
            [de.explorama.shared.agent-gateway.errors :as errors]))

(def skills-dir "../../agent/.claude/skills")

(defn- entry-schema [entry]
  (if (map? (second entry)) (nth entry 2) (second entry)))

(defn example [form]
  (cond
    (vector? form)
    (let [[head & more] form
          more (if (map? (first more)) (rest more) more)]
      (case head
        :map (into {} (map (fn [[k :as entry]] [k (example (entry-schema entry))])) more)
        :vector [(example (first more))]
        :set #{(example (first more))}
        :tuple (mapv example more)
        :enum (first more)
        :maybe (example (first more))
        :string "text"
        '<value>))
    (contains? #{'string? 'str string?} form) "text"
    (contains? #{'int? 'number? 'pos-int? 'nat-int? 'double? int? number? pos-int? nat-int? double?} form) 1
    (contains? #{'boolean? boolean?} form) true
    (contains? #{'keyword? 'qualified-keyword? keyword? qualified-keyword?} form) :keyword
    (= :any form) '<value>
    :else '<value>))

(defn- edn-block [value]
  (str "```clojure\n" (str/trim (with-out-str (pprint/pprint value))) "\n```"))

(defn- op-section [{:keys [op doc side input timeout-ms]}]
  (let [input (example input)
        frontend? (= :frontend side)]
    (str "## `" op "`\n\n" doc "\n\n"
         "Runs on the " (name side) (when timeout-ms (str ", answers within " timeout-ms " ms")) ".\n\n"
         "Request:\n\n"
         "```bash\ncurl -sS -X POST \"$EXPLORAMA_URL/api/agent/ops/" (namespace op) "/" (name op) "\" \\\n"
         "  -H \"Content-Type: application/edn\" -H \"$EXPLORAMA_AUTH_HEADER\" \\\n"
         "  --data-binary '" (pr-str (cond-> {:user "<user>" :params input}
                                       frontend? (assoc :client-id "<client-id>"))) "'\n```\n\n"
         "Input schema:\n\n" (edn-block (catalog/public-declaration-input op)) "\n\n"
         "Output schema:\n\n" (edn-block (catalog/public-declaration-output op)) "\n")))

(defn- plugin-skill [plugin declarations]
  (str "---\nname: explorama-" plugin "\n"
       "description: Operations of Explorama's " plugin " plugin over the agent gateway. Load the explorama skill first for connection and workflow rules.\n---\n\n"
       "# Explorama " plugin " operations\n\n"
       "Every response is `{:status :ok :result ...}` or `{:status :error :error {:type ... :message ...}}`. Error types: "
       (str/join ", " (map #(str "`" % "`") (sort-by str (keys errors/http-status)))) ".\n\n"
       (str/join "\n" (map op-section (sort-by (comp str :op) declarations)))))

(defn render-all []
  (into {}
        (map (fn [[plugin declarations]]
               [(str "explorama-" plugin "/SKILL.md") (plugin-skill plugin declarations)]))
        (group-by (comp namespace :op) (vals catalog/ops))))

(defn render! []
  (doseq [[path content] (render-all)]
    (let [file (io/file skills-dir path)]
      (io/make-parents file)
      (spit file content)
      (println "wrote" (.getPath file)))))

(defn -main [& _]
  (render!)
  (shutdown-agents))
