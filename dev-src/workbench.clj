(ns workbench
  (:require [clojure.tools.namespace.find :as ns-find]
            [clojure.tools.namespace.dir :as ns-dir]
            [clojure.tools.namespace.track :as ns-track]
            [clojure.tools.namespace.parse :as ns-parse]
            [clojure.tools.namespace.dependency :as dep]
            [clojure.java.io :as io]
            [datascript.core :as d]
            [clindex.api :as capi]
            [clindex.scanner :as scanner]
            [clojure.pprint :as pprint]
            [clindex.forms-facts.core :as forms-facts]
            [clindex.utils :as utils]

            [clindex.api :as clindex]
            [datascript.core :as d]
            [clojure.string :as str]
            [hashp.core]
            ))

(comment

  (require '[clindex.api :as clindex])
  (require '[datascript.core :as d])
  (require '[clojure.string :as str])
  (require '[clojure.pprint :as pprint])

  ;; first you index a project folder for some platforms
  (clindex/index-project! "./"
                          {:platforms #{:clj}})

  ;; retrieve the datascript dbs
  (def db (clindex/db :clj))

  ;; now you can query the dbs
  ;; lets query all the vars that start with "eval"
  (->> (d/q '[:find ?vname ?nname ?pname ?vline ?fname
                  :in $ ?text
                  :where
                  [?fid :file/name ?fname]
                  [?pid :project/name ?pname]
                  [?nid :namespace/file ?fid]
                  [?pid :project/namespaces ?nid]
                  [?nid :namespace/name ?nname]
                  [?nid :namespace/vars ?vid]
                  [?vid :var/name ?vname]
                  [?vid :var/line ?vline]
                  [(str/starts-with? ?vname ?text)]]
                db
                "eval")
       (map #(zipmap [:name :ns :project :line :file] %))
       (pprint/print-table))

  ;; who uses clojure.core/juxt ?
  (let [juxt-vid (d/q '[:find ?vid .
                    :in $ ?nsn ?vn
                    :where
                    [?nsid :namespace/name ?nsn]
                    [?nsid :namespace/vars ?vid]
                    [?vid :var/name ?vn]]
                  db
                  'clojure.core
                  'juxt)]
    (-> (d/pull db [{:var/refs [{:var-ref/namespace [:namespace/name]} :var-ref/line]}] juxt-vid)
        :var/refs
        (clojure.pprint/print-table)))

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

  (clindex/index-project! "./test-resources/test-project/"
                          {:platforms #{:clj}
                           :extra-schema {:compojure.route/method {:db/cardinality :db.cardinality/one}
                                          :compojure.route/url    {:db/cardinality :db.cardinality/one}}})

  (defmethod forms-facts/form-facts 'compojure.core/GET
    [all-ns-map {:keys [:namespace/name] :as ctx} [_ url :as form]]

    (let [route-id (utils/stable-id :route :get url)]
      {:facts [[:db/add route-id :compojure.route/method :get]
               [:db/add route-id :compojure.route/url url]]
      :ctx ctx}))

  (def db (clindex/db :clj))

  (d/q '[:find ?rmeth ?rurl
         :in $
         :where
         [?rid :compojure.route/method ?rmeth]
         [?rid :compojure.route/url ?rurl]]
       db)

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

  (require '[clojure.edn :as edn])

  (clindex/index-project! "./test-resources/test-project/"
                          {:platforms #{:clj}
                           :extra-schema {:project/aliases {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
                                          :deps.alias/name {:db/cardinality :db.cardinality/one}}
                           :on-new-facts (fn [new-facts] (prn "New Facts :" new-facts))
                           :extra-files {:index-file? (fn [{:keys [file-path]}]
                                                        (.endsWith file-path "/deps.edn"))
                                         :file-facts (fn [{:keys [project-id file-id file-path]}]
                                                       (let [aliases (-> (slurp file-path)
                                                                         (edn/read-string)
                                                                         :aliases)]
                                                         (->> aliases
                                                              (mapcat (fn [[a-key _]]
                                                                        (let [alias-id (utils/stable-id project-id :deps/alias a-key)]
                                                                          [[:db/add alias-id :deps.alias/name (name a-key)]
                                                                           [:db/add project-id :project/aliases alias-id ]]))))))}})

  (pprint/pprint (d/q '[:find ?pname ?aname
                        :in $
                        :where
                        [?pid :project/name ?pname]
                        [?pid :project/aliases ?aid]
                        [?aid :deps.alias/name ?aname]]
                      (clindex/db :clj)))
  )
