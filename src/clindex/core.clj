(ns clindex.core
  (:require [cljs.analyzer.api :as cljs-ana]
            [clojure.tools.namespace.parse :as tools-ns-parse]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [clojure.tools.deps.alpha :as tools-dep]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types]
            [cljs.tagged-literals :as tags]
            [clojure.string :as str]
            [clojure.tools.deps.alpha.util.io :as tools-io]
            [clindex.utils :as utils]
            [clojure.java.io :as io]
            [clojure.walk :as walk])
  (:import [java.io File])
  (:gen-class))

(def mvn-repos {"central" {:url "https://repo1.maven.org/maven2/"}
                "clojars" {:url "https://repo.clojars.org/"}})


(defn all-projects [{:keys [deps] :as main-project}]
  (-> (tools-dep/resolve-deps (assoc main-project :mvn/repos mvn-repos) nil)
      (assoc ::main-project main-project)))

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
                *ns* name]
        {:ns-name name
         :absolute-path full-path
         :alias-map requires
         :source-forms (reader/read {:read-cond :allow}
                                    (reader-types/indexing-push-back-reader (str "[" (slurp content-url) "]")))}))
    (catch Exception e
      (println (format "ERROR whilre reading file %s %s" full-path (ex-message e)))
      ;; (.printStackTrace e)
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
                 (let [{:keys [ns-name source-forms]} (parse-file src-file)]
                   (map (fn [form]
                          (with-meta
                            {:project proj-sym
                             :ns-name ns-name
                             :form form
                             ;;:form-first-symbol (first form)
                             }
                            {:type :clindex/form}))
                        source-forms))))))

(defn pre-index-symbols [forms]
  )

(defn index-all [db forms]
  )

(defn index-project [base-dir]
  (let [all-forms (->> (project base-dir)
                       all-projects
                       (mapcat (fn [[p-sym p-map]]
                                 (project-forms p-sym p-map))))
        ;; symbols-db (pre-index-symbols all-forms)
        ]
    ;;(index-all symbols-db all-forms)
    all-forms
    ))

(comment

  (index-project "/home/jmonetta/my-projects/clindex")

  (def test-forms
    (forms-seq
     '{org.clojure/tools.macro
       {:mvn/version "0.1.2",
        :deps/manifest :mvn,
        :paths
        ["/home/jmonetta/.m2/repository/org/clojure/tools.macro/0.1.2/tools.macro-0.1.2.jar"],
        :dependents [org.ajoberstar/ike.cljj]},
       :clindex.core/main-project
       {:deps
        {org.clojure/clojure #:mvn{:version "RELEASE"},
         datascript #:mvn{:version "0.17.1"},
         org.ajoberstar/ike.cljj #:mvn{:version "0.4.1"},
         org.clojure/tools.deps.alpha #:mvn{:version "0.6.480"},
         org.clojure/tools.analyzer #:mvn{:version "0.7.0"},
         org.clojure/clojurescript #:mvn{:version "1.10.516"}},
        :paths ["resources" "src"]}}))

  (prn test-forms)


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
