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
            [cljs.core.specs.alpha :as cljs-spec]
            [cljs.tagged-literals :as tags]
            [clojure.tools.namespace.find :as ctnf]
            [clojure.spec.alpha :as s]
            [clindex.specs]))


(def mvn-repos {"central" {:url "https://repo1.maven.org/maven2/"}
                "clojars" {:url "https://repo.clojars.org/"}})

(def ^:dynamic *def-public-set* #{'def 'defn 'declare 'defmulti 'deftype 'defprotocol 'defrecod})
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

(defn- find-project-in-dir [base-dir]
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

(s/fdef all-projects
  :args (s/cat :base-dir :file/path
               :opts (s/keys :req-un [:scanner/platform]))
  :ret :scanner/projects)

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

(defn- build-file->project-map [all-projs]
  (reduce (fn [files-map {:keys [:project/name :project/files]}]
            (reduce (fn [fm f]
                      (assoc fm (or (:jar f) (:full-path f)) name))
             files-map
             files))
   {}
   (vals all-projs)))


;;;;;;;;;;;;;;;;;;;;;;;;;
;; Namespaces scanning ;;
;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- read-namespace-forms [full-path alias-map readers read-opts]
  (binding [reader/*data-readers* (merge tags/*cljs-data-readers* readers)
            ;; this is relaying on a implementation detail, tools.reader/read calls
            ;; *alias-map* as a fn, so we can resolve to a dummy ns and allow reader to continue
            reader/*alias-map* (fn [alias]
                                 (if-let [ns (get alias-map alias)]
                                   ns
                                   (do
                                     (println "[Warning] couldn't resolve alias, resolving to dummy.ns"
                                             {:path full-path
                                              :alias alias})
                                     'dummy.ns)))
            reader/*read-eval* false
            ;; read everythig as we are on the user namespace, this is only important when runnning from repl
            ;; since if repl is on a different namespace, some symbols will be read with current namespace
            ;; and some tests can fail
            *ns* (the-ns 'user)]
    (try
      (let [file-str (slurp full-path)]
        (when-let [forms (->> (reader-types/indexing-push-back-reader (str "[" file-str "]"))
                              (reader/read read-opts)
                              (keep (fn [form]
                                      (when (and form (not= (first form) 'comment))
                                        (let [{:keys [line column end-line end-column]} (meta form)
                                              form-str (utils/rectangle-select file-str line end-line column)]
                                          {:form-list form
                                           :form-str form-str})))))]
          forms))
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

(defn- public-vars [ns-forms]
  (into #{} (keep form-public-var ns-forms)))

(defn- form-macro
  "If form defines a macro, returns the macro name, nil otherwise"
  [[symb vname]]
  (when (*def-macro-set* symb)
    vname))

(defn- macros [ns-forms]
  (into #{} (keep form-macro ns-forms)))

(defn- form-private-var
  "If form defines a private var, returns the var name, nil otherwise"
  [[symb vname]]
  (when (or (*def-private-set* symb)
            (and (*def-public-set* symb)
                 (:private (meta symb))))
    vname))

(defn- private-vars [ns-forms]
  (into #{} (keep form-private-var ns-forms)))

(defn- aliases-from-ns-decl [ns-form platform]
  ;; clojure ns-form spec doesn't work for cljs ns declarations
  (let [spec (cond
               (= platform ctnf/clj) :clojure.core.specs.alpha/ns-form
               (= platform ctnf/cljs) ::cljs-spec/ns-form)
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
                                (= platform ctnf/clj) lib
                                (= platform ctnf/cljs) (second lib))]))
                   (catch Exception e
                     (prn "[Warning] problem while building alias map for " {:ns-form ns-form
                                                                             :sub-part x})))))
         (into {}))))

(defn- aliases-from-alias-forms [file]
  ;; TODO implement this
  {})

(defn- data-readers [all-projs]
  ;; TODO implement this
  {})

(s/fdef all-namespaces
  :args (s/cat :all-projs :scanner/projects
               :opts (s/keys :req-un [:scanner/platform]))
  :ret :scanner/namespaces)

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
                      alias-map (merge (aliases-from-ns-decl ns-decl platform)
                                       (aliases-from-alias-forms file))
                      ns-forms (read-namespace-forms file-content-path alias-map readers (:read-opts platform))
                      ns-form-lists (map :form-list ns-forms)
                      pub-vars (public-vars ns-form-lists)
                      priv-vars (private-vars ns-form-lists)
                      ns-name (ns-parse/name-from-ns-decl ns-decl)]
                  ;; TODO probably around here we need to deal with the feature of cljs that lets you
                  ;; require clojure.set but that is kind of a alias to cljs.set
                  [ns-name {:namespace/name ns-name
                            :namespace/alias-map alias-map
                            :namespace/dependencies (conj (ns-parse/deps-from-ns-decl ns-decl) ;; implicitly requiered in clojure ns
                                                          (cond
                                                            (= platform ctnf/clj) 'clojure.core
                                                            (= platform ctnf/cljs) 'cljs.core))
                            :namespace/file-content-path file-content-path
                            :namespace/project (file->proj (or jar (.getAbsolutePath file)))
                            :namespace/forms ns-forms
                            :namespace/public-vars pub-vars
                            :namespace/private-vars priv-vars
                            :namespace/macros (macros ns-form-lists)}])))
         ;; need to combine since we can have the same namespace in different files like in
         ;; jar:file:/home/jmonetta/.m2/repository/org/clojure/clojurescript/1.10.439/clojurescript-1.10.439.jar!/cljs/core.cljc
         ;; jar:file:/home/jmonetta/.m2/repository/org/clojure/clojurescript/1.10.439/clojurescript-1.10.439.jar!/cljs/core.cljs
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
          {}))))

(s/fdef scan-all
  :args (s/cat :base-dir :file/path
               :opts (s/keys :req-un [:scanner/platform]))
  :ret (s/keys :req-un [:scanner/projects
                        :scanner/namespaces]))

(defn scan-all [base-dir opts]
  (let [projects (all-projects base-dir opts)]
    {:projects projects
     :namespaces (all-namespaces projects opts)}))

(comment

  (def all-projs (all-projects "/home/jmonetta/my-projects/clindex" {:platform ctnf/clj}))
  (def all-projs (all-projects "/home/jmonetta/my-projects/district0x/memefactory" {:platform ctnf/cljs}))

  (def all-ns (all-namespaces all-projs {:platform ctnf/clj #_ctnf/cljs}))

  (map #(meta %) (ns-find/find-ns-decls [(io/file "/home/jmonetta/my-projects/district0x/memefactory/src")]))

  )
