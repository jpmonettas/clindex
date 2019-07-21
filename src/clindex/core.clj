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

(def db-conn (d/create-conn {:project/name {:db/cardinality :db.cardinality/one}
                             :project/depends {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
                             :namespace/name {:db/cardinality :db.cardinality/one}
                             :namespace/project {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}}))

(def mvn-repos {"central" {:url "https://repo1.maven.org/maven2/"}
                "clojars" {:url "https://repo.clojars.org/"}})

(def main-project-symb 'clindex.core/main-project)

(defn all-projects [{:keys [deps] :as main-project}]
  (let [all-prjs (-> (tools-dep/resolve-deps (assoc main-project :mvn/repos mvn-repos) nil)
                     (assoc main-project-symb (-> (select-keys main-project [:paths])
                                                  (assoc :main? true))))
        main-project-deps-set (->> (keys deps) (into #{}))]
    ;; add ::main-project to dependencies :dependents
    (->> all-prjs
         (map (fn [[p m]]
                (if (contains? main-project-deps-set p)
                  [p (update m :dependents (fnil conj []) main-project-symb)]
                  [p m])))
         (into {}))))

(defn project [base-dir]
  (let [{:keys [deps paths]} (-> (utils/all-files base-dir #(str/ends-with? (str %) "deps.edn"))
                                 first
                                 :full-path
                                 tools-io/slurp-edn)]
    {:deps deps
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

(defn project-forms [proj-sym {:keys [paths]}]
  (->> (mapcat (fn [p]
                 (if (str/ends-with? p ".jar")
                   (utils/jar-files p analyze-source-file?)
                   (utils/all-files p analyze-source-file?)))
               paths)
       (mapcat (fn [src-file]
                 (let [{:keys [ns-name alias-map source-forms]} (parse-file src-file)
                       ns {:namespace/name ns-name
                           :namespace/requires alias-map
                           :project/name proj-sym}]
                   (map (fn [form]
                          (with-meta
                            {:ns ns
                             :form form}
                            {:type :clindex/form}))
                        source-forms))))))

(def def-set #{'def 'defn 'defn- 'defmulti 'deftype 'defprotocol 'defrecod})

(defn project-dependencies [project-symbol projects-map]
  (reduce-kv (fn [r dep-proy-symb {:keys [dependents]}]
               (if (contains? (into #{} dependents) project-symbol)
                 (conj r dep-proy-symb)
                 r))
             #{}
             projects-map))

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
        new-temp-ids (assoc temp-ids :projects project-temp-ids)
        tx-data (reduce-kv (fn [r proj-symb {:keys [dependents paths]}]
                             (let [pid (get project-temp-ids proj-symb)
                                   pdeps (project-dependencies proj-symb all-projects-map)
                                   pdeps-ids (map #(get project-temp-ids %) pdeps)]
                               (->> r
                                    (into [[:db/add  pid :project/name (str proj-symb)]])
                                    (into (mapv (fn [pdid]
                                                 [:db/add pid :project/depends pdid])
                                               pdeps-ids)))))
                 []
                 all-projects-map)]
    {:facts (into facts tx-data)
     :temp-ids new-temp-ids}))

;; TODO: memoize this to speed it up
#_(defn project-id-by-name [db name]
  (d/q '[:find ?pid .
         :in $ ?pn
         :where [?pid :project/name ?pn]]
       db
       (str name)))

(defn get-namespace-temp-id [temp-ids ns-symb]
  (get-in temp-ids [:namespaces ns-symb]))

(defn all-namespaces [forms]
  (reduce (fn [r {:keys [ns]}]
            (conj r ns))
          #{}
          forms))

(defn all-namespaces-facts [{:keys [facts temp-ids]} forms]
  (let [namespaces-set (reduce (fn [r {:keys [ns]}]
                                 (conj r (:namespace/name ns)))
                               #{}
                               forms)
        ns-temp-ids (zipmap namespaces-set (iterate dec (next-temp-id temp-ids)))
        new-temp-ids (assoc temp-ids :namespaces ns-temp-ids)
        tx-data (->> (all-namespaces forms)
                     (reduce (fn [r ns]
                               ;; TODO: add namespace requires facts
                               (let [ns-temp-id (get ns-temp-ids (:namespace/name ns))
                                     prj-temp-id (get-project-temp-id temp-ids (:project/name ns))]
                                 (if-not (:namespace/name ns)
                                   (do (println "ERROR: Namespace name is empty " ns) r)
                                   (into r [[:db/add ns-temp-id :namespace/name (str (:namespace/name ns))]
                                            [:db/add ns-temp-id :namespace/project prj-temp-id]]))))
                             []))]
    {:facts (into facts tx-data)
     :temp-ids new-temp-ids}))

#_(defn index-symbols! [conn forms]
  (let [symbols-set (reduce (fn [r {:keys [form]}]
                              (if (and (list? form)
                                       (def-set (first form)))
                                (conj r (second form))
                                r))
                            #{}
                            forms)
        forms-ids (zipmap symbols-set (map #(* -1 %) (range)))
        facts (mapcat (fn [[symb tmp-id]]
                        [:db/add tmp-id :symbol/name symb])
                      forms-ids)]
    (d/transact! conn facts)))

(defn index-all! [conn all-projs all-forms]
  ;; TODO: all-projs can be derived all-from forms
  (let [all-facts (-> {:facts [] :temp-ids{}}
                      (all-projects-facts all-projs)
                      (all-namespaces-facts all-forms)
                      :facts)]
    (d/transact! conn all-facts)))

#_(defn index-project [base-dir]
  (let [all-projects (all-projects (project base-dir))
        all-forms (->> all-projects
                       (mapcat (fn [[p-sym p-map]]
                                 (project-forms p-sym p-map))))
        {:keys [tx-data]} (index-symbols! db-conn all-forms)]
    (println (format "Preindexed %d symbols" (count tx-data)))

    (index-all! db-conn all-forms)))

(comment



  (def proj (project "/home/jmonetta/my-projects/clindex"))

  (def all-projs (all-projects proj))

  (def core-cache-forms (project-forms 'org.clojure/core.cache
                                     (get all-projs 'org.clojure/core.cache)))

  (def all-forms (->> all-projs
                      (mapcat (fn [[p-sym p-map]]
                                (project-forms p-sym p-map)))
                      doall))


  (count all-forms)
  (prn all-forms)

  (def all-facts (-> {:facts [] :temp-ids{}}
                     (all-projects-facts all-projs)
                     (namespaces-facts all-forms)
                     :facts))

  (index-all! db-conn all-projs all-forms)

  (d/q '[:find ?nsn
         :in $ ?pn
         :where
         [?pid :project/name ?pn]
         [?nsid :namespace/project ?pid]
         [?nsid :namespace/name ?nsn]]
       @db-conn
       "org.clojure/spec.alpha")

  (d/q '[:find ?pid ?pn ?nsid ?nsn
         :where
         [?pid :project/name ?pn]
         [?nsid :namespace/project ?pid]
         [?nsid :namespace/name ?nsn]]
       @db-conn)
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

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!"))
