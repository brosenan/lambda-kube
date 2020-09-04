(defproject brosenan/lambdakube "0.10.1-SNAPSHOT"
  :description "A Clojure library for generating Kubernetes API objects "
  :url "https://github.com/brosenan/lambda-kube"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [io.forward/yaml "1.0.10"]
                 [aysylu/loom "1.0.2"]
                 [org.clojure/data.json "1.0.0"]
                 [org.clojure/data.xml "0.0.8"]
                 [digest "1.4.9"]]
  :profiles {:dev {:dependencies [[midje "1.9.9"]]
                   :plugins [[lein-midje "3.2.1"]
                             [lein-auto "0.1.3"]]}}
  :deploy-repositories [["releases" :clojars]])
