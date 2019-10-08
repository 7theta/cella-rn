;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns cella.coerce
  (:require [cljs.reader :refer [read-string]]))

(def db-key-> read-string)

(def ->db-key pr-str)

(declare xform-map date?)

(defn- ->db
  [m]
  (-> m
      (xform-map clojure.core/name
                 (fn [v]
                   (cond
                     (keyword? v)
                     ["cella/kw" (name v)]

                     (date? v)
                     ["cella/date" (.getTime m)]

                     :else v)))
      clj->js
      js/JSON.stringify))

(defn- db->
  [m]
  (-> (js/JSON.parse m)
      js->clj
      (xform-map clojure.core/keyword
                 (fn [v]
                   (cond
                     (and (vector? v) (= "cella/kw" (first v)))
                     (keyword (second v))

                     (and (vector? v) (= "cella/date" (first v)))
                     (new js/Date (second v))

                     :else v)))))


;;; Private

(defn- date?
  [d]
  (boolean (when d (fn? (type (.-getMonth d))))))

(defn- xform-map
  [m kf vf]
  (into {} (map (fn [[k v]]
                  [(kf k)
                   (cond
                     (map? v)
                     (xform-map v kf vf)

                     (and (coll? v)
                          (not (and (string? (first v))
                                    (re-find #"^cella/.*$" (first v))))
                          (not (keyword? (first v))))
                     (mapv #(if (map? %) (xform-map % kf vf) %) v)

                     :else (vf v))]) m)))
