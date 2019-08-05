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
            [clojure.pprint :refer [pprint] :as pprint]
            [clojure.spec.alpha :as s]
            [cljs.tagged-literals :as tags]))


(def mvn-repos {"central" {:url "https://repo1.maven.org/maven2/"}
                "clojars" {:url "https://repo.clojars.org/"}})

(def ^:dynamic *def-public-set* #{'def 'defn 'declare 'defmulti 'deftype 'defprotocol 'defrecod})
(def ^:dynamic *def-private-set* #{'defn-})

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
                                 :project/dependencies (or (:project/dependencies p-map)
                                                           (project-dependencies p-symb all-projs))
                                :project/name p-symb
                                :project/files (->> (project-files p-map opts)
                                                    (into #{})))])))
         (into {}))))

(defn topo-sort
  "Given a list of things, a name-fn and deps-fn representing a graph (where deps are pointers to other names)
  returns a list of names in topological order."
  [xs name-fn deps-fn]
  (dep/topo-sort
   (reduce
    (fn [dep-graph x]
      (let [name (name-fn x)
            deps (deps-fn x)]
        (reduce (fn [dg pd]
                  (dep/depend dg name pd))
                dep-graph
                deps)))
    (dep/graph)
    xs)))

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

  (def all-projs-topo (topo-sort (vals all-projs)
                                 :project/name
                                 :project/dependencies))
 )

;;;;;;;;;;;;;;;;;;;;;;;;;
;; Namespaces scanning ;;
;;;;;;;;;;;;;;;;;;;;;;;;;

(defn read-namespace-forms [full-path alias-map readers read-opts]
  (binding [reader/*data-readers* (merge tags/*cljs-data-readers* readers)
            reader/*alias-map* alias-map
            reader/*read-eval* false]
    (try
      (when-let [forms (->> (reader-types/indexing-push-back-reader (str "[" (slurp full-path) "]"))
                            (reader/read read-opts)
                            (keep (fn [form]
                                    (when form
                                      (with-meta form {:type :clindex/form})))))]
        forms)
      (catch Exception e
        (let [{:keys [line type]} (ex-data e)]
          (case type
            :reader-exception
            (do (prn "[Warning] found a problem when reading the file" full-path {:line line
                                                                                  :alias-map alias-map})
                (utils/print-file-lines-arround full-path line))

            (throw e)))))))

(defn- form-public-var
  "If form defines a public var, returns the var name, nil otherwise"
  [[symb vname]]
  (and (*def-public-set* symb)
       (not (:private (meta symb)))
       vname))

(defn public-vars [ns-forms]
  (keep form-public-var ns-forms))

(defn- form-private-var
  "If form defines a private var, returns the var name, nil otherwise"
  [[symb vname]]
  (when (or (*def-private-set* symb)
            (and (*def-public-set* symb)
                 (:private (meta symb))))
    vname))

(defn private-vars [ns-forms]
  (keep form-private-var ns-forms))

(defn aliases-from-ns-decl [ns-form]
  (let [ns-form-ast (s/conform :clojure.core.specs.alpha/ns-form (rest ns-form))
        requires (->> (:ns-clauses ns-form-ast)
                      (into {})
                      :require)]
    (->> (:body requires)
         (keep (fn [x]
                 (try
                   (let [[_libspec [_lib+opts {:keys [lib options]}]] x]
                    (when-let [alias (:as options)]
                      [alias lib]))
                   (catch Exception e
                     (prn "[Warning] problem while building alias map for " {:ns-form ns-form
                                                                             :sub-part x})))))
         (into {}))))

(defn data-readers [all-projs]
  {})

(defn all-namespaces [all-projs {:keys [platform] :as opts}]
  (let [readers (data-readers all-projs)
        file->proj (build-file->project-map all-projs)
        all-paths (reduce (fn [r {:keys [:paths]}]
                            (into r paths))
                          #{}
                          (vals all-projs))]
    (->> (ns-find/find-ns-decls (map io/file all-paths) platform)
         (map (fn [ns-decl]
                (let [jar (when-let [jf (-> ns-decl meta :jar-file)]
                            (.getName jf))
                      file (-> ns-decl meta :file io/file)
                      file-content-path (if jar
                                          (utils/jar-full-path jar (.getPath file))
                                          file)
                      alias-map (aliases-from-ns-decl ns-decl)
                      ns-forms (read-namespace-forms file-content-path alias-map readers (:read-opts platform))
                      pub-vars (public-vars ns-forms)
                      priv-vars (private-vars ns-forms)
                      ns-name (ns-parse/name-from-ns-decl ns-decl)]
                  [ns-name {:namespace/name ns-name
                            :namespace/alias-map alias-map
                            :namespace/dependencies (ns-parse/deps-from-ns-decl ns-decl)
                            :namespace/file-content-path file-content-path
                            :namespace/project (file->proj (or jar (.getAbsolutePath file)))
                            :namespace/forms ns-forms
                            :namespace/public-vars pub-vars
                            :namespace/private-vars priv-vars}])))
         (into {}))))

(comment

  (def all-ns (all-namespaces all-projs {:platform #_ctnf/clj ctnf/cljs}))

  (def all-ns-topo (topo-sort (vals all-ns)
                              :namespace/name
                              :namespace/dependencies))

  (map #(meta %) (ns-find/find-ns-decls [(io/file "/home/jmonetta/my-projects/district0x/memefactory/src")]))

  )
