(ns lambdakube.util-test
  (:require [midje.sweet :refer :all]
            [lambdakube.core :as lk]
            [lambdakube.util :as lku]
            [clojure.data.json :as json]
            [clojure.java.shell :as sh]))

;; # Clojure Nanoservices

;; It is sometimes useful to create a "nanoservice", a service
;; consisting of only a few lines of code, from within a Lambda-Kube
;; library. Such nanoservices can be useful as it avoids the need for
;; building a dedicated Docker image. However, it is not meant to be
;; used for more than small and simple nanoservices (and tests), since
;; the code is written as a quoted s-expression, hindering the ability
;; to test it within the enclosing project.

;; The `add-clj-container` function careates a Clojure nanoservice in
;; a container, and adds it to a pod. It takes the following arguments:
;; 1. The pod to augment.
;; 2. The name of the container to create.
;; 3. A leiningen-style dependencies vector (quoted).
;; 4. A vector containing the nanoservice code (quoted).

;; The code should start with the `ns` macro, where the namespace
;; should always be `main`. A `-main` function needs to be implemented
;; as the nanoservice's entry-point.

;; The function adds a container based on the official `clojure` image
;; to the pod, and mounts two files to it, using a config-map. The
;; first file is a `project.clj` file, which contains the dependencies
;; and names the `main` namespace as the project's main
;; entry-point. The second file is `src/main.clj`, which contains the
;; nanoservice's code.
(fact
 (-> (lk/pod :foo {:app :foo})
     (lku/add-clj-container :bar
                            '[[org.clojure/clojure "1.9.0"]
                              [aysylu/loom "1.0.1"]]
                            '[(ns main
                                (:require [clojure.string :as str]))
                              (defn -main []
                                (println "Hello, World"))]))
 => (let [proj (pr-str '(defproject myproj "0.0.1-SNAPSHOT"
                          :dependencies [[org.clojure/clojure "1.9.0"]
                                         [aysylu/loom "1.0.1"]]
                          :main main))
          code (str (pr-str '(ns main
                               (:require [clojure.string :as str])))
                    "\n"
                    (pr-str '(defn -main []
                                (println "Hello, World"))))]
      (-> (lk/pod :foo {:app :foo})
          (lk/add-container :bar "clojure:lein-2.8.1")
          (lk/add-files-to-container :bar :bar-clj "/src"
                                     {"project.clj" proj
                                      "src/main.clj" code})
          (lk/update-container :bar assoc :command
                               ["sh" "-c" "cp -r /src /work && cd /work && lein run"]))))

;; `add-clj-container` takes optional keyword parameters to further
;; customize the end result. `:source-file` determines the souce file
;; to be used (defaults to `src/main.clj`). It needs to be coordinated
;; with the name given to the `ns` macro. `:proj` takes a map to be
;; merged to the `project.clj` definition (defaults to an empty
;; map). `:lein` contains to the `lein` command (defaults to `run`).
(fact
 (-> (lk/pod :foo {:app :foo})
     (lku/add-clj-container :bar
                            '[[org.clojure/clojure "1.9.0"]
                              [aysylu/loom "1.0.1"]]
                            '[(ns foo
                                (:require [clojure.string :as str]))
                              (defn -main []
                                (println "Hello, World"))]
                            :source-file "src/foo.clj"
                            :proj {:main 'foo}
                            :lein "trampoline run"))
 => (let [proj (pr-str '(defproject myproj "0.0.1-SNAPSHOT"
                          :dependencies [[org.clojure/clojure "1.9.0"]
                                         [aysylu/loom "1.0.1"]]
                          :main foo))
          code (str (pr-str '(ns foo
                               (:require [clojure.string :as str])))
                    "\n"
                    (pr-str '(defn -main []
                                (println "Hello, World"))))]
      (-> (lk/pod :foo {:app :foo})
          (lk/add-container :bar "clojure:lein-2.8.1")
          (lk/add-files-to-container :bar :bar-clj "/src"
                                     {"project.clj" proj
                                      "src/foo.clj" code})
          (lk/update-container :bar assoc :command
                               ["sh" "-c" "cp -r /src /work && cd /work && lein trampoline run"]))))

