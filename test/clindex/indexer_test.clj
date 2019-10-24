(ns clindex.indexer-test
  (:require [clindex.indexer :as indexer]
            [clindex.scanner :as scanner]
            [clindex.scanner-test :as scanner-test]
            [clojure.java.io :as io]
            [clojure.test :refer [is deftest testing use-fixtures]]
            [clojure.tools.namespace.find :as ns-find]
            [clojure.spec.test.alpha :as stest]
            [expound.alpha :as expound]
            [clojure.spec.alpha :as s]))

(def all-projects nil)
(def all-namespaces nil)

(defn with-scanned-projs-and-namespaces [f]
  (try
    (set! s/*explain-out* expound/printer)
    (stest/instrument)
    (alter-var-root (var all-projects) (constantly (scanner/scan-all-projects (str (io/file "./test-resources/test-project")) {:platform ns-find/clj})))
    (alter-var-root (var all-namespaces) (constantly (scanner/scan-namespaces all-projects {:platform ns-find/clj})))
    (f)
    (stest/unstrument)
    (catch Exception e
      (println (.getMessage e))
      (println "KEYS " (keys (ex-data e))))))

(use-fixtures :once with-scanned-projs-and-namespaces)

(defn count-facts-by-attr [facts]
  (reduce (fn [r [_ _ attr _]]
            (update r attr (fnil inc 0)))
          {}
          facts))

(defn fact-exist?
  "Search for a fact in facts that support templates"
  [fact-template facts]
  (boolean (some (fn [fact]
                   (every? identity
                           (map #(or (= %2 '_) (= %1 %2))
                                fact
                                fact-template)))
                 facts)))

(deftest project-facts-test
  (let [transit-proj (get all-projects 'com.cognitect/transit-java)
        transit-facts (#'indexer/project-facts transit-proj)
        transit-facts-count (count-facts-by-attr transit-facts)]
    (is (= (:project/depends transit-facts-count) 2)
        "Should index 2 dependencies")
    (is (fact-exist? '[_ _ :project/name com.cognitect/transit-java] transit-facts)
        "Should index name")
    (is (fact-exist? '[_ _ :project/version "0.8.332"] transit-facts)
        "Should index version")))

(deftest namespace-forms-facts-test
  (let [test-code-facts (#'indexer/namespace-full-facts all-namespaces 'test-code)
        facts-count (count-facts-by-attr test-code-facts)]

    (is (= (:var/name facts-count) 5)
        "Should index 5 vars for test-code namespace")

    (is (= (:var/function facts-count) 3)
        "Should index 2 function vars for test-code namespace")

    (is (= (:function/macro? facts-count) 1)
        "Should index 1 macro for test-code namespace")

    (is (= (:function/source-form facts-count) 2)
        "Should index 2 source forms for test-code namespace")

    (is (= (:function/source-str facts-count) 2)
        "Should index 2 source strings for test-code namespace")

    (is (= (:function/proto-var facts-count) 1)
        "Should index 1 protocol function for test-code namespace")

    (is (= (:var/protocol? facts-count) 1)
        "Should index 1 protocol for test-code namespace")

    (is (= (:var/multi facts-count) 1)
        "Should index 1 multi definition test-code namespace")

    (is (= (:multimethod/source-form facts-count) (:multimethod/dispatch-val facts-count) 1)
        "Should index 1 multimethod definition test-code namespace")))


(comment
  (def all-projs (scanner/scan-all-projects (str (io/file "./test-resources/test-project")) {:platform ns-find/clj}))
  (def all-namespaces  (scanner/scan-namespaces all-projs {:platform ns-find/clj}))

  (#'indexer/namespace-full-facts all-namespaces 'test-code)

  (#'indexer/enhance-form-list-meta
   '(defn some-function [arg1 arg2]
      (let [a 1
            b (+ arg1 arg2)]
        (+ a b)))
   "asdf"
   all-namespaces
   'test-code)
  )
