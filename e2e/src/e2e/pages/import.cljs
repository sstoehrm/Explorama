(ns e2e.pages.import
  (:require [promesa.core :as p]))

;; The import tool is registered with :tool-group :hidden, so the welcome
;; screen's Import card is its only entry point.
(def ^:private welcome-card-text "Use your data for analysis.")

;; The generated mapping otherwise defaults the datasource name to
;; "Placeholder"; the search journey after commit needs a known, stable name.
(def ^:private datasource-name "e2e-import")

(defn upload-csv [page path]
  (p/do
    (.click (.getByRole page "button" #js {:name welcome-card-text}))
    (.setInputFiles (.first (.locator page "input[type=file]")) path)))

;; The warning/summary/done dialogs and the workspace behind the overlay both
;; render their own "Close"-labeled controls, so every locator here is scoped
;; to the import overlay panel to avoid ambiguity.
(defn- overlay [page]
  (.locator page ".welcome__page"))

(defn- overlay-button [page name]
  (.getByRole (overlay page) "button" #js {:name name :exact true}))

;; The mapping step's dialogs (warning/summary/done) each unmount entirely
;; when hidden, so a button locator never matches across two of them at
;; once - but two of the three happen to share a button label ("Yes"), so
;; a wait keyed on the button alone can't tell which one is currently up.
;;
;; The fixture's CSV has no explicit id column, so the mapping's warning
;; dialog always appears once ("The ID for each row will be automatically
;; generated."); proceeding through it ("Yes") triggers the real backend
;; import-file call, whose success re-shows an import-summary dialog also
;; confirmed via "Yes" - that second click is the actual commit-import call.
(defn commit [page expect]
  (p/do
    (-> (expect (overlay-button page "Import")) (.toBeVisible #js {:timeout 30000}))
    (.fill (.getByRole (overlay page) "textbox" #js {:name "Datasource name"}) datasource-name)
    (.blur (.getByRole (overlay page) "textbox" #js {:name "Datasource name"}))
    (.click (overlay-button page "Import"))
    (.click (overlay-button page "Yes"))
    ;; Both the warning dialog and the import-summary dialog it leads to
    ;; render a "Yes" button, so waiting on that button alone can't tell
    ;; which of the two is currently up - the summary's "New Events: 3" is
    ;; unique to it (only rendered on a real, successful import-file
    ;; response) and is what the second click below must wait for before it
    ;; can safely target the summary dialog's Yes (the actual commit-import
    ;; trigger) instead of racing the warning dialog's.
    (-> (expect (.getByText (overlay page) "New Events: 3")) (.toBeVisible #js {:timeout 30000}))
    (.click (overlay-button page "Yes"))
    (-> (expect (overlay-button page "OK")) (.toBeVisible #js {:timeout 30000}))
    (.click (overlay-button page "OK"))
    (.click (overlay-button page "Close"))
    (.waitForSelector page ".welcome__page" #js {:state "detached" :timeout 30000})))
