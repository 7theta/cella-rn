;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(defproject com.7theta/cella-rn "0.1.0"
  :description "A library to wrap React Native Async Storage"
  :url "https://github.com7theta/cella-rn"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[com.7theta/utilis "1.7.1"]
                 [re-frame "0.10.9"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.10.1"]
                                  [org.clojure/clojurescript "1.10.520"]
                                  [com.google.javascript/closure-compiler-unshaded "v20190929"]
                                  [org.clojure/google-closure-library "0.0-20190213-2033d5d9"]]}}
  :scm {:name "git"
        :url "https://github.com/7theta/cella-rn"})