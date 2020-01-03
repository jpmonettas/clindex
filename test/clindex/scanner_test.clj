(ns clindex.scanner-test
  (:require [clojure.test :refer [is deftest testing use-fixtures]]
            [clindex.scanner :as scanner]
            [clojure.java.io :as io]
            [clojure.tools.namespace.find :as ns-find]
            [clojure.spec.test.alpha :as stest]
            [clojure.spec.alpha :as s]
            [expound.alpha :as expound]
            [clindex.utils :as utils]))

(def all-projects nil)
(def all-namespaces nil)

(defn with-scanned-projs-and-namespaces [f]
  (set! s/*explain-out* expound/printer)
  (stest/instrument)
  (alter-var-root (var all-projects) (constantly (scanner/scan-all-projects (str (io/file "./test-resources/test-project")) {:platform ns-find/clj})))
  (alter-var-root (var all-namespaces) (constantly (scanner/scan-namespaces all-projects {:platform ns-find/clj})))
  (f)
  (stest/unstrument))

(use-fixtures :once with-scanned-projs-and-namespaces)

(deftest scan-namespace-test
  (testing "Test code namespace should be correctly scanned"
    (let [test-code-path (utils/normalize-path (io/file "./test-resources/test-project/src/test_code.cljc"))
          test-code-ns (scanner/scan-namespace test-code-path all-projects {:platform ns-find/clj})]
      (is (= (dissoc test-code-ns :namespace/file-content-path)
             (quote #:namespace{:project clindex/main-project,
                                :macros #{some-macro},
                                :name test-code,
                                :docstring "A not so well documented namespace"
                                :public-vars
                                #{the-multi-method some-function TheProtocol
                                  do-something},
                                :private-vars #{},
                                :dependencies #{clojure.core dep-code clojure.string},
                                :alias-map {str clojure.string, dep dep-code},
                                :forms
                                ({:form-list
                                  (ns test-code "A not so well documented namespace" (:require [clojure.string :as str] [dep-code :as dep])),
                                  :form-str
                                  "ns test-code\n \"A not so well documented namespace\"\n (:require [clojure.string :as str]\n           [dep-code :as dep]))"}
                                 {:form-list
                                  (defmacro
                                    some-macro
                                    [a b]
                                    (clojure.core/sequence
                                     (clojure.core/seq
                                      (clojure.core/concat
                                       (clojure.core/list 'test-code/+)
                                       (clojure.core/list 'test-code/a)
                                       (clojure.core/list 'test-code/b))))),
                                  :form-str "(defmacro some-macro [a b]\n  `(+ a b)))"}
                                 {:form-list
                                  (defn
                                    some-function
                                    [arg1 arg2]
                                    (let [a 1 b (+ arg1 arg2)] (+ a b))),
                                  :form-str
                                  "(defn some-function [arg1 arg2]\n  ;; Some comment\n  (let [a 1\n        b (+ arg1 arg2)]\n    (+ a b)))"}
                                 {:form-list
                                  (defprotocol TheProtocol (do-something [_])),
                                  :form-str
                                  "(defprotocol TheProtocol\n  (do-something [_]))"}
                                 {:form-list (defmulti the-multi-method type),
                                  :form-str "(defmulti the-multi-method type)"}
                                 {:form-list
                                  (defmethod the-multi-method java.lang.String [s] (dep/concatenate "Hello " s)),
                                  :form-str
                                  "(defmethod the-multi-method java.lang.String\n  [s]\n  (dep/concatenate \"Hello \" s))"})}))))))

(deftest scan-namespaces-test
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

      (is (= (count (:namespace/public-vars clojure-core-ns)) 534)
          "Public vars count doesn't match")

      (is (= (count (:namespace/private-vars clojure-core-ns)) 30)
          "Private vars count doesn't match")

      (is (= (:namespace/project clojure-core-ns)
             'org.clojure/clojure)
          "Namespace project name doesn't match "))))
