;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns cella.subs
  (:refer-clojure :exclude [keys reset! realized?])
  (:require [cella.coerce :refer [db-> db-key-> ->db-key]]
            [reagent.core :as r]
            ["@react-native-community/async-storage" :default AsyncStorage]
            [utilis.js :as j]))

(defonce subscriptions (r/atom {}))
(defonce realized-keys (r/atom #{}))

(defn reset!
  [key value]
  (swap! realized-keys conj key)
  (when-let [value-atom (get @subscriptions key)]
    (clojure.core/reset! value-atom value) value))

(defn subscribe
  [key]
  (when-not (get @subscriptions key)
    (let [value-atom (r/atom nil)]
      (swap! subscriptions assoc key value-atom)
      (-> AsyncStorage
          (j/call :getItem (->db-key key))
          (j/call :then #(do (swap! realized-keys conj key)
                             (when-let [v (not-empty (db-> %))]
                               (reset! key v))))
          (j/call :catch #(js/console.error "cella.subs/subscribe" %)))))
  (get @subscriptions key))

(defn realized?
  [key]
  (boolean (get @realized-keys key)))

(defn keys
  []
  (when-not (get @subscriptions ::all-keys)
    (let [value-atom (r/atom nil)]
      (swap! subscriptions assoc ::all-keys value-atom)
      (->  AsyncStorage
           (j/call :getAllKeys)
           (j/call :then #(reset! ::all-keys (map db-key-> %)))
           (j/call :catch #(js/console.error "cella.subs/all-keys" %)))))
  (get @subscriptions ::all-keys))
