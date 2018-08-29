* [Clojure Nanoservices](#clojure-nanoservices)
* [Startup Ordering](#startup-ordering)
* [Testing](#testing)
  * [What are we Testing?](#what-are-we-testing?)
  * [Test Support in Lambda-Kube](#test-support-in-lambda-kube)
  * [Defining Tests](#defining-tests)
  * [Running Tests](#running-tests)
```clojure
(ns lambdakube.util-test
  (:require [midje.sweet :refer :all]
            [lambdakube.core :as lk]
            [lambdakube.util :as lku]))

```
# Clojure Nanoservices

It is sometimes useful to create a "nanoservice", a service
consisting of only a few lines of code, from within a Lambda-Kube
library. Such nanoservices can be useful as it avoids the need for
building a dedicated Docker image. However, it is not meant to be
used for more than small and simple nanoservices (and tests), since
the code is written as a quoted s-expression, hindering the ability
to test it within the enclosing project.

The `add-clj-container` function careates a Clojure nanoservice in
a container, and adds it to a pod. It takes the following arguments:
1. The pod to augment.
2. The name of the container to create.
3. A leiningen-style dependencies vector (quoted).
4. A vector containing the nanoservice code (quoted).

The code should start with the `ns` macro, where the namespace
should always be `main`. A `-main` function needs to be implemented
as the nanoservice's entry-point.

The function adds a container based on the official `clojure` image
to the pod, and mounts two files to it, using a config-map. The
first file is a `project.clj` file, which contains the dependencies
and names the `main` namespace as the project's main
entry-point. The second file is `src/main.clj`, which contains the
nanoservice's code.
```clojure
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

```
# Startup Ordering

Often, when one pod depends some service, we wish for it to waits
until the service starts and opens its sockets. Otherwise, the
dependent pod may try to connect to the service and fail. It will
be retried until the service is up, but Kubernetes will apply
backoff delays, which will make startup time longer than necessary,
and the logs dirtier than necessary.

To facilitate a clean startup, the `wait-for-service-port` function
adds an init container to a given pod, to make sure the pod waits
for the service to be available before starting the main
container(s). In addition to the pod to be augmented, it takes a
dependency description of a service, and the name of a port to wait
for.
```clojure
(defn module1 [$]
  (-> $
      ;; A dependency service
      (lk/rule :some-service []
               (fn []
                 (-> (lk/pod :some-service {:foo :bar})
                     (lk/add-container :quux "some-image")
                     (lk/deployment 3)
                     (lk/expose-cluster-ip :some-service
                                           (lk/port :quux :web 80 80)))))
      ;; A dependent pod
      (lk/rule :some-pod [:some-service]
               (fn [some-service]
                 (-> (lk/pod :some-pod {:foo :baz})
                     (lku/wait-for-service-port some-service :web))))))

```
It creates a [BusyBox](https://busybox.net/) container, which
uses [NetCat](https://en.wikipedia.org/wiki/Netcat) to poll the
necessary port.
```clojure
(fact
 (-> (lk/injector)
     (lk/standard-descs)
     (module1)
     (lk/get-deployable {})
     (last))
 => (-> (lk/pod :some-pod {:foo :baz})
        (lk/add-init-container :wait-for-some-service-web
                               "busybox"
                               {:command ["nc" "-z" :some-service "80"]})))


```
# Testing

Lambda-Kube brings software to the cluster level. This raises an
important question: _How do we test cluster-level software_? In
this section we try to answer this question.

## What are we Testing?

The first thing we would like to test at the cluster level, we
would like to test _integrations_ between components. We trust that
each component (e.g., microservice) is tested by itself, but at the
cluster level we would like to see that the assumption one
microservice makes on all others are sound.

However, this is not enough. As we moved the cluster level to
become software by itself, we need to test that software. We need
to see that the Kubernetes objects we create are indeed valid
(Lambda-Kube does not replicate Kubernetes's validation logic, so
it is possible to create illegal Kubernetes objects). Moreover, we
would like to see that the way we configured these objects provides
the correct functionality. For example, we would like to know we
have exposed the correct ports, that we wait for the right
services, etc.

## Test Support in Lambda-Kube

Lambda-Kube's testing support consists of two parts. A `test`
function, used to register tests from within
[modules](core.md#dependency-injection), and a `run-tests`
function, which uses `kubectl` to run all tests, each within its
own namespace, and return the results.

## Defining Tests

The `test` function adds a test definition to the given
injector. It takes the following paramters:
1. An injector (named `$` by convention).
2. A name for the test (a keyword, needs to be unique within the same injector).
3. A configuration map for building the system under test.
4. A list of dependencies (as in a `rule`).
5. A function which takes the dependencies as arguments and constructs a pod for running the test.
`test` returns an injector with a new test and a new rule.
```clojure
(fact
 (let [$ (-> (lk/injector)
             (lku/test :my-test
                       {:some :config}
                       [:foo :bar]
                       (fn [foo bar]
                         (lk/pod :my-own-name-for-this-pod
                                 {:foo foo
                                  :bar bar}))))]
   (:tests $) => {:my-test {:some :config}}
   (let [[[func deps res]] (:rules $)]
     deps => [:foo :bar]
     res => :my-test
     ;; The pod name is overriden with :test,
     ;; and is wrapped with a job.
     (func :FOO :BAR) => (-> (lk/pod :test {:foo :FOO
                                            :bar :BAR})
                             (lk/job :Never)))))

```
## Running Tests

The function `run-test` takes an injector, a name of a test
(keyword), and a prefix (string) as parameters, and runs a single
test in its own namespace.

`run-test` makes the following calls to `kubectl`:
1. `kubectl create ns` with the namespace name, consisting of the given prefix and the test name, separated by a dash.
2. `kubectl -n <the namespace> create -f` with the `.yaml` files produced from applying the test configuration to the injector.
3. Polling the job for completion: `kubectl -n <the namespace> get job test -o json`
4. Upon completion: `kubectl -n <the namespace> logs -ljob-name=test` to collect the test logs.
5. When successful: `kubectl delete ns <the namespace>`.
Note that we do not automatically remove failed namespaces, to allow investigation.
