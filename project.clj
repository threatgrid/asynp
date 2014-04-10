(defproject asynp "0.0.1"
  :description "A core.async library for efficient subprocess management"
  :url "http://github.com/threatgrid/asynp"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :lein-release {:deploy-via :clojars}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.async "0.1.278.0-76b25b-alpha"]
                 [com.taoensso/timbre "3.1.6"]
                 [com.zaxxer/nuprocess "0.9.1"] ; note: when released, 0.9.2 will be significantly preferable due to a pending bugfix
                 ])
