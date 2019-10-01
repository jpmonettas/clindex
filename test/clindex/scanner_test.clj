(ns clindex.scanner-test
  (:require [clojure.test :refer :all]
            [clindex.scanner :as scanner]
            [clojure.java.io :as io]
            [clojure.tools.namespace.find :as ctnf]))

(deftest all-namespaces-test
  (let [opts {:platform ctnf/clj}
        all-namespaces (-> (scanner/all-projects (io/file (io/resource "test-project")) opts)
                           (scanner/all-namespaces opts))]

    (testing "Test code namespace should be correctly parsed"
      (let [test-code-ns (get all-namespaces 'test-code)]
       (is (= (dissoc test-code-ns :namespace/file-content-path)
              (quote #:namespace{:macros #{some-macro},
                                 :name test-code,
                                 :public-vars #{some-function},
                                 :private-vars #{},
                                 :project clindex/main-project,
                                 :dependencies #{clojure.core clojure.string},
                                 :alias-map {str clojure.string},
                                 :forms
                                 ({:form-list
                                   (ns test-code (:require [clojure.string :as str])),
                                   :form-str
                                   "ns test-code\n (:require [clojure.string :as str]))"}
                                  {:form-list
                                   (defmacro
                                     some-macro
                                     [a b]
                                     (clojure.core/sequence
                                      (clojure.core/seq
                                       (clojure.core/concat
                                        (clojure.core/list 'clojure.core/+)
                                        (clojure.core/list 'user/a)
                                        (clojure.core/list 'user/b))))),
                                   :form-str "(defmacro some-macro [a b]\n  `(+ a b)))"}
                                  {:form-list
                                   (defn
                                     some-function
                                     [arg1 arg2]
                                     (let [a 1 b (+ arg1 arg2)] (+ a b))),
                                   :form-str
                                   "(defn some-function [arg1 arg2]\n  ;; Some comment\n  (let [a 1\n        b (+ arg1 arg2)]\n    (+ a b)))"})})))))

    ;; TODO: this should be really checked by counting clojure.core with a text editor
    (testing "Dependency code namespace should be correctly parsed"
      (let [clojure-core-ns (get all-namespaces 'clojure.core)]

        (is (= (:namespace/name clojure-core-ns) 'clojure.core)
            "Namespace name wasn't parsed correctly")

        (is (= (:namespace/macros clojure-core-ns) (quote #{when-first cond->> while import pvalues bound-fn vswap! dosync
                                                            with-loading-context .. delay with-bindings if-not doseq when-let
                                                            if-some with-precision lazy-seq let -> defstruct doto areduce
                                                            definline future fn as-> when-not when some->> ns amap declare or
                                                            assert-args defmethod time memfn cond-> dotimes with-open defonce
                                                            defn- sync assert letfn loop with-out-str condp cond with-in-str
                                                            some-> for binding with-local-vars with-redefs locking def-aset
                                                            defmulti if-let case io! lazy-cat comment add-doc-and-meta
                                                            when-class and when-some ->> refer-clojure}))
            "Namespace macros weren't parsed correctly")

        (is (= (count (:namespace/public-vars clojure-core-ns)) 527)
            "Public vars count doesn't match")

        (is (= (count (:namespace/private-vars clojure-core-ns)) 30)
            "Private vars count doesn't match")

        (is (= (:namespace/project clojure-core-ns)
               'org.clojure/clojure)
            "Namespace project name doesn't match ")))))
