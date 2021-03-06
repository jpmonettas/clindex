(ns clindex.api-test
  (:require [clindex.api :as api]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [datascript.core :as d]
            [clojure.java.io :as io]
            [clojure.spec.test.alpha :as stest]
            [expound.alpha :as expound]
            [clojure.spec.alpha :as s]))

(defn with-indexed-test [f]
  (stest/unstrument) ;; too big to run specs, takes forever
  (api/index-project! (str (io/file "./test-resources/test-project")) {:platforms #{:clj}})
  (f))

(use-fixtures :once with-indexed-test)


(deftest indexed-clj-db-test
  (let [db (api/db :clj)
        test-code-ns-id (d/q '[:find ?nsid .
                               :in $ ?nsn
                               :where [?nsid :namespace/name ?nsn]]
                             db
                             'test-code)]
    (testing "Pulling namespace should pull all components"
      (is (= (d/pull db [:*] test-code-ns-id)
             '{:db/id 1653199087,
               :namespace/docstring "A not so well documented namespace",
               :namespace/file #:db{:id 1895585013},
               :namespace/name test-code,
               :namespace/depends [#:db{:id 482712339} #:db{:id 544082019} #:db{:id 1771627449}]
               :namespace/vars
               [{:db/id 189378944,
                 :var/column 4,
                 :var/end-column 16,
                 :var/function
                 {:db/id 1493209856,
                  :function/proto-var #:db{:id 453362419}
                  :function/args ["[_]"]},
                 :var/line 17,
                 :var/name do-something,
                 :var/namespace 1653199087,
                 :var/public? true}
                {:var/name the-multi-method,
                 :var/end-column 27,
                 :var/column 11,
                 :var/line 19,
                 :var/refs
                 [{:db/id 1699847084,
                   :var-ref/column 11,
                   :var-ref/end-column 27,
                   :var-ref/in-function #:db{:id 1859721138},
                   :var-ref/line 19,
                   :var-ref/namespace #:db{:id 1653199087}}
                  {:db/id 2036471479,
                   :var-ref/column 12,
                   :var-ref/end-column 28,
                   :var-ref/in-function #:db{:id 1859721138},
                   :var-ref/line 21,
                   :var-ref/namespace #:db{:id 1653199087}}],
                 :var/namespace 1653199087,
                 :db/id 422022587,
                 :var/multi
                 {:db/id 610769301,
                  :multi/dispatch-form type,
                  :multi/methods
                  [{:db/id 654778333,
                    :multimethod/dispatch-val java.lang.String,
                    :source/form (clojure.core/defmethod the-multi-method java.lang.String [s] (dep/concatenate "Hello " s)),
                    :source/str "(defmethod the-multi-method java.lang.String\n  [s]\n  (dep/concatenate \"Hello \" s))"}]},
                 :var/public? true}
                {:db/id 453362419,
                 :var/column 14,
                 :var/end-column 25,
                 :var/line 16,
                 :var/name TheProtocol,
                 :var/namespace 1653199087,
                 :var/protocol? true,
                 :var/public? true}
                {:var/name some-macro,
                 :var/end-column 24,
                 :var/column 14,
                 :var/line 7,
                 :var/refs
                 [{:db/id 1509927944,
                   :var-ref/column 14,
                   :var-ref/end-column 24,
                   :var-ref/in-function #:db{:id 182575839},
                   :var-ref/line 7,
                   :var-ref/namespace #:db{:id 1653199087}}],
                 :var/namespace 1653199087,
                 :db/id 1169875698,
                 :var/function
                 {:db/id 182575839,
                  :function/args ["[a b]"],
                  :function/macro? true,
                  :source/form (clojure.core/defmacro some-macro [a b] (clojure.core/sequence (clojure.core/seq (clojure.core/concat (clojure.core/list 'test-code/+) (clojure.core/list 'test-code/a) (clojure.core/list 'test-code/b))))),
                  :source/str "(defmacro some-macro [a b]\n  `(+ a b)))"},
                 :var/public? true}
                {:var/name some-function,
                 :var/end-column 20,
                 :var/column 7,
                 :var/line 10,
                 :var/refs
                 [{:db/id 1166623671,
                   :var-ref/column 7,
                   :var-ref/end-column 20,
                   :var-ref/in-function #:db{:id 993887617},
                   :var-ref/line 10,
                   :var-ref/namespace #:db{:id 1653199087}}],
                 :var/namespace 1653199087,
                 :db/id 1289073799,
                 :var/function
                 {:db/id 993887617,
                  :function/args ["[arg1 arg2]"],
                  :source/form (clojure.core/defn some-function [arg1 arg2] (let [a 1 b (+ arg1 arg2)] (+ a b))),
                  :source/str "(defn some-function [arg1 arg2]\n  ;; Some comment\n  (let [a 1\n        b (+ arg1 arg2)]\n    (+ a b)))"},
                 :var/public? true}]})))))

(deftest file-change-handler-test
  (testing "A modified file without any modification should have the same facts retracted than added"
    (#'api/file-change-handler (fn [diff-datoms]
                                 (let [datoms-with-add (fn [add?]
                                                         (->> diff-datoms
                                                              (keep (fn [[eid a v _ a?]] (when (= a? add?) [eid a v])))
                                                              (into #{})))]
                                   (is (= (datoms-with-add true)
                                          (datoms-with-add false)))))
                               #{:clj}
                               {}
                               {:kind :modify
                                :file (io/file "./test-resources/test-project/src/test_code.cljc")})))
