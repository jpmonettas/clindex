(ns clindex.scanner
  "Scanner, provides functionality for scanning complete projects for a platform.

  Starting from a deps.edn or project.clj project it can download all project dependencies,
  scan all source files inside local folders or jars and return a map of namespaces with tons
  of information about them. (See: :scanner/namespace spec)

  Its main api consists of :
  - `scan-all-projects`
  - `scan-namespace`
  - `scan-namespaces`
  "
  (:require [clojure.string :as str]
            [clindex.utils :as utils]
            [clojure.tools.deps.alpha.util.io :as tools-io]
            [clojure.tools.deps.alpha :as tools-dep]
            [clojure.tools.namespace.dependency :as dep]
            [clojure.java.io :as io]
            [clojure.tools.namespace.parse :as ns-parse]
            [clojure.tools.namespace.find :as ns-find]
            [clojure.tools.namespace.file :as ns-file]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types]
            [clojure.pprint :refer [pprint] :as pprint]
            [clojure.spec.alpha :as s]
            [cljs.core.specs.alpha :as cljs-spec]
            [cljs.tagged-literals :as tags]
            [clojure.spec.alpha :as s]
            [clindex.specs]
            [clojure.tools.deps.alpha.util.maven :as tool-deps-maven]))

(def ^:dynamic *def-public-set* #{'def 'defonce 'defn 'declare 'defmulti 'deftype 'defprotocol 'defrecod})
(def ^:dynamic *def-macro-set* #{'defmacro})
(def ^:dynamic *def-private-set* #{'defn-})

;;;;;;;;;;;;;;;;;;;;;;;
;; Projects scanning ;;
;;;;;;;;;;;;;;;;;;;;;;;

(def main-project-symb 'clindex/main-project)


(defn- project-dependencies [project-symbol projects-map]
  (reduce-kv (fn [r dep-proy-symb {:keys [dependents]}]
               (if (contains? (into #{} dependents) project-symbol)
                 (conj r dep-proy-symb)
                 r))
             #{}
             projects-map))

(s/fdef find-project-in-dir
  :args (s/cat :base-dir :file/path)
  :ret (s/keys :req [:project/name
                     :project/dependencies]
               :req-un [:project/paths]))

(defn find-project-in-dir
  "Given a `base-dir` path returns a project map with :project/name :project/dependencies and :paths (source paths)"
  [base-dir]
  (let [tool-deps (some->> (utils/all-files base-dir #(str/ends-with? (str %) "deps.edn"))
                           (sort-by (comp count :full-path)) ;; if there are more than one, that the shorter
                           first
                           :full-path
                           tools-io/slurp-edn)
        lein-proj (some-> (utils/all-files base-dir #(str/ends-with? (str %) "project.clj"))
                          first
                          :full-path
                          slurp
                          read-string)
        {:keys [deps paths :mvn/repos]} (or
                                         ;; tools deps
                                         tool-deps

                                         ;; lein proj
                                         (let [[_ _ _ & r] lein-proj
                                               {:keys [dependencies source-paths repositories]} (->> (partition 2 r)
                                                                                                     (map vec)
                                                                                                     (into {}))]
                                           {:deps (->> dependencies
                                                       (map (fn [[p v]]
                                                              [p {:mvn/version v}]))
                                                       (into {}))

                                            ;; https://github.com/technomancy/leiningen/blob/master/sample.project.clj#L104
                                            :mvn/repos (into {} repositories)
                                            :paths source-paths}))
        all-repos (merge repos tool-deps-maven/standard-repos)]
    (prn "[Info] Using maven repos " all-repos)
    {:project/name main-project-symb
     :deps deps
     :mvn/repos all-repos
     :project/dependencies (->> deps keys (into #{}))
     :paths (or paths ["src"])}))

(defn- project-files
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

(s/fdef scan-all-projects
  :args (s/cat :base-dir :file/path
               :opts (s/keys :req-un [:scanner/platform]))
  :ret :scanner/projects)

(defn scan-all-projects
  "Given a base dir retrieves all projects (including base one) and all its dependencies.
  Returns a map like {proj-symb {:project/dependencies #{}
                                 :project/name proj-symb
                                 :project/files #{}
                                 :paths []}}"
  [base-dir opts]
  (let [proj (find-project-in-dir base-dir)
        all-projs (tools-dep/resolve-deps proj nil)]
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

(def build-file->project-map
  (memoize
   (fn [all-projs]
     (reduce (fn [files-map {:keys [:project/name :project/files]}]
               (reduce (fn [fm f]
                         (assoc fm (or (:jar f) (:full-path f)) name))
                       files-map
                       files))
             {}
             (vals all-projs)))))


;;;;;;;;;;;;;;;;;;;;;;;;;
;; Namespaces scanning ;;
;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- read-namespace-forms [full-path ns-name alias-map readers read-opts]
  (create-ns ns-name)
  (binding [reader/*data-readers* (merge tags/*cljs-data-readers* readers)
            ;; this is relaying on a implementation detail, tools.reader/read calls
            ;; *alias-map* as a fn, so we can resolve to a dummy ns and allow reader to continue
            reader/*alias-map* (fn [alias]
                                 (if-let [ns (get alias-map alias)]
                                   ns
                                   (let [unresolved-ns (symbol "unresolved" (str alias))]
                                     (println "[Warning] couldn't resolve alias, resolving to " unresolved-ns
                                             {:path full-path
                                              :alias alias})
                                     unresolved-ns)))
            reader/*read-eval* false
            *ns* (find-ns ns-name)]
    (try
      (let [file-str (slurp full-path)
            file-lines (str/split-lines file-str)]
        (when-let [forms (->> (reader-types/indexing-push-back-reader (str "[" file-str "]"))
                              (reader/read read-opts)
                              (keep (fn build-form [form]
                                      (when (and (list? form) (not= (first form) 'comment))
                                        (let [{:keys [line column end-line end-column]} (meta form)
                                              form-str (if (and line column end-line)
                                                         (utils/rectangle-select file-lines line end-line column)
                                                         (do
                                                           (println "[Warning] no meta line info found for " form)
                                                           ""))]
                                          {:form-list form
                                           :form-str form-str}))))
                              doall)]
          forms))
      (catch Exception e
        (let [{:keys [line type]} (ex-data e)]
          (case type
            :reader-exception
            (do (prn "[Warning] found a problem when reading the file" full-path {:line line
                                                                                  :alias-map alias-map})
                (utils/print-file-lines-arround full-path line))

            (throw e)))))))

(defn- clojure-core-namespaced? [symb alias-map]
  (when-let [ns (namespace symb)]
    (or (= (symbol ns) 'clojure.core)
        (= (symbol ns) 'cljs.core)
        (= (alias-map (symbol ns)) 'clojure.core)
        (= (alias-map (symbol ns)) 'cljs.core))))

(defn- form-public-var
  "If form defines a public var, returns the var name, nil otherwise"
  [[symb vname] alias-map]
  (and (or (*def-public-set* symb)                      ;; it is a def, defn, etc
           (and (*def-public-set* (symbol (name symb))) ;; or it is core/def, clojure.core/def, etc
                (clojure-core-namespaced? symb alias-map)))
       (not (:private (meta symb)))
       vname))

(defn- public-vars [ns-forms alias-map]
  (->> ns-forms
       (mapcat (fn [form]
                 (when-let [symb (and (list? form) (first form))]
                   (when-let [pv (form-public-var form alias-map)]
                     (cond-> [pv]
                       ;; when defprotocol   ;; grab all proto fns names
                       (= symb 'defprotocol) (into (->> form
                                                        (filter list?)
                                                        (map first))))))))
       (remove nil?)
       (into #{})))

(defn- form-macro
  "If form defines a macro, returns the macro name, nil otherwise"
  [[symb vname] alias-map]
  (when (or (*def-macro-set* symb)
            (and (*def-macro-set* (symbol (name symb)))
                 (clojure-core-namespaced? symb alias-map)))
    vname))

(defn- macros [ns-forms alias-map]
  (into #{} (keep #(form-macro % alias-map) ns-forms)))

(defn- form-private-var
  "If form defines a private var, returns the var name, nil otherwise"
  [[symb vname] alias-map]
  (when (or (*def-private-set* symb)                                   ; is defn- , etc
            (and (*def-private-set* (symbol (name symb)))              ; OR core/defn- clojure.core/defn-
                 (clojure-core-namespaced? symb alias-map))
            (and (or (*def-public-set* symb)                           ; or def, core/def, etc but with ^{:private true}
                     (and (*def-public-set* (symbol (name symb)))
                          (clojure-core-namespaced? symb alias-map)))
                 (:private (meta symb))))
    vname))

(defn- private-vars [ns-forms alias-map]
  (into #{} (keep #(form-private-var % alias-map) ns-forms)))

(defn- aliases-from-ns-decl [ns-form platform]
  ;; clojure ns-form spec doesn't work for cljs ns declarations
  (let [spec (cond
               (= platform ns-find/clj) :clojure.core.specs.alpha/ns-form
               (= platform ns-find/cljs) ::cljs-spec/ns-form)
        ns-form-ast (s/conform spec (rest ns-form))
        requires (->> (:ns-clauses ns-form-ast)
                      (into {})
                      :require)]
    (->> (:body requires)
         (keep (fn [x]
                 (try
                   (let [[_libspec [_lib+opts {:keys [lib options]}]] x]
                     (when-let [alias (:as options)]
                       ;; ns-form specs doesn't conform to the same for cljs and clj :(
                       ;; so patching it here
                       [alias (cond
                                (= platform ns-find/clj) lib
                                (= platform ns-find/cljs) (second lib))]))
                   (catch Exception e
                     (prn "[Warning] problem while building alias map for " {:ns-form ns-form
                                                                             :sub-part x})))))
         (into {}))))

(defn- aliases-from-alias-forms [file]
  ;; TODO implement this
  {})

(def data-readers
  (memoize
   (fn [all-projs]
     ;; TODO implement this
     {})))

(defn- doc-from-ns-decl [ns-decl]
  (let [[_ _ possible-doc] ns-decl]
    (when (string? possible-doc)
      possible-doc)))

(defn- scan-namespace-decl [ns-decl all-projs platform]
  (try
    (let [file->proj (build-file->project-map all-projs)
          readers (data-readers all-projs)
          jar (when-let [jf (-> ns-decl meta :jar-file)]
                (.getName jf))
          file (-> ns-decl meta :file io/file)
          ns-name (ns-parse/name-from-ns-decl ns-decl)]
      (if-not (or file jar)

        (prn (format "[Warning] No file content path in namespace %s declaration meta" ))

        (let [file-content-path (if jar
                                  (utils/jar-full-path jar (.getPath file))
                                  (utils/normalize-path file))
              alias-map (merge (aliases-from-ns-decl ns-decl platform)
                               (aliases-from-alias-forms file))

              ns-forms (read-namespace-forms file-content-path ns-name alias-map readers (:read-opts platform))
              ns-form-lists (map :form-list ns-forms)
              pub-vars (public-vars ns-form-lists alias-map)
              priv-vars (private-vars ns-form-lists alias-map)
              macros (macros ns-form-lists alias-map)
              ns-doc (doc-from-ns-decl ns-decl)]
          ;; TODO probably around here we need to deal with the feature of cljs that lets you
          ;; require clojure.set but that is kind of a alias to cljs.set
          [ns-name {:namespace/name ns-name
                    :namespace/docstring ns-doc
                    :namespace/alias-map alias-map
                    :namespace/dependencies (let [deps (ns-parse/deps-from-ns-decl ns-decl)]
                                              (if-not (#{'clojure.core 'cljs.core} ns-name)
                                                (conj deps
                                                      (cond ;; add c.core as a dependency if it is not itself
                                                        (= platform ns-find/clj) 'clojure.core
                                                        (= platform ns-find/cljs) 'cljs.core))
                                                deps))
                    :namespace/file-content-path file-content-path
                    :namespace/project (file->proj (or jar (utils/normalize-path file)))
                    :namespace/forms ns-forms
                    :namespace/public-vars pub-vars
                    :namespace/private-vars priv-vars
                    :namespace/macros macros}])))
    (catch Exception e
      (prn "[Warning] couldn't scan namespace for ns-decl " ns-decl))))

(defn- merge-namespaces
  "Given a list of [ns-name ns-map] returns a map like {ns-name ns-map}
  with same namespaces correctly merged."
  [all-ns]
  (reduce
   (fn [r [ns-name ns-map]]
     (update r ns-name (fn [m]
                         (if m
                           (-> m
                               (update :namespace/alias-map merge (:namespace/alias-map ns-map))
                               (update :namespace/dependencies (fnil into #{}) (:namespace/dependencies ns-map))
                               (update :namespace/forms (fnil into #{}) (:namespace/forms ns-map))
                               (update :namespace/public-vars (fnil into #{}) (:namespace/public-vars ns-map))
                               (update :namespace/private-vars (fnil into #{}) (:namespace/private-vars ns-map))
                               (update :namespace/macros (fnil into #{}) (:namespace/macros ns-map)))
                           ns-map))))
   {}
   all-ns))

(s/fdef scan-namespace
  :args (s/cat :ns-file-path string?
               :all-projs :scanner/projects
               :opts (s/keys :req-un [:scanner/platform]))
  :ret :scanner/namespace)

(defn scan-namespace [ns-file-path all-projs {:keys [platform] :as opts}]
  (let [ns-file (io/file ns-file-path)
        [_ ns-map] (scan-namespace-decl (with-meta (ns-file/read-file-ns-decl ns-file platform) {:file (.getAbsolutePath ns-file)})
                                        all-projs
                                        platform)]
    ns-map))

(s/fdef scan-namespaces
  :args (s/cat :all-projs :scanner/projects
               :opts (s/keys :req-un [:scanner/platform]))
  :ret :scanner/namespaces)

(defn scan-namespaces [all-projs {:keys [platform] :as opts}]
  (let [all-paths (->> (vals all-projs)
                       (reduce (fn [r {:keys [:paths]}]
                                 (into r paths))
                               #{})
                       (map io/file))]

    (->> (ns-find/find-ns-decls all-paths platform)
         (pmap (fn [ns-decl] (scan-namespace-decl ns-decl all-projs platform)))
         ;; need to merge namespaces since we can have the same namespace in different files like in
         ;; jar:file:/home/jmonetta/.m2/repository/org/clojure/clojurescript/1.10.439/clojurescript-1.10.439.jar!/cljs/core.cljc
         ;; jar:file:/home/jmonetta/.m2/repository/org/clojure/clojurescript/1.10.439/clojurescript-1.10.439.jar!/cljs/core.cljs
         (merge-namespaces))))

(s/fdef scan-all
  :args (s/cat :base-dir :file/path
               :opts (s/keys :req-un [:scanner/platform]))
  :ret (s/keys :req-un [:scanner/projects
                        :scanner/namespaces]))

(defn scan-all [base-dir opts]
  (let [projects (scan-all-projects base-dir opts)]
    {:projects projects
     :namespaces (scan-namespaces projects opts)}))

(comment

  (def all-projs (scan-all-projects "/home/jmonetta/my-projects/clindex" {:platform ns-find/clj}))
  (def all-projs (scan-all-projects "/home/jmonetta/my-projects/district0x/memefactory" {:platform ns-find/cljs}))

  (def test-ns (scan-namespace "/home/jmonetta/my-projects/clindex/src/clindex/indexer.clj" all-projs {:platform ns-find/clj #_ns-find/cljs}))
  (def all-ns (scan-namespaces all-projs {:platform ns-find/clj #_ns-find/cljs}))

  (map #(meta %) (ns-find/find-ns-decls [(io/file "/home/jmonetta/my-projects/district0x/memefactory/src")]))


  ;; Performance test
  (require '[clj-async-profiler.core :as prof])
  (prof/serve-files 9090)
  (prof/profile
   (time
    (let [all-projs (scan-all-projects "/home/jmonetta/my-projects/clindex/test-resources/test-project" {:platform ns-find/clj})
          all-ns (scan-namespaces all-projs {:platform ns-find/clj})]
      (prn (count all-ns)))))
  )
