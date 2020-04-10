(ns clindex.api
  "The namespace intended to be required by clindex users.

  Use `index-project!` for indexing any project and `db` for retrieveing
  datascript dbs by platform."
  (:require [clindex.scanner :as scanner]
            [clindex.indexer :as indexer]
            [datascript.core :as d]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.namespace.find :as ns-find]
            [clojure.tools.namespace.parse :as ns-parse]
            [clojure.tools.namespace.track :as ns-track]
            [clindex.schema :refer [schema]]
            [clindex.utils :as utils]
            [hawk.core :as hawk]
            [clojure.tools.namespace.file :as ns-file]
            [clojure.pprint :as pprint]
            [clojure.spec.alpha :as s]))

(def effective-schema (atom nil))
(def db-conns (atom {}))
(def all-projects-by-platform (atom nil))
(def all-ns-by-platform (atom nil))
(def trackers-by-platform (atom nil))
(def files-facts-by-platform (atom nil))

(comment
  (index-project! "./test-resources/test-project" {:platforms #{:clj}})

  (index-project! "." {:platforms #{:cljs}})
  (index-project! "/home/jmonetta/my-projects/district0x/memefactory" {:platforms #{:cljs}})

  (require '[clojure.pprint :as pprint])
  (file-change-handler (fn [diff] (pprint/pprint diff))
                       #{:clj}
                       {}
                       {:kind :delete
                        :file (io/file "./test-resources/test-project/src/test_code.cljc")})
  (file-change-handler (fn [diff] (pprint/pprint diff))
                       #{:clj}
                       {}
                       {:kind :modify
                        :file (io/file "./test-resources/test-project/src/test_code.cljc")})
  )

(defn- get-ns-for-path [all-ns file-path]
  (->> (vals all-ns)
       (some #(when (= (:namespace/file-content-path %)
                       file-path)
                (:namespace/name %)))))

(defn- get-project-for-path [all-proj file-path]
  ;; TODO: this is kind of hacky
  ;; Maybe if project has the notion of a base project path we can
  ;; compare paths to figure this out
  (let [in-proj-files? (fn [files full-path]
                         (some #(= (:full-path %) full-path) files))]
    (or (->> (vals all-proj)
             (some (fn [proj]
                     (when (in-proj-files? (:project/files proj) file-path)
                       (:project/name proj)))))
        scanner/main-project-symb)))

(defn- build-dep-map [all-ns]
  (reduce (fn [r [ns-name {:keys [:namespace/dependencies]}]]
            (assoc r ns-name dependencies))
   {}
   all-ns))

(defn- build-opts [platform-key opts]
  {:platform (case platform-key
               :clj  ns-find/clj
               :cljs ns-find/cljs)
   :extra-files (:extra-files opts)})

(defn- process-unloads [{:keys [tracker] :as m}]
  (reduce (fn [r ns-symb]
            (let [ns-id (utils/namespace-id ns-symb)
                  reload? (some #(= % ns-symb) (::ns-track/load tracker))]
              (cond-> r
                true          (update-in [:tracker ::ns-track/unload] rest)
                ;; don't remove it if we are going to re load it, since we need the info there
                (not reload?) (update :namespaces dissoc ns-symb)
                true          (update :tx-data conj [:db.fn/retractEntity ns-id]))))
          m
          (::ns-track/unload tracker)))

(defn- process-loads [{:keys [tracker namespaces all-projects] :as m} opts]
  (reduce (fn [r ns-symb]
            (let [ns-file-path (:namespace/file-content-path (or (get namespaces ns-symb)
                                                                 (meta ns-symb)))
                  ns (scanner/scan-namespace ns-file-path all-projects opts)
                  updated-namespaces (assoc namespaces ns-symb ns)
                  ns-facts (indexer/namespace-full-facts updated-namespaces ns-symb)]
              (-> r
                  (update-in [:tracker ::ns-track/load] rest)
                  (assoc :namespaces updated-namespaces)
                  (update :tx-data into ns-facts))))
          m
          (::ns-track/load tracker)))

(defn- reindex-namespaces [all-projs all-ns tracker opts]
  (-> {:tracker tracker
       :tx-data []
       :namespaces all-ns
       :all-projects all-projs}
      process-unloads
      (process-loads opts)))

(defn extra-files-tx-data [extra-files project-symb file-facts ctx {:keys [kind file]}]
  (let [file-path (utils/normalize-path file)
        proj-id (utils/project-id project-symb)
        file-id (utils/file-id file-path)
        file-retract-files (mapv (fn [[_ eid attr v]] [:db/retract eid attr v]) file-facts)]
    (cond
      (#{:delete} kind)
      file-retract-files

      ;; on modification, retract all associated to file-id and add the facts again
      ;; TODO: factor this out, it is repeated in indexer/files-facts
      (#{:modify :add} kind)
      (->> file-retract-files
           (into [[:db/add proj-id :project/files file-id]
                  [:db/add file-id :file/name file-path]])
           (into ((:file-facts extra-files) {:project-id proj-id
                                             :file-id file-id
                                             :file-path file-path}))))))

(defn- reindex-file-type [file-path platform extra-files]
  (cond

    (.startsWith file-path ".#") ;; hack to avoid re-indexing emacs backup files
    nil

    (or (.endsWith file-path "clj")
        (.endsWith file-path "cljc")
        (.endsWith file-path "cljs"))
    :source-file

    (and (:index-file? extra-files)
         ((:index-file? extra-files) {:file-path file-path}))
    :extra-file

    :else nil))

(defn- file-change-handler [on-new-facts platforms extra-files ctx {:keys [kind file] :as op-info}] ;; kind can be :create, :modify or :delete ;; file is java.io.File
  (let [file-path (utils/normalize-path file)
        file-id (utils/file-id file-path)]
    (doseq [p platforms]
      (let [all-platform-projs (get @all-projects-by-platform p)
            this-file-facts (get-in @files-facts-by-platform [p file-id])
            tx-data (case (reindex-file-type file-path p extra-files)
                      :source-file (let [plat-opts (build-opts p {:extra-files extra-files})
                                         all-platform-ns (get @all-ns-by-platform p)
                                         platform-tracker (get @trackers-by-platform p)
                                         platform-tracker' (cond
                                                             (#{:delete} kind)
                                                             (let [ns-symb (get-ns-for-path all-platform-ns file-path)]
                                                               (ns-track/remove platform-tracker [ns-symb]))

                                                             (#{:modify :add} kind)
                                                             (let [ns-decl (try
                                                                             (ns-file/read-file-ns-decl file ns-find/clj)
                                                                             (catch Exception e
                                                                               (println "[Warning] exception while trying to read ns-decl for " (merge
                                                                                                                                                 {:file-path file-path}
                                                                                                                                                 (ex-data e)))))
                                                                   ns-symb (with-meta (ns-parse/name-from-ns-decl ns-decl)
                                                                             {:namespace/file-content-path file-path})
                                                                   deps (ns-parse/deps-from-ns-decl ns-decl)]
                                                               (ns-track/add platform-tracker {ns-symb deps})))
                                         _ (println (format "File %s changed, retracting namespaces (%s), indexing namspeces (%s)"
                                                            file-path
                                                            (pr-str (::ns-track/unload platform-tracker'))
                                                            (pr-str (::ns-track/load platform-tracker'))))
                                         {:keys [tx-data namespaces tracker]} (reindex-namespaces all-platform-projs all-platform-ns platform-tracker' plat-opts)]
                                     (swap! all-ns-by-platform assoc p namespaces)
                                     (swap! trackers-by-platform assoc p tracker)
                                     tx-data)

                      :extra-file
                      (do
                        (println (format "Extra file %s changed, reindexing ..." file-path))
                        (extra-files-tx-data extra-files
                                             (get-project-for-path all-platform-projs file-path)
                                             this-file-facts
                                             ctx
                                             op-info))

                      [])

            tx-data-diff (:tx-data (d/transact! (get @db-conns p) tx-data))]

        (when (seq tx-data-diff)
          (on-new-facts tx-data-diff))))))

(s/def ::on-new-facts (s/fspec :args (s/cat :new-facts (s/coll-of :datomic/fact))))

(s/fdef index-project!
  :args (s/cat :base-dir :file/path
               :opts (s/keys :req-un [:clindex/platforms]
                             :opt-un [:datascript/extra-schema
                                      :scanner/extra-files
                                      ::on-new-facts])))
(defn index-project!
  "Goes over all Clojure[Script] files (depending on platforms) inside `base-dir` and index facts about the project
  and all its dependencies.
  Possible `opts` are :
    - :platforms (required), a set with the platforms to index, like #{:clj :cljs}
    - :extra-schema, a datascript schema that is going to be merged with clindex.schema/schema
    - :extra-files, a map containing :index-file?, :file-facts
    - :on-new-facts, a fn of one arg that will be called with new facts everytime a file inside `base-dir` project sources changes"
  [base-dir {:keys [platforms extra-schema on-new-facts extra-files] :as opts}]
  (if-not (utils/sane-classpath?)
    (throw (ex-info "org.clojure/tools.namespace detected on classpath. Clindex uses a modified version of tools.namespace so exclude it from the classpath before continuing" {}))
    ;; index everything by platform
    (let [source-paths (->> (:paths (scanner/find-project-in-dir base-dir))
                            (map (fn [p] (str (utils/normalize-path (io/file base-dir)) p))))]
      (doseq [p platforms]
        (let [plat-opts (build-opts p opts)
              all-projs (scanner/scan-all-projects base-dir plat-opts)
              all-ns (scanner/scan-namespaces all-projs plat-opts)
              tracker (-> (ns-track/tracker)
                          (ns-track/add (build-dep-map (utils/reloadable-namespaces all-ns)))
                          (dissoc ::ns-track/unload ::ns-track/load)) ;; we can discard this first time since we are indexing everything
              facts (-> (indexer/all-facts {:projects all-projs
                                            :namespaces all-ns}
                                           {:extra-files extra-files}))
              tx-data (-> (reduce concat (vals (:facts-by-file facts)))
                          (into (:all-projects-facts facts))
                          (into (:all-source-facts facts)))]
          (utils/check-facts tx-data)
          (swap! files-facts-by-platform assoc p (:facts-by-file facts))
          (reset! effective-schema (merge schema extra-schema))
          (swap! db-conns assoc p (d/create-conn @effective-schema))
          (swap! all-projects-by-platform assoc p all-projs)
          (swap! all-ns-by-platform assoc p all-ns)
          (swap! trackers-by-platform assoc p tracker)
          (println (format "About to transact %d facts" (count tx-data) "for platform" p))
          (d/transact! (get @db-conns p) tx-data)))

      ;; install watcher for reindexing when on-new-facts callback provided
      (when on-new-facts
        (let [paths (scanner/calculate-base-paths base-dir source-paths )]
          (println "Watching " paths)
          (hawk/watch!
           [{:paths paths
             :handler (partial file-change-handler on-new-facts platforms extra-files)}])))))
  nil)

(s/fdef db
  :args (s/cat :platform :clindex/platform))

(defn db
  "Returns the datascript db index for the `platform`"
  [platform]
  @(get @db-conns platform))

(defn db-schema
  "Returns the current dbs schema"
  []
  @effective-schema)
