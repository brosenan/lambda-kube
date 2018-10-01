(defproject brosenan/lambdakube "0.9.1"
  :description "A Clojure library for generating Kubernetes API objects "
  :url "https://github.com/brosenan/lambda-kube"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [io.forward/yaml "1.0.9"]
                 [aysylu/loom "1.0.1"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/data.xml "0.0.8"]
                 [digest "1.4.8"]]
  :profiles {:dev {:dependencies [[midje "1.9.2"]]
                   :plugins [[lein-midje "3.2.1"]
                             [lein-auto "0.1.3"]]}}
  :deploy-repositories [["releases" :clojars]])
