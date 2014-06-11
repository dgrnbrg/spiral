(defproject async-ring "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :jvm-opts ^:replace ["-Xmx4g" "-server"]
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [http-kit "2.1.16"]
                 [org.clojure/tools.logging "0.2.6"]
                 [ring "1.2.2"]
                 [compojure "1.1.7"]
                 [hiccup "1.0.5"]
                 [org.clojure/core.async "0.1.303.0-886421-alpha"]])
