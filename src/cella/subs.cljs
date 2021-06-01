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
  (:require [cella.connection :as db]
            [re-frame.core :as rf]
            [reagent.ratom :as rr]
            [integrant.core :as ig]
            [utilis.js :as j]
            [utilis.fn :refer [fsafe]]))

(defmethod ig/init-key :cella/subs [_ {:keys [db-connection]}]
  (rf/reg-sub-raw
   :cella/subscribe
   (fn [_ [_ expr]]
     (let [a (db/observe db-connection expr)]
       (rr/make-reaction
        (fn [] @a)
        :on-dispose #(db/dispose db-connection a))))))
