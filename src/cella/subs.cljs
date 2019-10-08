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
  (:refer-clojure :exclude [keys])
  (:require [cella.coerce :refer [db-> db-key-> ->db-key]]
            [reagent.core :as r]
            [reagent.ratom :as ra]
            ["@react-native-community/async-storage" :default AsyncStorage]
            [utilis.js :as j]))

(defonce subscriptions (r/atom {}))
(defonce values (r/atom {}))

(defn subscribe
  [key]
  (when-not (get @subscriptions key)
    (swap! subscriptions assoc key (ra/make-reaction #(get @values key)))
    (->  AsyncStorage
         (j/call :getItem (->db-key key))
         (j/call :then #(when-let [v (not-empty (db-> %))] (swap! values assoc key v)))
         (j/call :catch #(js/console.error "cella.subs/subscribe" %))))
  (get @subscriptions key))

(defn keys
  []
  (when-not (get @subscriptions ::all-keys)
    (swap! subscriptions assoc ::all-keys (ra/make-reaction #(get @values ::all-keys)))
    (->  AsyncStorage
         (j/call :getAllKeys)
         (j/call :then #(swap! values assoc ::all-keys (map db-key-> %)))
         (j/call :catch #(js/console.error "cella.subs/all-keys" %))))
  (get @subscriptions ::all-keys))
