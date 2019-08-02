(ns clindex.scanner
  (:require [clojure.string :as str]
            [clindex.utils :as utils]
            [clojure.tools.deps.alpha.util.io :as tools-io]
            [clojure.tools.deps.alpha :as tools-dep]
            [clojure.tools.namespace.dependency :as dep]
            [clojure.java.io :as io]
            [clojure.tools.namespace.parse :as ns-parse]
            [clojure.tools.namespace.find :as ns-find]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types]
            [clojure.pprint :refer [pprint] :as pprint]))


(def mvn-repos {"central" {:url "https://repo1.maven.org/maven2/"}
                "clojars" {:url "https://repo.clojars.org/"}})

(def ^:dynamic *def-set* #{'def 'defn 'defn- 'declare 'defmulti 'deftype 'defprotocol 'defrecod})

;;;;;;;;;;;;;;;;;;;;;;;
;; Projects scanning ;;
;;;;;;;;;;;;;;;;;;;;;;;

(def main-project-symb 'clindex.core/main-project)


(defn- project-dependencies [project-symbol projects-map]
  (reduce-kv (fn [r dep-proy-symb {:keys [dependents]}]
               (if (contains? (into #{} dependents) project-symbol)
                 (conj r dep-proy-symb)
                 r))
             #{}
             projects-map))

(defn- find-project-in-dir [base-dir]
  (let [tool-deps (some-> (utils/all-files base-dir #(str/ends-with? (str %) "deps.edn"))
                          first
                          :full-path
                          tools-io/slurp-edn)
        lein-proj (some-> (utils/all-files base-dir #(str/ends-with? (str %) "project.clj"))
                          first
                          :full-path
                          slurp
                          read-string)
        {:keys [deps paths]} (or
                              ;; tools deps
                              tool-deps

                              ;; lein proj
                              (let [[_ _ _ & r] lein-proj
                                    {:keys [dependencies source-paths]} (->> (partition 2 r)
                                                                             (map vec)
                                                                             (into {}))]
                                {:deps (->> dependencies
                                            (map (fn [[p v]]
                                                   [p {:mvn/version v}]))
                                            (into {}))
                                 :paths source-paths}))]
    {:project/name main-project-symb
     :deps deps
     :project/dependencies (->> deps keys (into #{}))
     :paths (or paths ["src"])}))

(defn project-files
  "Retrieves all project files maps for a platform.
  A project shoudl be a map containing a collection of paths, could be dirs or jar files.
  Platform is tools.namespace clj or cljs platform."
  [{:keys [paths]} {:keys [platform]}]
  (let [extensions (-> platform :extensions)
        interested-in? (fn [full-path]
                         (some #(str/ends-with? full-path %) extensions))]
    (->> paths
        (mapcat (fn [p]
                  (if (str/ends-with? p ".jar")
                    (utils/jar-files p interested-in?)
                    (utils/all-files p interested-in?)))))))

(defn all-projects
  "Given a base dir retrieves all projects (including base one) and all its dependencies.
  Returns a map like {proj-symb {:project/dependencies #{}
                                 :project/name proj-symb
                                 :project/files #{}
                                 :paths []}}"
  [base-dir {:keys [platform] :as opts}]
  (let [proj (find-project-in-dir base-dir)
        all-projs (tools-dep/resolve-deps (assoc proj :mvn/repos mvn-repos) nil)]
    (->> (assoc all-projs main-project-symb (-> proj
                                                (assoc :main? true)
                                                (update :paths (fn [paths] (map #(str base-dir "/" %) paths)))))
         (map (fn [[p-symb p-map]]
                ;; TODO: fix this hack, :local/root deps include a path that contains
                ;; this unreplaced variable
                (let [p-map (update p-map :paths (fn [paths] (remove #(str/includes? % "${project.basedir}") paths)))]
                  [p-symb (assoc p-map
                                :project/dependencies (project-dependencies p-symb all-projs)
                                :project/name p-symb
                                :project/files (->> (project-files p-map opts)
                                                    (into #{})))])))
         (into {}))))

(defn topo-sort-projects-symbs
  "Given all-projects map like returned by all-projects returns a list of project name symbols
  in topological order."
  [all-projs]
  (dep/topo-sort
   (reduce
    (fn [dep-graph {:keys [:project/name :project/dependencies]}]
      (reduce (fn [dg pd]
                (dep/depend dg name pd))
              dep-graph
              dependencies))
    (dep/graph)
    (vals all-projs))))

(defn build-file->project-map [all-projs]
  (reduce (fn [files-map {:keys [:project/name :project/files]}]
            (reduce (fn [fm f]
                      (assoc fm (or (:jar f) (:full-path f)) name))
             files-map
             files))
   {}
   (vals all-projs)))

(comment
  (require '[clojure.tools.namespace.find :as ctnf])

  (def all-projs (all-projects "/home/jmonetta/my-projects/clindex"
                               {:platform ctnf/clj}))
  (def all-projs (all-projects "/home/jmonetta/my-projects/district0x/memefactory"
                               {:platform ctnf/cljs}))

  (def all-projs-topo (topo-sort-projects-symbs all-projs))
 )

;;;;;;;;;;;;;;;;;;;;;;;;;
;; Namespaces scanning ;;
;;;;;;;;;;;;;;;;;;;;;;;;;

(defn read-namespace-forms [full-path read-opts]
  (binding [;;reader/*data-readers* (merge tags/*cljs-data-readers* data-readers)
            ;;reader/*alias-map* requires
            ;;reader/*read-eval* false
            ;;*ns* name
            ]
    (try
      (when-let [forms (->> (reader-types/indexing-push-back-reader (str "[" (slurp full-path) "]"))
                            (reader/read read-opts)
                            (mapv #(with-meta % {:type :clindex/form})))]
        forms)
      (catch Exception e
        (println "Error when reading the file" full-path)
        (clojure.repl/pst e 1)))))

(defn- form-public-var
  "If form defines a public var, returns the var name, nil otherwise"
  [[symb vname]]
  (and (*def-set* symb) vname))

(defn public-vars [ns-forms]
  (keep form-public-var ns-forms))

(defn all-namespaces [all-projs {:keys [platform] :as opts}]
  (let [file->proj (build-file->project-map all-projs)
        all-paths (reduce (fn [r {:keys [:paths]}]
                            (into r paths))
                          #{}
                          (vals all-projs))
        all-ns-decl (->> (ns-find/find-ns-decls (map io/file all-paths))
                         (map (fn [ns-decl]
                                (let [jar (when-let [jf (-> ns-decl meta :jar-file)]
                                            (.getName jf))
                                      file (-> ns-decl meta :file io/file)
                                      file-content-path (if jar
                                                          (utils/jar-full-path jar (.getPath file))
                                                          file)
                                      ns-forms (read-namespace-forms file-content-path (:read-opts platform))
                                      pub-vars (public-vars ns-forms)]
                                  {:namespace/name (ns-parse/name-from-ns-decl ns-decl)
                                   :namespace/dependencies (ns-parse/deps-from-ns-decl ns-decl)
                                   :namespace/file-content-path file-content-path
                                   :namespace/project (file->proj (or jar file))
                                   :namespace/forms ns-forms
                                   :namespace/public-vars pub-vars}))))]
    all-ns-decl))
(comment

  (def all-ns (all-namespaces all-projs {:platform ctnf/clj}))


  )
