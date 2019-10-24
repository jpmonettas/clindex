(ns dep-code
  (:require [compojure.core :as comp :refer [defroutes GET]]))

(defroutes app
  (GET "/" [] "<h1>Hello World</h1>")
  (GET "/test" [] "<h1>Test</h1>"))

(defn concatenate [s1 s2]
  (str s1 s2))
