(ns lambdakube.util-test
  (:require [midje.sweet :refer :all]
            [lambdakube.core :as lk]
            [lambdakube.util :as lku]
            [clojure.data.json :as json]
            [clojure.java.shell :as sh]
            [clojure.java.io :as io]
            [digest]))

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
;; 4. A map of constants, to be passed to the code as `def`ined variables. Designed to be limited to [EDN](https://github.com/edn-format/edn)-compatible values.
;; 5. A vector containing the nanoservice code (quoted).

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
                            {:hello "Hello"
                             :world "World"}
                            '[(ns main
                                (:require [clojure.string :as str]))
                              (defn -main []
                                (println (str hello ", " world)))]))
 => (let [proj (pr-str '(defproject myproj "0.0.1-SNAPSHOT"
                          :dependencies [[org.clojure/clojure "1.9.0"]
                                         [aysylu/loom "1.0.1"]]
                          :main main))
          code (str (pr-str '(ns main
                               (:require [clojure.string :as str])))
                    "\n"
                    (pr-str '(def hello "Hello"))
                    "\n"
                    (pr-str '(def world "World"))
                    "\n"
                    (pr-str '(defn -main []
                               (println (str hello ", " world)))))]
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
                            {}
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
;; for the new container, a vector of dependencies, a map of
;; constants, and a vector of s-expressions, which in its case should
;; include `testing` expressions. The `ns` should be `main-test`.
(fact
 (-> (lk/pod :foo {:tests :foo})
     (lku/add-clj-test-container :test
                                 '[[org.clojure/clojure "1.9.0"]]
                                 {:expected 2}
                                 '[(ns main-test
                                     (:require [clojure.test :refer :all]))
                                   (deftest one-equals-two
                                     (is (= 1 expected)))]))
 => (-> (lk/pod :foo {:tests :foo})
        (lku/add-clj-container :test
                               '[[org.clojure/clojure "1.9.0"]]
                               {:expected 2}
                               '[(ns main-test
                                   (:require [clojure.test :refer :all]))
                                   (deftest one-equals-two
                                     (is (= 1 expected)))]
                               :source-file "test/main_test.clj"
                               :lein "test")))

