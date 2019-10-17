(ns clindex.scanner-test
  (:require [clojure.test :refer [is deftest testing use-fixtures]]
            [clindex.scanner :as scanner]
            [clojure.java.io :as io]
            [clojure.tools.namespace.find :as ctnf]
            [clojure.spec.test.alpha :as stest]))

(def all-projects nil)
(def all-namespaces nil)

(defn with-scanned-projs-and-namespaces [f]
  (stest/instrument)
  (alter-var-root (var all-projects) (constantly (scanner/scan-all-projects (str (io/file "./test-resources/test-project")) {:platform ctnf/clj})))
  (alter-var-root (var all-namespaces) (constantly (scanner/scan-all-namespaces all-projects {:platform ctnf/clj})))
  (f)
  (stest/unstrument))

(use-fixtures :once with-scanned-projs-and-namespaces)

(deftest scan-namespace-test
  (testing "Test code namespace should be correctly scanned"
    (let [test-code-path (.getAbsolutePath (io/file "./test-resources/test-project/src/test_code.cljc"))
          test-code-ns (scanner/scan-namespace test-code-path all-projects {:platform ctnf/clj})]
      (is (= (dissoc test-code-ns :namespace/file-content-path)
             (quote #:namespace{:macros #{some-macro},
                                :name test-code,
                                :public-vars
                                #{the-multi-method some-function TheProtocol
                                  do-something},
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
                                  "(defn some-function [arg1 arg2]\n  ;; Some comment\n  (let [a 1\n        b (+ arg1 arg2)]\n    (+ a b)))"}
                                 {:form-list
                                  (defprotocol TheProtocol (do-something [_])),
                                  :form-str
                                  "(defprotocol TheProtocol\n  (do-something [_]))"}
                                 {:form-list (defmulti the-multi-method type),
                                  :form-str "(defmulti the-multi-method type)"}
                                 {:form-list
                                  (defmethod the-multi-method java.lang.String [s] s),
                                  :form-str
                                  "(defmethod the-multi-method java.lang.String\n  [s]\n  s)"})}))))))

(deftest scan-all-namespaces-test
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

      (is (= (count (:namespace/public-vars clojure-core-ns)) 528)
          "Public vars count doesn't match")

      (is (= (count (:namespace/private-vars clojure-core-ns)) 30)
          "Private vars count doesn't match")

      (is (= (:namespace/project clojure-core-ns)
             'org.clojure/clojure)
          "Namespace project name doesn't match "))))
