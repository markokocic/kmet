(ns kmet.app.ui.test-resource-config
  "Resource-config screen tests (pi: config-selector.ts): row model,
   global/project scope toggles and their settings writes, tri-state
   cycling and search."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [babashka.fs :as fs]
            [kmet.app.packages :as pkgs]
            [kmet.app.ui.resource-config :as rc]
            [kmet.config :as cfg]
            [kmet.tui.protocols :as protocols]))

(defn- tmp-dir []
  (str (fs/create-temp-dir {:dir (System/getenv "TMPDIR")})))

(defn- make-package
  [root]
  (fs/create-dirs (str root "/extensions"))
  (fs/create-dirs (str root "/skills/root"))
  (spit (str root "/extensions/one.clj") "(ns pkg-one)\n")
  (spit (str root "/extensions/two.clj") "(ns pkg-two)\n")
  (spit (str root "/skills/root/SKILL.md")
        "---\nname: skill-a\ndescription: skill a\n---\nbody\n")
  root)

(defn- with-settings
  "Run F with settings paths isolated and the given :user/:project settings
   maps written to the files. The read/save redefs are file-based, so
   read-after-write round-trips behave like production."
  [f {:keys [user project]}]
  (let [global-dir (tmp-dir)
        project-dir (str (fs/path (tmp-dir) ".kmet"))
        read-file (fn [path default]
                    (if (fs/exists? path)
                      (or (edn/read-string (slurp path)) default)
                      default))]
    (fs/create-dirs global-dir)
    (when user
      (spit (str (fs/path global-dir "settings.edn")) (pr-str user)))
    (when project
      (fs/create-dirs project-dir)
      (spit (str (fs/path project-dir "settings.edn")) (pr-str project)))
    (with-redefs [cfg/read-global-settings-map (fn [] (read-file (str (fs/path global-dir "settings.edn")) {}))
                  cfg/read-project-settings-map (fn [] (read-file (str (fs/path project-dir "settings.edn")) {}))
                  cfg/save-setting! (fn [path value]
                                      (spit (str (fs/path global-dir "settings.edn"))
                                            (pr-str (assoc-in (read-file (str (fs/path global-dir "settings.edn")) {}) path value))))
                  cfg/save-project-setting! (fn [path value]
                                              (fs/create-dirs project-dir)
                                              (spit (str (fs/path project-dir "settings.edn"))
                                                    (pr-str (assoc-in (read-file (str (fs/path project-dir "settings.edn")) {}) path value))))
                  cfg/get-agent-dir (fn [] global-dir)
                  cfg/project-dir (fn [] project-dir)]
      (f {:global-dir global-dir :project-dir project-dir}))))

(defn- render-lines [screen width]
  (protocols/render screen width))

(defn- user-file-packages [ctx]
  (let [f (str (fs/path (:global-dir ctx) "settings.edn"))]
    (if (fs/exists? f)
      (:packages (edn/read-string (slurp f)))
      [])))

(defn- project-file-packages [ctx]
  (let [f (str (fs/path (:project-dir ctx) "settings.edn"))]
    (if (fs/exists? f)
      (:packages (edn/read-string (slurp f)))
      [])))

(def ^:const K-DOWN "\u001b[B")
(def ^:const K-UP "\u001b[A")
(def ^:const K-TAB "\t")
(def ^:const K-ESC "\u001b")
(def ^:const K-PGUP "\u001b[5~")
(def ^:const K-PGDN "\u001b[6~")

