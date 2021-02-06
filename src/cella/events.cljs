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
            [utilis.js :as j]
            [re-frame.core :refer [reg-event-fx reg-fx dispatch]]))

(defmethod ig/init-key :cella/events [_ {:keys [db-connection]}]
  (reg-fx
   :cella/run
   (fn [{:keys [expr on-success on-error]}]
     (try (-> db-connection
              (db/run expr)
              (j/call :then #(when on-success (dispatch (conj on-success %))))
              (j/call :catch #(when on-error (dispatch (conj on-error %)))))
          (catch js/Error e
            (js/console.warn e)))))
  (reg-event-fx
   :cella/run
   (fn [_ [_ expr {:keys [on-success on-error]}]]
     {:cella/run {:expr expr
                  :on-success on-success
                  :on-error on-error}})))
