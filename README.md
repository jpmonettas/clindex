# clindex

WIP !!!

A Clojure[Script] source file indexer. Scans a Clojure[Script] project with all its dependencies and generates datascript dbs with facts about them.

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

Index project options can be :
- :platforms, a set containing :clj and/or :cljs
- :extra-schema, a schema that will be merged with dbs schemas

## DB schema

You can find the schema [here](/src/clindex/schema.clj).

## Datalog examples

Who calls datascript.core/q ?

TODO

## Extensions
