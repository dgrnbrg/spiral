(defproject spiral "0.1.1-SNAPSHOT"
  :description "core.async based http server library"
  :url "http://github.com/dgrnbrg/spiral"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :jvm-opts ^:replace ["-Xmx4g" "-server"]
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/tools.logging "0.2.6"]
                 [org.clojure/data.priority-map "0.0.5"]
                 [ring "1.2.2"]
                 [org.clojure/core.async "0.1.303.0-886421-alpha"]]
  :profiles {:dev {:dependencies [[ring/ring-jetty-adapter "1.3.1"]
                                  [hiccup "1.0.5"]
                                  [compojure "1.1.7"]
                                  [clj-time "0.4.4"]
                                  [http-kit "2.1.19"]]}})
