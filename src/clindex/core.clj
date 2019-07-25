(ns clindex.core
  (:require [cljs.analyzer.api :as cljs-ana]
            [clojure.tools.namespace.parse :as tools-ns-parse]
            [clojure.tools.deps.alpha :as tools-dep]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types]
            [cljs.tagged-literals :as tags]
            [clojure.string :as str]
            [clojure.tools.deps.alpha.util.io :as tools-io]
            [clindex.utils :as utils]
            [clojure.tools.reader.edn :as edn-reader]
            [datascript.core :as d])
  (:gen-class))

(def db-conn (d/create-conn {:project/name      {:db/cardinality :db.cardinality/one}
                             :project/depends   {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
                             :file/name         {:db/cardinality :db.cardinality/one}
                             :namespace/name    {:db/cardinality :db.cardinality/one}
                             :namespace/project {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
                             :namespace/file    {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
                             :var/name          {:db/cardinality :db.cardinality/one}
                             :var/line          {:db/cardinality :db.cardinality/one}
                             :var/namespace     {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}}))

(defprotocol Project
  (project-name [_])
  (project-paths [_])
  (project-version [_])
  (dependencies [_]))

(extend-protocol Project
  clojure.lang.PersistentArrayMap
  (project-name [m] (:project/name m))
  (project-paths [m] (:paths m))
  (project-version [m] (:mvn/version m))
  (dependencies [m] (:project/dependencies m)))

(def mvn-repos {"central" {:url "https://repo1.maven.org/maven2/"}
                "clojars" {:url "https://repo.clojars.org/"}})

(def main-project-symb 'clindex.core/main-project)

(defn project-dependencies [project-symbol projects-map]
  (reduce-kv (fn [r dep-proy-symb {:keys [dependents]}]
               (if (contains? (into #{} dependents) project-symbol)
                 (conj r dep-proy-symb)
                 r))
             #{}
             projects-map))

(defn all-projects [proj]
  (let [all-projs (tools-dep/resolve-deps (assoc proj :mvn/repos mvn-repos) nil)]
    (->> all-projs
         (map (fn [[p m]]
                [p (assoc m
                          :project/dependencies (project-dependencies p all-projs)
                          :project/name p)]))
         (into {main-project-symb (assoc proj :main? true)}))))

(defn project [base-dir]
  (let [{:keys [deps paths]} (-> (utils/all-files base-dir #(str/ends-with? (str %) "deps.edn"))
                                 first
                                 :full-path
                                 tools-io/slurp-edn)]
    {:project/name main-project-symb
     :deps deps
     :project/dependencies (->> deps keys (into #{}))
     :paths (or paths ["src"])}))

(defn ns-form [url]
  (-> url
      slurp
      java.io.StringReader.
      java.io.PushbackReader.
      tools-ns-parse/read-ns-decl))

(defn normalize-ns-form [ns-form]
  (remove #(= % '(:gen-class)) ns-form))

(defn parse-file [{:keys [full-path content-url]}]
  (try
    (let [{:keys [requires name] :as ast} (:ast (->> (ns-form content-url)
                                                     normalize-ns-form
                                                     pr-str
                                                     java.io.StringReader.
                                                     cljs-ana/parse-ns))]
      (binding [reader/*data-readers* tags/*cljs-data-readers*
                reader/*alias-map* requires
                clojure.core/*read-eval* false
                ;;*ns* name
                ]
        {:ns-name name
         :absolute-path full-path
         :alias-map reader/*alias-map*
         :source-forms (reader/read {:read-cond :allow}
                        (reader-types/indexing-push-back-reader (str "[" (slurp content-url) "]")))}))
    (catch Exception e
      (println (format "ERROR while reading file %s %s" full-path (ex-message e)))
      #_(.printStackTrace e)
      nil)))

(defn analyze-source-file? [full-path]
  (or (str/ends-with? full-path ".clj")
      (str/ends-with? full-path ".cljc")))

(defn project-forms [project]
  (->> (project-paths project)
       (mapcat (fn [p]
                 (if (str/ends-with? p ".jar")
                   (utils/jar-files p analyze-source-file?)
                   (utils/all-files p analyze-source-file?))))
       (mapcat (fn [src-file]
                 (let [{:keys [ns-name alias-map source-forms absolute-path]} (parse-file src-file)
                       ns {:namespace/name ns-name
                           :namespace/requires alias-map
                           :namespace/file absolute-path
                           :project/name (project-name project)}]
                   (map (fn [form]
                          (with-meta
                            {:ns ns
                             :form form}
                            {:type :clindex/form}))
                        source-forms))))))

(def def-set #{'def 'defn 'defn- 'defmulti 'deftype 'defprotocol 'defrecod})

(defn next-temp-id [temp-ids]
  (let [ids (->> (vals temp-ids)
                 (reduce merge {})
                 vals)]
    (if (empty? ids)
      -1
      (dec (apply min ids)))))

(defn get-project-temp-id [temp-ids proj-symb]
  (get-in temp-ids [:projects proj-symb]))

(defn all-projects-facts [{:keys [facts temp-ids]} all-projects-map]
  (let [projects-set (into #{} (keys all-projects-map))
        project-temp-ids (zipmap projects-set (iterate dec (next-temp-id temp-ids)))
        temp-ids' (assoc temp-ids :projects project-temp-ids)
        tx-data (reduce (fn [r proj]
                          (let [pid (get project-temp-ids (project-name proj))
                                pdeps (dependencies proj)
                                pdeps-ids (map #(get project-temp-ids %) pdeps)]
                            (->> r
                                 (into [[:db/add  pid :project/name (project-name proj)]])
                                 (into (mapv (fn [pdid]
                                               [:db/add pid :project/depends pdid])
                                             pdeps-ids)))))
                        []
                        (vals all-projects-map))]
    {:facts (into facts tx-data)
     :temp-ids temp-ids'}))

(defn get-namespace-temp-id [temp-ids ns-symb]
  (get-in temp-ids [:namespaces ns-symb]))


(defn all-namespaces-facts [{:keys [facts temp-ids]} forms]
  (let [namespaces-set (reduce (fn [r {:keys [ns]}]
                                 (conj r ns))
                               #{}
                               forms)
        ns-temp-ids (zipmap (map :namespace/name namespaces-set) (iterate dec (next-temp-id temp-ids)))
        temp-ids' (assoc temp-ids :namespaces ns-temp-ids)
        files-temp-ids (zipmap (map :namespace/file namespaces-set) (iterate dec (next-temp-id temp-ids')))
        temp-ids'' (assoc temp-ids' :files files-temp-ids)
        tx-data (->> namespaces-set
                     (reduce (fn [r ns]
                               ;; TODO: add namespace requires facts
                               (let [file-temp-id (get files-temp-ids (:namespace/file ns))
                                     ns-temp-id (get ns-temp-ids (:namespace/name ns))
                                     prj-temp-id (get-project-temp-id temp-ids (:project/name ns))]
                                 (if-not (and (:namespace/name ns)
                                              (:namespace/file ns))
                                   (do (println "ERROR: Namespace name or file name is empty " ns) r)
                                   (into r [[:db/add file-temp-id :file/name (:namespace/file ns)]
                                            [:db/add ns-temp-id :namespace/file file-temp-id]
                                            [:db/add ns-temp-id :namespace/name (:namespace/name ns)]
                                            [:db/add ns-temp-id :namespace/project prj-temp-id]]))))
                             []))]
    {:facts (into facts tx-data)
     :temp-ids temp-ids''}))

(defn all-vars-facts [{:keys [facts temp-ids]} forms]
  (let [vars-set (reduce (fn [r {:keys [ns form]}]
                           (if (and (list? form)
                                    (symbol? (second form))
                                    (contains? def-set (first form)))
                             (conj r {:var-symb (second form)
                                      :ns ns
                                      :line (:line (meta form))})
                             r))
                         #{}
                         forms)
        vars-temp-ids (zipmap (map :var-symb vars-set) (iterate dec (next-temp-id temp-ids)))
        temp-ids' (assoc temp-ids :vars vars-temp-ids)
        tx-data (->> vars-set
                     (reduce (fn [r {:keys [var-symb ns line]}]
                               (let [var-temp-id (get vars-temp-ids var-symb)
                                     ns-temp-id (get-namespace-temp-id temp-ids (:namespace/name ns))]
                                 (if-not (:namespace/name ns)
                                   (do (println "ERROR: Namespace name is empty " ns) r)
                                   (into r [[:db/add var-temp-id :var/name var-symb]
                                            [:db/add var-temp-id :var/namespace ns-temp-id]
                                            [:db/add var-temp-id :var/line line]]))))
                             []))]
    {:facts (into facts tx-data)
     :temp-ids temp-ids'}))

(defn all-forms-facts [{:keys [facts temp-ids]} forms]
  (let [temp-ids' temp-ids
        tx-data []]
    {:facts (into facts tx-data)
    :temp-ids temp-ids'}))

(defn index-project! [base-dir]
  ;; TODO: all-projs can be derived from all-forms
  (let [all-projs (all-projects (project base-dir))
        all-forms (->> (vals all-projs)
                       (mapcat (fn [p] (project-forms p))))
        ;; IMPORTANT: the order here is important since they are dependent
        all-facts (-> {:facts [] :temp-ids {}}
                      (all-projects-facts all-projs)
                      (all-namespaces-facts all-forms)
                      (all-vars-facts all-forms)
                      (all-forms-facts all-forms)
                      :facts)]
    (d/transact! db-conn all-facts)))

(comment



  (def proj (project "/home/jmonetta/my-projects/clindex"))

  (def all-projs (all-projects proj))

  (def core-cache-forms (project-forms (get all-projs 'org.clojure/core.cache)))

  (def all-forms (->> (vals all-projs)
                      (mapcat (fn [p]
                                (project-forms p)))
                      doall))


  (count all-forms)
  (prn all-forms)

  (def all-facts (-> {:facts [] :temp-ids{}}
                     (all-projects-facts all-projs)
                     (namespaces-facts all-forms)
                     :facts))

  (def tx-result (index-project! "/home/jmonetta/my-projects/clindex"))

  ;; all namespaces for 'org.clojure/spec.alpha project
  (d/q '[:find ?nsn
         :in $ ?pn
         :where
         [?pid :project/name ?pn]
         [?nsid :namespace/project ?pid]
         [?nsid :namespace/name ?nsn]]
       @db-conn
       'org.clojure/spec.alpha)

  ;; all namespaces for all projects
  (d/q '[:find ?pid ?pn ?nsid ?nsn
         :where
         [?pid :project/name ?pn]
         [?nsid :namespace/project ?pid]
         [?nsid :namespace/name ?nsn]]
       @db-conn)

  ;; all vars for clojure.spec.alpha namespace
  (d/q '[:find ?vn ?vl ?fname
         :in $ ?nsn
         :where
         [?fid :file/name ?fname]
         [?nid :namespace/file ?fid]
         [?nid :namespace/name ?nsn]
         [?vid :var/namespace ?nid]
         [?vid :var/name ?vn]
         [?vid :var/line ?vl]]
       @db-conn
       'clojure.spec.alpha)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

  (index-project "/home/jmonetta/my-projects/clindex")

  (utils/all-files "/home/jmonetta/my-projects/clindex" analyze-source-file?)

  (utils/jar-files "/home/jmonetta/.m2/repository/org/clojure/core.specs.alpha/0.2.44/core.specs.alpha-0.2.44.jar"
                   analyze-source-file?)


  (parse-file (utils/make-file "/home/jmonetta/my-projects/clindex/src/clindex/core.clj"))
  (parse-file (utils/make-file "/home/jmonetta/.m2/repository/org/clojure/core.specs.alpha/0.2.44/core.specs.alpha-0.2.44.jar"
                               "clojure/core/specs/alpha.clj"))

  (->> (vals (tools-dep/resolve-deps {:deps '{
                                              ;; org.clojure/clojure    {:mvn/version "1.10.0"}
                                              ;; martian                {:mvn/version "0.1.5"}
                                              ;; org.clojure/spec.alpha {:git/url "https://github.com/clojure/spec.alpha.git"
                                              ;;                         :sha "739c1af56dae621aedf1bb282025a0d676eff713"}
                                              clj-tree-layout         {
                                                                       :local/root "/home/jmonetta/my-projects/clj-tree-layout"
                                                                       ;;  :git/url "https://github.com/jpmonettas/clj-tree-layout.git"
                                                                       ;; :sha "39b2ff8a95ec947ff0ad1a5d7e1afaad5c141d40"
                                                                       }
                                              }
                                      :mvn/repos {"central" {:url "https://repo1.maven.org/maven2/"}
                                                  "clojars" {:url "https://repo.clojars.org/"}}}
                                     nil))
       (mapcat :paths))

  (ct/lib-location 'org.clojure/test.check
                 {:mvn/version "0.9.0"}
                 {:deps '{
                                     ;; org.clojure/clojure    {:mvn/version "1.10.0"}
                                     ;; martian                {:mvn/version "0.1.5"}
                                     ;; org.clojure/spec.alpha {:git/url "https://github.com/clojure/spec.alpha.git"
                                     ;;                         :sha "739c1af56dae621aedf1bb282025a0d676eff713"}
                                     clj-tree-layout         {
                                                              :local/root "/home/jmonetta/my-projects/clj-tree-layout"
                                                             ;;  :git/url "https://github.com/jpmonettas/clj-tree-layout.git"
                                                             ;; :sha "39b2ff8a95ec947ff0ad1a5d7e1afaad5c141d40"
                                                             }
                                     }
                       :mvn/repos {"central" {:url "https://repo1.maven.org/maven2/"}
                                   "clojars" {:url "https://repo.clojars.org/"}}}
                 )

)
