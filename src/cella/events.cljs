;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns cella.events
  (:require [cella.connection :as db]
            [integrant.core :as ig]
            [re-frame.core :refer [reg-event-fx reg-fx]]))

(defmethod ig/init-key :cella/events [_ {:keys [db-connection]}]
  (reg-fx
   :cella/run
   (fn [expr]
     (try (db/run db-connection expr)
          (catch js/Error e
            (js/console.warn e)))))
  (reg-event-fx
   :cella/run
   (fn [_ [_ expr]]
     {:cella/run expr})))
