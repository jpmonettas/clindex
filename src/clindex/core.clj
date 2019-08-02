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


;;;;;;;;;;;;;;
;; Projects ;;
;;;;;;;;;;;;;;

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

(defn data-readers [proj]
  )

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Namespaces and files ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ns-form [url]
  (-> url
      slurp
      java.io.StringReader.
      java.io.PushbackReader.
      tools-ns-parse/read-ns-decl))

(defn normalized-ns-form-str [ns-form]
  (let [ns-form-str (pr-str (remove #(= % '(:gen-class)) ns-form))]
    ns-form-str))

;; TODO : Make this also work for clojure.
;; The problem so far has to do with cljs-ana/parse-ns doesn't handle :refer :all
(defn analyze-ns [file-url]
  (:ast (try
          (when (ns-form file-url)
            (cljs-ana/parse-ns file-url))
          (catch Exception e
            (println "Error while analizing ns on " file-url)
            (clojure.repl/pst e 1)))))

(defn parse-file [{:keys [full-path content-url]} data-readers]
  (let [{:keys [requires name]} (analyze-ns content-url)]
    (binding [reader/*data-readers* (merge tags/*cljs-data-readers* data-readers)
              reader/*alias-map* requires
              reader/*read-eval* false
              ;;*ns* name
              ]
      {:ns-name name
       :absolute-path full-path
       :alias-map reader/*alias-map*
       :source-forms (try
                       (reader/read {:read-cond :allow}
                                    (reader-types/indexing-push-back-reader (str "[" (slurp content-url) "]")))
                       (catch Exception e
                         (println "Error when reading the file" content-url)
                         (clojure.repl/pst e 1)))})))

(defn analyze-source-file? [full-path]
  (or (str/ends-with? full-path ".cljs")
      (str/ends-with? full-path ".cljc")))

(defn project-forms [project data-readers]
  (->> (project-paths project)
       (mapcat (fn [p]
                 (if (str/ends-with? p ".jar")
                   (utils/jar-files p analyze-source-file?)
                   (utils/all-files p analyze-source-file?))))
       (mapcat (fn [src-file]
                 (let [{:keys [ns-name alias-map source-forms absolute-path]} (parse-file src-file data-readers)

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

;;;;;;;;;;;;;;;;;;;;;;
;; Facts generation ;;
;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *def-set* #{'def 'defn 'defn- 'declare 'defmulti 'deftype 'defprotocol 'defrecod})

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
                                    (contains? *def-set* (first form)))
                             (conj r {:var-symb (second form)
                                      :ns ns
                                      :line (:line (meta form))})
                             r))
                         #{}
                         forms)
        vars-temp-ids (zipmap vars-set (iterate dec (next-temp-id temp-ids)))
        temp-ids' (assoc temp-ids :vars vars-temp-ids)
        tx-data (->> vars-set
                     (reduce (fn [r {:keys [var-symb ns line] :as v}]
                               (let [var-temp-id (get vars-temp-ids v)
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
        data-readers (->> (map data-readers all-projs)
                          (reduce merge {}))
        _ (println "Using data_readers " data-readers)
        all-forms (->> (vals all-projs)
                       (mapcat (fn [p] (project-forms p data-readers))))
        ;; IMPORTANT: the order here is important since they are dependent
        all-facts (-> {:facts [] :temp-ids {}}
                      (all-projects-facts all-projs)
                      (all-namespaces-facts all-forms)
                      (all-vars-facts all-forms)
                      (all-forms-facts all-forms)
                      :facts)]
    (println (format "About to transact %d facts" (count all-facts)))
    (d/transact! db-conn all-facts)))

;;;;;;;;;;;;;;;;;;
;; For the repl ;;
;;;;;;;;;;;;;;;;;;

(comment



  (def proj (project "/home/jmonetta/my-projects/clindex"))
  (def proj (project "/home/jmonetta/my-projects/district0x/memefactory"))

  (def all-projs (all-projects proj))

  (def core-cache-forms (project-forms (get all-projs 'org.clojure/core.cache) {}))

  (def all-forms (->> (vals all-projs)
                      (mapcat (fn [p]
                                (project-forms p {})))
                      doall))


  (count all-forms)
  (prn all-forms)

  (def all-facts (-> {:facts [] :temp-ids{}}
                     (all-projects-facts all-projs)
                     (namespaces-facts all-forms)
                     :facts))

  (def tx-result (index-project! "/home/jmonetta/my-projects/clindex"))
  (def tx-result (index-project! "/home/jmonetta/my-projects/district0x/memefactory"))

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

  ;; This gives a error because :refer :all
  ;; (try
  ;;   (cljs-ana/parse-ns (java.io.StringReader. "(ns clojure.tools.analyzer
  ;; (:refer-clojure :exclude [macroexpand-1 macroexpand var? record? boolean?])
  ;; (:require [clojure.tools.analyzer.utils :refer :all]
  ;;           [clojure.tools.analyzer.env :as env])
  ;; (:import (clojure.lang Symbol IPersistentVector IPersistentMap IPersistentSet ISeq IType IRecord)))"))
  ;;   (catch Exception e
  ;;     (println e)))


  )
