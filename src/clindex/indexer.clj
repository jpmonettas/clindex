(ns clindex.indexer
  "Indexer, provides functionality to collects facts about projects and namespaces.

  Collects facts about :
  - projects (name, dependencies, version, namespaces)
  - files (name)
  - namespaces (name, file, vars, docstring)
  - vars (name, line, public?, refs)
  - and a bunch more by extending `clindex.forms-facts.core/form-facts`
  Its main api consists of :
  - `namespace-full-facts`
  - `all-facts`
  "
  (:require [datascript.core :as d]
            [clojure.zip :as zip]
            [clojure.string :as str]
            [clindex.utils :as utils]
            [clindex.forms-facts.core :refer [form-facts]]
            [clojure.tools.namespace.track :as ns-track]
            [clojure.walk :as walk]
            [clojure.spec.alpha :as s]
            [clindex.specs]
            [clindex.scanner :as scanner]
            [clindex.resolve-utils :as resolve-utils]))

(defn- project-facts [{:keys [:project/name :project/dependencies :mvn/version] :as proj}]
  (let [proj-id (utils/project-id name)]
    (cond->> [[:db/add proj-id :project/name name]]
      version      (into [[:db/add proj-id :project/version version]])
      dependencies (into (mapv (fn [dep-symb]
                                 [:db/add proj-id :project/depends (utils/project-id dep-symb)])
                               dependencies)))))

(defn- files-facts [{:keys [:project/files] :as proj} {:keys [index-file? file-facts]}]
  (->> files
       (map (fn [file]
              (let [file-id (utils/file-id (:full-path file))
                    file-path (:full-path file)
                    proj-id (utils/project-id (:project/name proj))]
                [file-id (cond->> [[:db/add proj-id :project/files file-id]
                                   [:db/add file-id :file/name file-path]]
                           (and index-file? (index-file? {:file-path file-path})) (into (file-facts {:project-id proj-id
                                                                                                     :file-id file-id
                                                                                                     :file-path file-path})))])))
       (into {})))

(defn- namespace-facts [ns]
  (let [ns-id (utils/namespace-id (:namespace/name ns))
        ns-doc (:namespace/docstring ns)
        vars-facts (fn vars-facts [vs pub?]
                     (mapcat (fn [v]
                               (let [vid (utils/var-id (:namespace/name ns) v)
                                     {:keys [line column end-line end-column]} (meta v)]
                                 (when (nil? line)
                                   (println (format "[Warning], no line meta for %s/%s" (:namespace/name ns) v)))
                                 (cond-> [[:db/add vid :var/name v]
                                          [:db/add vid :var/public? pub?]
                                          [:db/add vid :var/namespace ns-id]
                                          [:db/add ns-id :namespace/vars vid]]
                                   line (into [[:db/add vid :var/line       line]
                                               [:db/add vid :var/column     column]
                                               [:db/add vid :var/end-column end-column]]))))
                             vs))
        ns-dependencies-facts (map (fn [ns-dep-symb]
                                     [:db/add ns-id :namespace/depends (utils/namespace-id ns-dep-symb)])
                                   (:namespace/dependencies ns))
        facts (cond-> (-> [[:db/add ns-id :namespace/name (:namespace/name ns)]
                           [:db/add (utils/project-id (:namespace/project ns)) :project/namespaces ns-id]
                           [:db/add ns-id :namespace/file (utils/file-id (:namespace/file-content-path ns))]]
                          (into (vars-facts (:namespace/public-vars ns) true))
                          (into (vars-facts (:namespace/private-vars ns) false))
                          (into (vars-facts (:namespace/macros ns) true))
                          (into ns-dependencies-facts))
                ns-doc (into [[:db/add ns-id :namespace/docstring ns-doc]]))]
    facts))



