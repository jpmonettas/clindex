(ns dep-code
  "A documented namespace"
  (:require [compojure.core :as comp :refer [defroutes GET]]))

(defroutes app
  (GET "/" [] "<h1>Hello World</h1>")
  (GET "/test" [] "<h1>Test</h1>"))

(defn concatenate [s1 s2]
  (str s1 s2))

;; Things tha contains docs
;; ------------------------

(def var-with-doc "Some doc" 5)
(defn function-with-doc "Returns constantly true" [args] (constantly true))
(defn function-with-doc-and-meta "Returns constantly true" {:added "1.2"} [args] (constantly true))
(defn multi-arity-with-doc "Some doc" ([a] a) ([a b] (+ a b)))
(defn multi-arity-with-doc-and-meta "Some doc" {:added "1.2"} ([a] a) ([a b] (+ a b)))
(defmulti multi-with-doc "Some other doc" :type)
(defprotocol ProtoWithDoc "Some proto doc" (x [_]))