(defn- first-item-row
  "The first :item row of the screen."
  [screen]
  (first (filter #(= :item (:kind %)) (rc/screen-rows screen))))

(defn- item-rows [screen]
  (filterv #(= :item (:kind %)) (rc/screen-rows screen)))

;; ─── Global scope ─────────────────────────────────────────────────────────

(t/deftest test-screen-global-view
  (let [dir (make-package (tmp-dir))]
    (with-settings
      (fn [_]
        (let [screen (rc/make-resource-config-screen :rows 40)
              rows (rc/screen-rows screen)
              items (item-rows screen)]
          (t/is (= 3 (count items)))
          (t/is (= 2 (count (filter #(= :extensions (:resource-type (:item %))) items))))
          (t/is (some #(and (= :group (:kind %))
                            (str/includes? (:label (:group %)) (fs/file-name dir)))
                      rows))
          (t/is (every? :enabled items))
          (t/testing "header renders"
            (let [lines (render-lines screen 80)]
              (t/is (some #(str/includes? % "Global Resources") lines))
              (t/is (some #(str/includes? % "~/.kmet/agent/settings.edn") lines))
              (t/is (some #(str/includes? % "space toggle") lines))))
          (t/testing "item lines render the checkbox"
            (let [lines (render-lines screen 80)]
              (t/is (= 3 (count (filter #(re-find #"\[x\]" %) lines))))))))
      {:user {:packages [dir]}})))

(t/deftest test-screen-global-toggle-disables
  (let [dir (make-package (tmp-dir))]
    (with-settings
      (fn [ctx]
        (let [screen (rc/make-resource-config-screen :rows 40)]
         ;; selection starts on the first item; one down → second item
          (protocols/handle-input screen K-DOWN)
          (protocols/handle-input screen " ")
          (let [entries (user-file-packages ctx)]
            (t/is (= 1 (count entries)))
            (t/is (map? (first entries)))
            (t/is (= 1 (count (:extensions (first entries)))))
            (t/is (str/starts-with? (first (:extensions (first entries))) "-")))
          (t/testing "the screen re-reads the file — the item shows disabled"
            (t/is (false? (:enabled (second (item-rows screen))))))
         ;; toggle back on
          (protocols/handle-input screen " ")
          (let [entries (user-file-packages ctx)]
            (t/is (str/starts-with? (first (:extensions (first entries))) "+")))
          (t/is (every? :enabled (item-rows screen)))))
      {:user {:packages [dir]}})))

;; ─── Project scope ────────────────────────────────────────────────────────

(t/deftest test-screen-project-inherited-tri-state
  (let [dir (make-package (tmp-dir))]
    (with-settings
      (fn [ctx]
        (let [screen (rc/make-resource-config-screen :rows 40
                                                     :write-scope :project
                                                     :project-mode? true)]
         ;; the user package shows as inherited global
          (t/is (= 3 (count (item-rows screen))))
          (t/is (every? :inherited? (item-rows screen)))
          (t/is (every? #(= :inherit (:override-state %)) (item-rows screen)))
          (t/testing "space cycles unload → writes a delta entry"
            (protocols/handle-input screen " ")
            (let [entries (project-file-packages ctx)]
              (t/is (= 1 (count entries)))
              (let [entry (first entries)]
                (t/is (map? entry))
                (t/is (false? (:autoload entry)))
                (t/is (str/starts-with? (first (:extensions entry)) "-"))))
            (t/is (= :unload (:override-state (first-item-row screen))))
            (t/testing "rows show the project unload suffix"
              (let [lines (render-lines screen 100)]
                (t/is (some #(str/includes? % "project unload") lines))))
           ;; cycle to load
            (protocols/handle-input screen " ")
            (let [entry (first (project-file-packages ctx))]
              (t/is (str/starts-with? (first (:extensions entry)) "+")))
            (t/is (= :load (:override-state (first-item-row screen))))
           ;; cycle back to inherit — the delta entry disappears
            (protocols/handle-input screen " ")
            (t/is (empty? (project-file-packages ctx)))
            (t/is (= :inherit (:override-state (first-item-row screen)))))))
      {:user {:packages [dir]}})))

(t/deftest test-screen-project-scope-package-tri-state
  (let [dir (make-package (tmp-dir))]
    (with-settings
      (fn [ctx]
        (let [screen (rc/make-resource-config-screen :rows 40
                                                     :write-scope :project
                                                     :project-mode? true)]
          (t/is (every? #(not (:inherited? %)) (item-rows screen)))
         ;; first space: inherit → unload (writes -pattern, entry becomes an
         ;; object form)
          (protocols/handle-input screen " ")
          (let [entries (project-file-packages ctx)]
            (t/is (= 1 (count entries)))
            (t/is (map? (first entries)))
            (t/is (str/starts-with? (first (:extensions (first entries))) "-")))
          (t/is (= :unload (:override-state (first-item-row screen))))
         ;; second space: unload → load
          (protocols/handle-input screen " ")
          (let [entries (project-file-packages ctx)]
            (t/is (str/starts-with? (first (:extensions (first entries))) "+")))
          (t/is (= :load (:override-state (first-item-row screen))))
         ;; third space: load → inherit; the entry returns to its plain
         ;; source string
          (protocols/handle-input screen " ")
          (t/is (= :inherit (:override-state (first-item-row screen))))
          (let [entries (project-file-packages ctx)]
            (t/is (= 1 (count entries)))
            (t/is (not (map? (first entries)))))))
      {:project {:packages [dir]}})))

(t/deftest test-screen-single-extension-not-toggleable
  ;; a file source (single extension) ignores per-type filters: the row is
  ;; marked always loaded and space writes nothing
  (let [dir (tmp-dir)
        f (str dir "/ext.clj")]
    (spit f "(ns ext)\n")
    (with-settings
      (fn [ctx]
        (let [screen (rc/make-resource-config-screen :rows 40)
              item (:item (first-item-row screen))]
          (t/is (pkgs/single-extension-item? item))
          (t/is (some #(str/includes? % "always loaded") (render-lines screen 100)))
          (protocols/handle-input screen " ")
          (t/is (true? (:enabled (first-item-row screen))))
          (t/is (= [f] (user-file-packages ctx)))))
      {:user {:packages [f]}})))

;; ─── Search and scope switching ───────────────────────────────────────────

(t/deftest test-screen-query-filter
  (let [dir (make-package (tmp-dir))]
    (with-settings
      (fn [_]
        (let [screen (rc/make-resource-config-screen :rows 40)]
          (rc/screen-set-query! screen "two")
          (let [items (item-rows screen)]
            (t/is (= 1 (count items)))
            (t/is (str/includes? (:path (:item (first items))) "two.clj")))))
      {:user {:packages [dir]}})))

(t/deftest test-screen-tab-switches-scope
  (let [dir (make-package (tmp-dir))]
    (with-settings
      (fn [_]
        (let [screen (rc/make-resource-config-screen :rows 40 :project-mode? true)]
          (t/is (= :global (rc/screen-write-scope screen)))
          (protocols/handle-input screen K-TAB)
          (t/is (= :project (rc/screen-write-scope screen)))
          (protocols/handle-input screen K-TAB)
          (t/is (= :global (rc/screen-write-scope screen)))))
      {:user {:packages [dir]}})))

(t/deftest test-screen-rows-interleave
  ;; each package group is followed by its own subgroups and items (pi
  ;; buildFlatList) — not all group/subgroup headers first
  (let [a (str (fs/path (tmp-dir) "pkgA"))
        b (str (fs/path (tmp-dir) "pkgB"))]
    (doseq [root [a b]]
      (fs/create-dirs (str root "/extensions"))
      (spit (str root "/extensions/one.clj") "(ns x)\n"))
    (with-settings
      (fn [_]
        (let [screen (rc/make-resource-config-screen :rows 40)]
          (t/is (= [:group :subgroup :item :group :subgroup :item]
                   (mapv :kind (rc/screen-rows screen))))))
      {:user {:packages [a b]}})))

(t/deftest test-screen-paging
  ;; page up/down jump maxVisible item rows (pi pageUp/pageDown): the
  ;; target row is scanned inclusively and headers are skipped
  (let [dir (str (fs/path (tmp-dir) "pkg"))]
    (fs/create-dirs (str dir "/extensions"))
    (doseq [i (range 10)]
      (spit (str dir "/extensions/e" i ".clj") "(ns x)\n"))
    (with-settings
      (fn [_]
        (let [screen (rc/make-resource-config-screen :rows 16)]
         ;; 12 rows: group, subgroup, 10 items; maxVisible = 16 - 11 = 5
          (t/is (= 2 (rc/screen-selected screen)))
          (protocols/handle-input screen K-PGUP)
          (t/is (= 2 (rc/screen-selected screen)) "page-up at the first item keeps it")
          (protocols/handle-input screen K-PGDN)
          (t/is (= 7 (rc/screen-selected screen)))
          (protocols/handle-input screen K-PGDN)
          (t/is (= 11 (rc/screen-selected screen)))
          (protocols/handle-input screen K-PGUP)
          (t/is (= 6 (rc/screen-selected screen)))))
      {:user {:packages [dir]}})))