;; The second test framework is
;; [Midje](https://github.com/marick/Midje), supported by the
;; `add-midje-container` function.
(fact
 (-> (lk/pod :foo {:tests :foo})
     (lku/add-midje-container :test
                              '[[org.clojure/clojure "1.9.0"]]
                              {:expected 2}
                              '[(ns main-test
                                  (:use midje.sweet))
                                (fact
                                 1 => expected)]))
 => (-> (lk/pod :foo {:tests :foo})
        (lku/add-clj-container :test
                               '[[org.clojure/clojure "1.9.0"]]
                               {:expected 2}
                               '[(ns main-test
                                   (:use midje.sweet))
                                 (fact
                                  1 => expected)]
                               :source-file "test/main_test.clj"
                               :lein "midje"
                               :proj {:profiles {:dev {:dependencies '[[midje "1.9.2"]]
                                                       :plugins '[[lein-midje "3.2.1"]]}}})))

;; ## Building Docker Images

;; One of the down-sides of using Clojure nanoservices is the fact
;; that a nanoservice has to first pull all its dependencies from
;; Maven repositories before starting. This has impact on
;; initialization time (and recovery time, in case a pod crashed and
;; is restarted).

;; To ovecome this problem, `add-clj-container` can optionally build a
;; Docker image, which will include all the nanoservice's
;; dependencies. This moves the dependency installation process to the
;; host machine, and it will be done once per deployment. Furthermore,
;; if the same system is being deployed multiple times, no new image
;; will be built, thanks to Docker's use of content-addressing for its
;; images.

;; The down-side of using Docker images is that they require setup,
;; such as credentials on a Docker repository. To alleviate this
;; problem, we make this feature optional, defaulting to using the
;; standard `clojure` image. We will create a dedicated image for each
;; nanoservice if the dynamic variable `*docker-repo*` is set, with
;; the name of the user's Docker repository, one he or she has `docker
;; push` priveleges to.

;; The macro `with-docker-repo` looks for this prefix in the file
;; `~/.lambda-kube/docker-repo`. If it does not exist, it executes the
;; underlying code without setting `*docker-repo*`.
(fact
 (lku/with-docker-repo
   {:the-value-is lku/*docker-repo*}) => {:the-value-is nil}
 (provided
  (lku/lk-dir) => ..dir..
  (lku/file-exists? (io/file ..dir.. "docker-repo")) => false))

;; If the file exists, its content becomes the value of
;; `*docker-repo*`.
(fact
 (lku/with-docker-repo
   {:the-value-is lku/*docker-repo*}) => {:the-value-is "foo-repo"}
 (provided
  (lku/lk-dir) => ..dir..
  (io/file ..dir.. "docker-repo") => ..file..
  (lku/file-exists? ..file..) => true
  (slurp ..file..) => "foo-repo\n"))

;; When `add-clj-container` received a non-`nil` `*docker-repo*`, it
;; calls `create-clj-image`, providing it the base image and the
;; content of the `project.clj` file. `create-clj-image` returns a
;; tag, which is then used in place of the original `clojure` tag.
(fact
 (binding [lku/*docker-repo* "foo-repo"]
          (-> (lk/pod :foo {:app :foo})
              (lku/add-clj-container :bar
                                     '[[org.clojure/clojure "1.9.0"]
                                       [aysylu/loom "1.0.1"]]
                                     {:hello "Hello"
                                      :world "World"}
                                     '[(ns main
                                         (:require [clojure.string :as str]))
                                       (defn -main []
                                         (println (str hello ", " world)))])))
 => (let [proj (pr-str '(defproject myproj "0.0.1-SNAPSHOT"
                          :dependencies [[org.clojure/clojure "1.9.0"]
                                         [aysylu/loom "1.0.1"]]
                          :main main))
          code (str (pr-str '(ns main
                               (:require [clojure.string :as str])))
                    "\n"
                    (pr-str '(def hello "Hello"))
                    "\n"
                    (pr-str '(def world "World"))
                    "\n"
                    (pr-str '(defn -main []
                               (println (str hello ", " world)))))]
      (-> (lk/pod :foo {:app :foo})
          (lk/add-container :bar ..the-new-tag..)
          (lk/add-files-to-container :bar :bar-clj "/src"
                                     {"project.clj" proj
                                      "src/main.clj" code})
          (lk/update-container :bar assoc :command
                               ["sh" "-c" "cp -r /src /work && cd /work && lein run"])))
 (provided
  (lku/create-clj-image "clojure:lein-2.8.1" '(defproject myproj "0.0.1-SNAPSHOT"
                                                :dependencies [[org.clojure/clojure "1.9.0"]
                                                               [aysylu/loom "1.0.1"]]
                                                :main main)) => ..the-new-tag..))


;; `create-clj-image` calculates a tag based on the contents of
;; `project.clj`. Then it checks if a file of the format
;; `~/.lambda-kube/tag-<tag>` (where `<tag>` is replaced with the
;; coalculated tag) exists. If it does, the function exists, returning
;; the complete image name.
(fact
 (binding [lku/*docker-repo* "foo-repo"]
   (lku/create-clj-image "base-image:123" '(defproject ...)) => "foo-repo/clj-nanoservice:12345"
   (provided
    (digest/sha-256 "(defproject ...)") => ..full-digest..
    (subs ..full-digest.. 0 16) => "12345"
    (lku/file-exists? (io/file (lku/lk-dir) "tag-12345")) => true)))

;; If the file `~/.lambda-kube/tag-<tag>` does not exist,
;; `create-clj-image` creates a temporary directory, generates a
;; `Dockerfile` within it, and writes the given `project.clj` content,
;; calls `docker build` with a tag derived from the contents of
;; `project.clj`, and `docker push`. Finally, it creates a
;; `~/.lambda-kube/tag-<tag>` file to prevent future creations of the
;; same image.
(fact
 (binding [lku/*docker-repo* "foo-repo"]
   (lku/create-clj-image "base-image:123" '(defproject ...)) => "foo-repo/clj-nanoservice:12345"
   (provided
    (digest/sha-256 "(defproject ...)") => ..full-digest..
    (subs ..full-digest.. 0 16) => "12345"
    (lku/file-exists? (io/file (lku/lk-dir) "tag-12345")) => false
    (lku/mk-temp-dir "clj-nano") => ..d..
    (spit (io/file ..d.. "Dockerfile")
          "FROM base-image:123
WORKDIR /src
COPY project.clj .
RUN lein deps") => irrelevant
    (spit (io/file ..d.. "project.clj")
          "(defproject ...)") => irrelevant
    (lku/log "Building image" "foo-repo/clj-nanoservice:12345") => irrelevant
    (lku/sh "docker" "build" "-t" "foo-repo/clj-nanoservice:12345" "." :dir ..d..) => irrelevant
    (lku/log "Pushing image" "foo-repo/clj-nanoservice:12345") => irrelevant
    (lku/sh "docker" "push" "foo-repo/clj-nanoservice:12345" :dir ..d..) => irrelevant
    (spit (io/file (lku/lk-dir) "tag-12345") "") => irrelevant)))


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

;; When the service in question exposes exactly one port, the port
;; name argument can be omitted.
(defn module3 [$]
  (-> $
      (lk/rule :some-service [:some-dep]
               (fn [some-dep]
                 (-> (lk/pod :some-service {:foo some-dep})
                     (lk/add-container :quux "some-image")
                     (lk/deployment 3)
                     (lk/expose-cluster-ip :some-service
                                           (lk/port :quux :web 80 80)))))
      (lk/rule :some-pod [:some-service]
               (fn [some-service]
                 (-> (lk/pod :some-pod {:foo :baz})
                     ;; We omit the :web argument
                     (lku/wait-for-service-port some-service))))))
(fact
 (-> (lk/injector)
     (lk/standard-descs)
     (module3)
     (lk/get-deployable {:some-dep :bar})
     (last))
 => (-> (lk/pod :some-pod {:foo :baz})
        (lk/add-init-container :wait-for-some-service-web
                               "busybox"
                               {:command ["sh" "-c" "while ! nc -z some-service 80; do sleep 1; done"]})))

;; Omitting the port name on a service with more than one port will
;; result in an exception being thrown.
(defn module4 [$]
  (-> $
      (lk/rule :some-service [:some-dep]
               (fn [some-dep]
                 (-> (lk/pod :some-service {:foo some-dep})
                     (lk/add-container :quux "some-image")
                     (lk/deployment 3)
                     (lk/expose-cluster-ip :some-service
                                           (comp (lk/port :quux :web 80 80)
                                                 (lk/port :quux :https 443 443))))))
      (lk/rule :some-pod [:some-service]
               (fn [some-service]
                 (-> (lk/pod :some-pod {:foo :baz})
                     ;; We omit the :web argument
                     (lku/wait-for-service-port some-service))))))
(fact
 (-> (lk/injector)
     (lk/standard-descs)
     (module4)
     (lk/get-deployable {:some-dep :bar}))
 => (throws "Port name must be specified when waiting for a service exposing more than one port."))

;; # InjectTheDriver

;; [InjectTheDriver](https://github.com/brosenan/InjectTheDriver)
;; (ITD) is a framework for injecting drivers (objects acting as
;; clients to services) into JVM-based services.

;; Injection is done in two phases. First, on the _server_ side (the
;; service we would like to provide a driver for), annotations are
;; added to the service, naming the driver class, and a URL to an
;; [uber-jar](https://stackoverflow.com/questions/11947037/what-is-an-uber-jar)
;; that provides it. It is important that the driver class be packaged
;; as an uber-jar, because it is loaded in isolation, without
;; depending on the client's class-path.

;; Second, on the _client_ side (the service that uses the driver to
;; access the server), we take the description map of the server
;; (which includes the annotations and possibly other things), and
;; expose it to the client pod as an environment variable, named after
;; the Java interface the driver is intended to provide. The value of
;; this environment variable is encoded as JSON.

;; The InjectTheDriver framework has a factory method
;; (`DriverFactory.createDriverFor()`), which uses this environment
;; variable to fetch the uber-jar and instantiate a driver object of
;; the correct class. Consequently, the client can be totally agnostic
;; of the server implementation, getting a driver "out of thin air".

;; ## Server-Side

;; On the server-side, we need to add two annotations: `:jar`,
;; specifying the URL of the driver's uber-jar to be used, and
;; `:class`, specifying the fully-qualified name of the class.

;; The function `add-itd-annotations` takes a pod, a class, and the
;; URL of the driver uberjar.

;; It assigns the `:class` annotation with the fully-qualified name of
;; the given class, and sets the `:jar` annotation to be the given
;; URL.
(fact
 (-> (lk/pod :foo {})
     (lku/add-itd-annotations java.util.Map "http://foo.com/bar.jar"))
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:labels {}
                :name :foo
                :annotations {:class "java.util.Map"
                              :jar "http://foo.com/bar.jar"}}
     :spec {}})

;; ## Client-Side

;; On the client-side, we need to inject an environment variable, one
;; that `DriverFactory.createDriverFor()` can use to instantiate our class.

;; The name of this environment variable is based on the name of the
;; Java interface it implements, with the dots converted to
;; underscores, and all letters are capitalized. The contents is a
;; JSON representation of the description map of the server.

;; The `inject-driver` function takes a container, a Java interface
;; and a dependency description, and adds an environment variable as
;; described above to the container.

;; Consider for example the following module, defining a rule for a
;; "server" and a "client" of that server.
(defn module2 [$]
  (-> $
      (lk/rule :server []
               (fn []
                 (-> (lk/pod :server {})
                     (lk/add-container :srv "some-image")
                     (lk/deployment 3)
                     (lku/add-itd-annotations java.util.HashMap "http://foo.com/bar.jar")
                     (lk/expose-cluster-ip :server
                                           (lk/port :srv :web 80 80)))))
      (lk/rule :client [:server]
               (fn [server]
                 (-> (lk/pod :client {})
                     (lk/add-container :client "some-image"
                                       (-> {}
                                           (lku/inject-driver java.util.Map server))))))))

;; When applying dependency injection, the client gets initialized
;; with an environment variable named `JAVA_UTIL_MAP`, containing
;; a JSON representation of the server's description.
(fact
 (-> (lk/injector)
     (lk/standard-descs)
     (module2)
     (lk/get-deployable {})
     (last))
 => (-> (lk/pod :client {})
        (lk/add-container :client "some-image"
                          (-> {}
                              (lk/add-env {:JAVA_UTIL_MAP (json/write-str {:class "java.util.HashMap"
                                                                           :jar "http://foo.com/bar.jar"
                                                                           :hostname "server"
                                                                           :ports {:web 80}})})))))
