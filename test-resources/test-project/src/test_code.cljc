(ns test-code
  (:require [clojure.string :as str]
            [dep-code :as dep]))

#?(:clj
   (defmacro some-macro [a b]
     `(+ a b)))

(defn some-function [arg1 arg2]
  ;; Some comment
  (let [a 1
        b (+ arg1 arg2)]
    (+ a b)))

(defprotocol TheProtocol
  (do-something [_]))

(defmulti the-multi-method type)

(defmethod the-multi-method java.lang.String
  [s]
  (dep/concatenate "Hello " s))
