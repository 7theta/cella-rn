;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.
(ns cella.fx
  (:require [cella.subs :as subs]
            [cella.coerce :refer [->db ->db-key]]
            [re-frame.core :refer [reg-fx dispatch]]
            ["@react-native-community/async-storage" :default AsyncStorage]
            [utilis.js :as j]))

(reg-fx
 :cella.async-storage/set!
 (fn [{:keys [key value on-complete on-error]}]
   (->  AsyncStorage
        (j/call :setItem (->db-key key) (->db value))
        (j/call :then #(do (subs/reset! key value)
                           (when on-complete (dispatch (conj (vec on-complete) %)))))
        (j/call :catch #(when on-error (dispatch (conj (vec on-error) %)))))))

(reg-fx
 :cella.async-storage/remove!
 (fn [{:keys [key on-complete on-error]}]
   (->  AsyncStorage
        (j/call :removeItem (->db-key key))
        (j/call :then #(do (subs/remove-key! key)
                           (when on-complete (dispatch (conj (vec on-complete) %)))))
        (j/call :catch #(when on-error (dispatch (conj (vec on-error) %)))))))