(defn- deep-form-facts [all-ns-map ns-symb form]
  (let [is-var (resolve-utils/all-vars all-ns-map)]
    (loop [zloc (utils/code-zipper form)
           facts []
           ctx {:namespace/name ns-symb}]
      (if (zip/end? zloc)
        facts
        (let [token (zip/node zloc)]

          (cond
            ;; we are deep looking at a form
            ;; lets collect this form facts
            (list? token)
            (let [form' (resolve-utils/fully-qualify-form-first-symb all-ns-map ns-symb token)
                  {ffacts :facts fctx :ctx} (try
                                              (form-facts all-ns-map ctx form')
                                              (catch Exception e
                                                (prn "[Error] found when extracting form-facts for " form')
                                                {:facts [] :ctx ctx}))]
              (recur (utils/move-zipper-to-next zloc #(or (list? %) (symbol? %)))
                     (into facts ffacts)
                     (merge ctx fctx)))

            ;; we are deep looking at a symbol
            (symbol? token)
            (recur (utils/move-zipper-to-next zloc #(or (list? %) (symbol? %)))
                   (let [fq-symb (resolve-utils/fully-qualify-symb all-ns-map ns-symb token)
                         var (resolve-utils/split-symb-namespace fq-symb)]
                     (if (and (is-var var)
                              (not (:in-protocol ctx)))
                       (let [[var-ns var-symb] var
                             {:keys [line column end-column]} (meta (zip/node zloc))
                             vr-id (utils/var-ref-id var-ns var-symb ns-symb line column)]
                         (into facts (cond-> [[:db/add (utils/var-id var-ns var-symb) :var/refs vr-id]
                                              [:db/add vr-id :var-ref/namespace (utils/namespace-id ns-symb)]
                                              [:db/add vr-id :var-ref/in-function (utils/function-id ns-symb (:in-function ctx))]]
                                       line (into [[:db/add vr-id :var-ref/line line]
                                                   [:db/add vr-id :var-ref/column column]
                                                   [:db/add vr-id :var-ref/end-column end-column]]))))
                       facts))
                   ctx)

            :else
            (recur (utils/move-zipper-to-next zloc #(or (list? %) (symbol? %)))
                   facts
                   ctx)))))))

(defn- enhance-form-list [form-list form-str all-ns-map ns-symb]
  (let [form-list' (walk/postwalk
                    (fn [x]
                      (if (symbol? x)
                        (if-let [fqs (resolve-utils/fully-qualify-symb all-ns-map ns-symb x)]
                          (let [ns (namespace fqs)]
                            (vary-meta x merge (when (and ns x)
                                                 {:var/id (utils/var-id (symbol ns) (symbol (name x)))})))
                          x)
                        x))
                    form-list)]
    (vary-meta form-list' merge (meta form-list) {:form-str form-str})))

(defn- namespace-forms-facts [all-ns-map ns-symb]
  (println "indexing " ns-symb)
  (->> (:namespace/forms (get all-ns-map ns-symb))
       (map (fn enhance [{:keys [form-str form-list]}]
              (enhance-form-list form-list form-str all-ns-map ns-symb)))
       (pmap (partial deep-form-facts all-ns-map ns-symb))
       (apply concat)))


(s/fdef namespace-full-facts
  :args (s/cat :all-ns-map :scanner/namespaces
               :ns-symb :namespace/name)
  :ret (s/coll-of :datomic/fact))

(defn namespace-full-facts [all-ns-map ns-symb]
  (into (namespace-facts (get all-ns-map ns-symb))
        (namespace-forms-facts all-ns-map ns-symb)))



(s/def ::facts-by-file (s/map-of :datomic/id (s/coll-of :datomic/fact)))
(s/def ::all-projects-facts (s/coll-of :datomic/fact))
(s/def ::all-source-facts (s/coll-of :datomic/fact))

(s/fdef all-facts
  :args (s/cat :m (s/keys :req-un [:scanner/projects
                                   :scanner/namespaces])
               :opts (s/keys :opt-un [:scanner/extra-files]))
  :ret (s/keys :req-un [::facts-by-file
                        ::all-projects-facts
                        ::all-source-facts]))

(defn all-facts [{:keys [projects namespaces]} {:keys [extra-files]}]
  (let [all-projs-facts (mapcat project-facts (vals projects))
        facts-by-files (->> (vals projects)
                            (map #(files-facts % extra-files))
                            (reduce merge {}))
        all-source-facts (->> namespaces
                              (map (fn [[ns-symb _]] (namespace-full-facts namespaces ns-symb)))
                              (apply concat))]
    {:facts-by-file facts-by-files
     :all-projects-facts all-projs-facts
     :all-source-facts all-source-facts}))

(comment

  (do (require '[clindex.scanner :as scanner])
      (require '[clojure.tools.namespace.find :as ns-find])

      (def all-projs (scanner/all-projects "/home/jmonetta/my-projects/clindex"
                                           {:platform ns-find/clj}))

      (def main-project {scanner/main-project-symb (get all-projs scanner/main-project-symb)})
      (def all-ns (scanner/all-namespaces
                   all-projs #_main-project
                   {:platform ns-find/clj #_ns-find/cljs})))

  (do (require '[clindex.scanner :as scanner])
      (require '[clojure.tools.namespace.find :as ns-find])

      (def all-projs (scanner/all-projects "/home/jmonetta/my-projects/district0x/memefactory"
                                           {:platform ns-find/cljs}))

      (def all-ns (scanner/all-namespaces all-projs {:platform ns-find/cljs #_ns-find/cljs})))

  (def src-facts (source-facts all-ns))

    ;; Performance test
  (require '[clj-async-profiler.core :as prof])
  (prof/serve-files 9090)

  (def all-projs (scanner/scan-all-projects "/home/jmonetta/my-projects/clindex/test-resources/test-project" {:platform ns-find/clj}))
  (def all-ns (scanner/scan-namespaces all-projs {:platform ns-find/clj}))

  (prof/profile
   (time
    (def facts(all-facts {:projects all-projs :namespaces all-ns} {}))))


  )
