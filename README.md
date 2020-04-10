# clindex

**Clindex** is a general and extensible Clojure[Script] source code indexer. It scans a Clojure[Script] project together with all its dependencies and generates a [datascript](https://github.com/tonsky/datascript) database with facts about them.

It is intended to be used as a platform for building dev tools so they don't have to deal with the complexities of understanding Clojure code by reading the filesystem.
Instead, as an api for talking about your code it gives you a datascript db full of facts you can use together with `d/q`, `d/pull`, `d/entity`, etc.

## Features

- Index **your project and all its dependency** tree (only lein and deps.edn supported so far)
- **Big set of facts** out of the box, see [schema](/src/clindex/schema.clj)
- **Extensible**, you can make any form generate any facts by adding a method for the `clindex.forms-facts.core/form-facts` multimethod,
  Also non source files can be indexed, check `:extra-files`.
- **Hot reload**, watches your sources and reindexes whenever something on its source path changes, taking care of retraction and notification

## Installation

**Clindex** is available as a Maven artifact from Clojars.

The latest released version is: [![Clojars Project](https://img.shields.io/clojars/v/clindex.svg)](https://clojars.org/clindex)<br>

## Usage

```clojure
(require '[clindex.api :as clindex])
(require '[datascript.core :as d])
(require '[clojure.string :as str])
(require '[clojure.pprint :as pprint])

;; first you index a project folder for some platforms
(clindex/index-project! "./" {:platforms #{:clj}})

;; then retrieve the datascript db for the platform you want to explore
(def db (clindex/db :clj))

;; now you can explore your code using datalog, pull or whatever you can run against datascript
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

`index-project!` options should be a map with the following keys :

- `:platforms` a set containing :clj and/or :cljs
- `:extra-schema` a schema that will be merged with dbs schemas
- `:extra-files` a map containing :index-file?, :file-facts, intended to be used on non clojure source files
- `:on-new-facts` a fn of one arg that will be called with new facts everytime a file inside base-dir project sources changes

## DB schema

You can find the schema [here](/src/clindex/schema.clj).


## Extending clindex

You can extend clindex to make any form generate any facts by adding implementations of the `clindex.forms-facts.core/form-facts` multimethod.

There are already some extensions not loaded by default, take a look at [/src/clindex/forms_facts/](here). For indexing re-frame facts for example just
`(require [clindex.forms_facts.re-frame :as re-frame-facts])` and when calling index-project! add to the `:extra-schema` `re-frame-facts/extra-schema`.

The dispatch value for `clindex.forms-facts.core/form-facts` is the fully qualified form first symbol. The method will receive as parameters :

- `all-namespaces-map` (spec `:scanner/namespaces`)
- `ctx` a context map that at least will contain `:namespace/name` and things like  `:in-function` if the form is inside a fn definition
- `form` the form with the first symbol fully qualified when it is a function, it also contains all metadata added by tools.reader + some more stuff

It should return a map with the following keys :

- `:ctx`, the new context
- `:facts`, a collection of datascript tx-data like `[:db/add eid attr val]`

### Using clindex for indexing othe project files (experimental)

You can extend clindex to make it index any files you want using the `:extra-files` option.

Imagine you want to index all projects deps.edn files aliases, you can try something like :

```clojure
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

(d/q '[:find ?pname ?aname
       :in $
       :where
       [?pid :project/name ?pname]
       [?pid :project/aliases ?aid]
       [?aid :deps.alias/name ?aname]]
     (clindex/db :clj))
;; =>
;; #{[clindex/main-project "bench"]
;;   [clindex/main-project "test"]
;;   [clindex/main-project "1.7"]}
```


### Example: indexing compojure routes
```clojure

(require '[clindex.forms-facts.core :as forms-facts])

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

- [Clograms](https://github.com/jpmonettas/clograms) Explore clojure codebases by building diagrams

## For developers

This is a high level overview of the api and the scanner.
<img src="/doc/api-and-scanner-diagram.png?raw=true"/>

This is a high level overview of the indexer.
<img src="/doc/indexer.png?raw=true"/>

## Known issues

- Not indexing deps.edn file source paths outside base dir until implementing `clindex.scanner/calculate-base-paths`
