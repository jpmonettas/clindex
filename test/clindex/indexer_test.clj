(ns clindex.indexer-test
  (:require [clindex.indexer :as indexer]
            [clindex.scanner :as scanner]
            [clojure.java.io :as io]
            [clojure.test :refer [is deftest testing]]
            [clojure.tools.namespace.find :as ctnf]))



(defn count-facts-by-attr [facts]
  (reduce (fn [r [_ _ attr _]]
            (update r attr (fnil inc 0)))
          {}
          facts))

(deftest namespace-forms-facts-test
  (let [all-namespaces  (-> (scanner/all-projects (io/file (io/resource "test-project")) {:platform ctnf/clj})
                            (scanner/all-namespaces {:platform ctnf/clj}))
        test-code-facts (indexer/namespace-forms-facts all-namespaces 'test-code)
        facts-count (count-facts-by-attr test-code-facts)]

    (is (= (:function/var facts-count) 2)
        "Should index 2 function vars for test-code namespace")

    (is (= (:function/macro? facts-count) 1)
        "Should index 1 macro for test-code namespace")

    (is (= (:function/source-form facts-count) 2)
        "Should index 2 source forms for test-code namespace")

    (is (= (:function/source-str facts-count) 2)
        "Should index 2 source strings for test-code namespace")))


(comment
 (def all-namespaces  (-> (scanner/all-projects (io/file (io/resource "test-project")) {:platform ctnf/clj})
                          (scanner/all-namespaces {:platform ctnf/clj})))

 (indexer/enhance-form-list-meta
  '(defn some-function [arg1 arg2]
     (let [a 1
           b (+ arg1 arg2)]
       (+ a b)))
  "asdf"
  all-namespaces
  'test-code)
 )
