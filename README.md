# clindex

A general Clojure[Script] source file indexer. Scans a Clojure[Script] project with all its dependencies and generates datascript dbs with facts about them.

## Installation

Clindex is available as a Maven artifact from Clojars. The latest released version is:
[![Clojars Project](https://img.shields.io/clojars/v/clindex.svg)](https://clojars.org/clindex)<br>

## Usage

```clojure
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

;; =>

;; |         :name |               :ns |                  :project | :line |                                                                                                                       :file |
;; |---------------+-------------------+---------------------------+-------+-----------------------------------------------------------------------------------------------------------------------------|
;; |      eval-opt |      clojure.main |       org.clojure/clojure |   482 |                      jar:file:/home/jmonetta/.m2/repository/org/clojure/clojure/1.10.1/clojure-1.10.1.jar!/clojure/main.clj |
;; |      eval-str | cljs.repl.graaljs | org.clojure/clojurescript |    49 | jar:file:/home/jmonetta/.m2/repository/org/clojure/clojurescript/1.10.516/clojurescript-1.10.516.jar!/cljs/repl/graaljs.clj |
;; |   eval-result |   cljs.repl.rhino | org.clojure/clojurescript |    64 |   jar:file:/home/jmonetta/.m2/repository/org/clojure/clojurescript/1.10.516/clojurescript-1.10.516.jar!/cljs/repl/rhino.clj |
;; |      eval-opt |          cljs.cli | org.clojure/clojurescript |   260 |          jar:file:/home/jmonetta/.m2/repository/org/clojure/clojurescript/1.10.516/clojurescript-1.10.516.jar!/cljs/cli.clj |
;; |     eval-cljs |         cljs.repl | org.clojure/clojurescript |   682 |        jar:file:/home/jmonetta/.m2/repository/org/clojure/clojurescript/1.10.516/clojurescript-1.10.516.jar!/cljs/repl.cljc |
;; | evaluate-form |         cljs.repl | org.clojure/clojurescript |   499 |        jar:file:/home/jmonetta/.m2/repository/org/clojure/clojurescript/1.10.516/clojurescript-1.10.516.jar!/cljs/repl.cljc |
;; |      evaluate |         cljs.repl | org.clojure/clojurescript |   131 |        jar:file:/home/jmonetta/.m2/repository/org/clojure/clojurescript/1.10.516/clojurescript-1.10.516.jar!/cljs/repl.cljc |
;; | eval-resource | cljs.repl.graaljs | org.clojure/clojurescript |    52 | jar:file:/home/jmonetta/.m2/repository/org/clojure/clojurescript/1.10.516/clojurescript-1.10.516.jar!/cljs/repl/graaljs.clj |
;; |          eval |      clojure.core |       org.clojure/clojure |  3210 |                      jar:file:/home/jmonetta/.m2/repository/org/clojure/clojure/1.10.1/clojure-1.10.1.jar!/clojure/core.clj |

```

`index-project!` options can be :

- :platforms, a set containing :clj and/or :cljs
- :extra-schema, a schema that will be merged with dbs schemas
- :on-new-facts, a fn of one arg that will be called with new facts everytime a file inside base-dir project sources changes

## DB schema

You can find the schema [here](/src/clindex/schema.clj).


## Extending clindex

You can extend clindex to any form that appears in your source code by adding implementations of the `clindex.forms-facts/form-facts` multimethod.
The dispatch value for `clindex.forms-facts/form-facts` is the fully qualified form first symbol. The method will recieve as parameters :
- all-namespaces-map (spec :scanner/namespaces)
- a context (a map that at least will contain :namespace/name)
- the form

and should return a map with :
- :ctx (the new context)
- :facts (a collection of datascript facts)

### Example: indexing compojure routes
```clojure

(require '[clindex.forms-facts :as forms-facts])

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

;; =>
;; #{[:get "/"]
;;   [:get "/test"]
;;   [:get (add-wildcard path)]}

```

## Datalog examples

who calls clojure.core/juxt ?

```clojure
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

;; =>

;; | :var-ref/line |                                 :var-ref/namespace |
;; |---------------+----------------------------------------------------|
;; |          4215 |                   #:namespace{:name cljs.analyzer} |
;; |          1834 |                    #:namespace{:name cljs.closure} |
;; |            58 |                       #:namespace{:name cljs.main} |
;; |            55 |                       #:namespace{:name cljs.main} |
;; |           336 |                       #:namespace{:name cljs.repl} |
;; |          3934 |                   #:namespace{:name cljs.analyzer} |
;; |            37 |                       #:namespace{:name cljs.main} |
;; |           344 |      #:namespace{:name clojure.tools.analyzer.jvm} |
;; |          4186 |                   #:namespace{:name cljs.analyzer} |
;; |            80 |                       #:namespace{:name hawk.core} |
;; |          3355 |                   #:namespace{:name cljs.analyzer} |
;; |            97 |                #:namespace{:name cljs.core.server} |
;; |           163 |                 #:namespace{:name clindex.indexer} |
;; |           113 | #:namespace{:name clojure.tools.reader.impl.utils} |
;; |          2172 |                   #:namespace{:name cljs.analyzer} |
;; |            79 |                       #:namespace{:name hawk.core} |
;; |          2576 |                    #:namespace{:name clojure.core} |
;; |          1145 |                       #:namespace{:name cljs.repl} |
;; |          1994 |                    #:namespace{:name cljs.closure} |
;; |            90 |                   #:namespace{:name datascript.db} |
;; |           621 |                        #:namespace{:name cljs.cli} |

```

## Projects known to be using clindex

- [Clograms](https://github.com/jpmonettas/clograms) Explore clojure projects by building diagrams
