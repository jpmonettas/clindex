(ns test-code
  (:require [clojure.string :as str]))

#?(:clj
   (defmacro some-macro [a b]
     `(+ a b)))

(defn some-function [arg1 arg2]
  ;; Some comment
  (let [a 1
        b (+ arg1 arg2)]
    (+ a b)))