;; ## Clojure Test Containers

;; One of the most important uses of Clojure nanoservices is in
;; testing, e.g., in conjunction with our [testing
;; framework](testing.md). To facilitate this, we provide two functions
;; to help use two Clojure test frameworks.

;; `add-clj-test-container` adds a container based on
;; `clojure.test`. Like `add-clj-container`, it takes a pod, a name
;; for the new container, a vector of dependencies, and a vector of
;; s-expressions, which in its case should include `testing`
;; expressions. The `ns` should be `main-test`.
(fact
 (-> (lk/pod :foo {:tests :foo})
     (lku/add-clj-test-container :test
                                 '[[org.clojure/clojure "1.9.0"]]
                                 '[(ns main-test
                                     (:require [clojure.test :refer :all]))
                                   (deftest one-equals-two
                                     (is (= 1 2)))]))
 => (-> (lk/pod :foo {:tests :foo})
        (lku/add-clj-container :test
                               '[[org.clojure/clojure "1.9.0"]]
                               '[(ns main-test
                                   (:require [clojure.test :refer :all]))
                                   (deftest one-equals-two
                                     (is (= 1 2)))]
                               :source-file "test/main_test.clj"
                               :lein "test")))

;; The second test framework is
;; [Midje](https://github.com/marick/Midje), supported by the
;; `add-midje-container` function.
(fact
 (-> (lk/pod :foo {:tests :foo})
     (lku/add-midje-container :test
                              '[[org.clojure/clojure "1.9.0"]]
                              '[(ns main-test
                                  (:use midje.sweet))
                                (fact
                                 1 => 2)]))
 => (-> (lk/pod :foo {:tests :foo})
        (lku/add-clj-container :test
                               '[[org.clojure/clojure "1.9.0"]]
                               '[(ns main-test
                                   (:use midje.sweet))
                                 (fact
                                  1 => 2)]
                               :source-file "test/main_test.clj"
                               :lein "midje"
                               :proj {:profiles {:dev {:dependencies '[[midje "1.9.2"]]
                                                       :plugins '[[lein-midje "3.2.1"]]}}})))

;; # Startup Ordering

;; Often, when one pod depends some service, we wish for it to waits
;; until the service starts and opens its sockets. Otherwise, the
;; dependent pod may try to connect to the service and fail. It will
;; be retried until the service is up, but Kubernetes will apply
;; backoff delays, which will make startup time longer than necessary,
;; and the logs dirtier than necessary.

;; To facilitate a clean startup, the `wait-for-service-port` function
;; adds an init container to a given pod, to make sure the pod waits
;; for the service to be available before starting the main
;; container(s). In addition to the pod to be augmented, it takes a
;; dependency description of a service, and the name of a port to wait
;; for.
(defn module1 [$]
  (-> $
      ;; A dependency service
      (lk/rule :some-service [:some-dep]
               (fn [some-dep]
                 (-> (lk/pod :some-service {:foo some-dep})
                     (lk/add-container :quux "some-image")
                     (lk/deployment 3)
                     (lk/expose-cluster-ip :some-service
                                           (lk/port :quux :web 80 80)))))
      ;; A dependent pod
      (lk/rule :some-pod [:some-service]
               (fn [some-service]
                 (-> (lk/pod :some-pod {:foo :baz})
                     (lku/wait-for-service-port some-service :web))))))

;; It creates a [BusyBox](https://busybox.net/) container, which
;; uses [NetCat](https://en.wikipedia.org/wiki/Netcat) to poll the
;; necessary port.
(fact
 (-> (lk/injector)
     (lk/standard-descs)
     (module1)
     (lk/get-deployable {:some-dep :bar})
     (last))
 => (-> (lk/pod :some-pod {:foo :baz})
        (lk/add-init-container :wait-for-some-service-web
                               "busybox"
                               {:command ["sh" "-c" "while ! nc -z some-service 80; do sleep 1; done"]})))


