(ns lambdakube.testing-test
  (:require [midje.sweet :refer :all]
            [lambdakube.testing :as lkt]
            [lambdakube.core :as lk]
            [lambdakube.util :as lku]
            [lambdakube.util-test :as lkut]
            [clojure.data.json :as json]
            [clojure.data.xml :as xml]
            [clojure.java.shell :as sh]))

;; Lambda-Kube brings software to the cluster level. This raises an
;; important question: _How do we test cluster-level software_? In
;; this section we try to answer this question.

;; ## What are we Testing?

;; The first thing we would like to test at the cluster level, we
;; would like to test _integrations_ between components. We trust that
;; each component (e.g., microservice) is tested by itself, but at the
;; cluster level we would like to see that the assumption one
;; microservice makes on all others are sound.

;; However, this is not enough. As we moved the cluster level to
;; become software by itself, we need to test that software. We need
;; to see that the Kubernetes objects we create are indeed valid
;; (Lambda-Kube does not replicate Kubernetes's validation logic, so
;; it is possible to create illegal Kubernetes objects). Moreover, we
;; would like to see that the way we configured these objects provides
;; the correct functionality. For example, we would like to know we
;; have exposed the correct ports, that we wait for the right
;; services, etc.

;; # Test Support in Lambda-Kube

;; Lambda-Kube's testing support consists of two parts. A `test`
;; function, used to register tests from within
;; [modules](core.md#dependency-injection), and a `run-tests`
;; function, which uses `kubectl` to run all tests, each within its
;; own namespace, and return the results.

;; # Defining Tests

;; The `test` function adds a test definition to the given
;; injector. It takes the following paramters:
;; 1. An injector (named `$` by convention).
;; 2. A name for the test (a keyword, needs to be unique within the same injector).
;; 3. A configuration map for building the system under test.
;; 4. A list of dependencies (as in a `rule`).
;; 5. A function which takes the dependencies as arguments and constructs a pod for running the test.
;; `test` returns an injector with a new test and a new rule.
(fact
 (let [$ (-> (lk/injector)
             (lkt/test :my-test
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
     ;; The pod is wrapped with a job named `:test`
     (func :FOO :BAR) => (-> (lk/pod :test {:foo :FOO
                                            :bar :BAR})
                             ;; We wrap the given pod with a job, and
                             ;; make sure it never restarts on
                             ;; failures. This applies both to the pod
                             ;; restarting itself, and the job
                             ;; creating a new pod.
                             (lk/job :Never {:backoffLimit 0})))))

;; ## Running a Single Test

;; The function `run-test` takes an injector, a name of a test
;; (keyword), and a prefix (string) as parameters, and runs a single
;; test in its own namespace.

;; Consider for example `module2`, which defines a test for the
;; service defined in `module1` (see [here](util.md#startup-ordering)).
(defn module2 [$]
  (-> $
      (lkt/test :my-test {:some-dep :goo}
                [:some-service]
                (fn [some-service]
                  (-> (lk/pod :this-name-does-not-matter {})
                      (lku/add-clj-container :cont
                                             '[[org.clojure/clojure "1.9.0"]]
                                             '[(ns main)
                                               (defn -main []
                                                 (println "Hello, World"))])
                      (lku/wait-for-service-port some-service :web))))))

;; Now, to execute this test we need to create an injector and
;; register both modules. Then we call `run-test` on this injector.
(fact
 (let [$ (-> (lk/injector)
             (lkut/module1)
             (module2)
             (lk/standard-descs))]
   (lkt/run-test $ :my-test "foo") => {:log "this is the log"
                                       :status :pass}
   (provided
    ;; Creation of the namespace
    (lkt/log "Creating namespace foo-my-test") => nil
    (sh/sh "kubectl" "create" "ns" "foo-my-test") => {:exit 0}
    ;; Creation of the YAML file for the test setup
    (lk/get-deployable $ {:some-dep :goo}) => ..deployable..
    (lk/to-yaml ..deployable..) => ..yaml..
    (spit "foo-my-test.yaml" ..yaml..) => nil
    ;; Apply the YAML within the namespace
    (lkt/log "Deploying test :my-test") => nil
    (sh/sh "kubectl" "-n" "foo-my-test" "apply" "-f" "foo-my-test.yaml") => {:exit 0}
    ;; Polling for the job status. In this scenario, the job is active
    ;; for two iterations, and then becomes completed.
    (sh/sh "kubectl" "-n" "foo-my-test" "get" "job" "test" "-o" "json")
    =streams=> [{:exit 0
                 :out (json/write-str {:status {:active 1}})}
                {:exit 0
                 :out (json/write-str {:status {:active 1}})}
                {:exit 0
                 :out (json/write-str {:status {:succeeded 1}})}] :times 3
    ;; Collect the logs
    (sh/sh "kubectl" "-n" "foo-my-test" "logs" "-ljob-name=test")
    => {:exit 0
        :out "this is the log"}
    (lkt/log "Test :my-test completed. Status: :pass") => nil
    ;; Delete the namespace
    (lkt/log "Deleting namespace foo-my-test") => nil
    (sh/sh "kubectl" "delete" "ns" "foo-my-test") => {:exit 0})))

;; If `kubectl` returns a non-zero exit code, an exception is thrown,
;; taking the content of the standard error as the exception message.
(fact
 (let [$ (-> (lk/injector)
             (lkut/module1)
             (module2)
             (lk/standard-descs))]
   (lkt/run-test $ :my-test "foo") => (throws "This is an error")
   (provided
    (spit "foo-my-test.yaml" irrelevant) => nil
    (lkt/log "Creating namespace foo-my-test") => nil
    (sh/sh "kubectl" "create" "ns" "foo-my-test") => {:exit 22
                                                      :err "This is an error"})))

;; If the job fails, we return a `:fail` status
(fact
 (let [$ (-> (lk/injector)
             (lkut/module1)
             (module2)
             (lk/standard-descs))]
   (lkt/run-test $ :my-test "foo") => {:log "this is the log"
                                       :status :fail}
   (provided
    (lkt/log "Creating namespace foo-my-test") => nil
    (sh/sh "kubectl" "create" "ns" "foo-my-test") => {:exit 0}
    (lk/get-deployable $ {:some-dep :goo}) => ..deployable..
    (lk/to-yaml ..deployable..) => ..yaml..
    (spit "foo-my-test.yaml" ..yaml..) => nil
    (lkt/log "Deploying test :my-test") => nil
    (sh/sh "kubectl" "-n" "foo-my-test" "apply" "-f" "foo-my-test.yaml") => {:exit 0}
    ;; After the job is not active anymore, it fails.
    (sh/sh "kubectl" "-n" "foo-my-test" "get" "job" "test" "-o" "json")
    =streams=> [{:exit 0
                 :out (json/write-str {:status {:active 1}})}
                {:exit 0
                 :out (json/write-str {:status {:active 1}})}
                {:exit 0
                 :out (json/write-str {:status {:failed 1}})}] :times 3
    (sh/sh "kubectl" "-n" "foo-my-test" "logs" "-ljob-name=test")
    => {:exit 0
        :out "this is the log"}
    (lkt/log "Test :my-test completed. Status: :fail") => nil
    ;; If the test fails, we do not delete the namespace to allow
    ;; investigation of the root cause.    
    (sh/sh "kubectl" "delete" "ns" "foo-my-test") => {:exit 0} :times 0)))

;; # Running All Tests

;; The function `run-tests` runs some or all registered tests. It
;; takes an injector and a prefix, and iterates over all the tests it
;; contains. It then calls `run-test` on each one.
(fact
 (let [$ {:tests {:foo {:foo :config}
                  :bar {:bar :config}}}]
   (lkt/run-tests $ ..prefix..) => {:foo ..foores..
                                    :bar ..barres..}
   (provided
    (lkt/run-test $ :foo ..prefix..) => ..foores..
    (lkt/run-test $ :bar ..prefix..) => ..barres..)))

;; An optional third parameter is a predicate on the configuration.
(fact
 (let [$ {:tests {:foo {:foo :config}
                  :bar {:bar :config}}}]
   (lkt/run-tests $ ..prefix.. #(contains? % :foo)) => {:foo ..foores..}
   (provided
    (lkt/run-test $ :foo ..prefix..) => ..foores..)))


;; # Writing Test Results

;; To facilitate integration with external CI tools, Lambda-Kube's
;; testing framework is able to produce [XUnit-like XML result
;; files](https://xunit.github.io/docs/format-xml-v2).

;; The function `to-xunit` takes a results map, as returned by
;; `run-tests`, and generates an XML file, to be written to the given
;; file name.
(fact
 (let [res {:foo {:log "Log for foo"
                  :status :pass}
            :bar {:log "Log for bar"
                  :status :fail}}]
   (lkt/to-xunit res "res.xml") => nil
   (provided
    (xml/sexp-as-element [:assemblies
                          [:assembly {:name "lambda-kube tests"
                                      :passed 1
                                      :failed 1}
                           [:collection {:name "tests"
                                         :passed 1
                                         :failed 1}
                            [:test {:name "foo"
                                    :result "Pass"}]
                            [:test {:name "bar"
                                    :result "Fail"}]]]]) => ..xml..
    (xml/indent-str ..xml..) => ..str..
    (spit "res.xml" ..str..) => nil)))

;; # Top-Level Function

;; `kube-tests` takes an injector, a prefix and (optionally) a
;; predicate function, and does the following:
;; 1. Calls `run-tests` to execute all registered tests (filtered by the predicate function if provided).
;; 2. Calls `to-xunit` with the result, to write a results file named `<prefix>-results.xml`.
;; 3. Returns `true` if all tests have passed, or `false` otherwise.
(fact
 (let [$ {:tests {:foo {:foo :config}
                  :bar {:bar :config}}}]
   (lkt/kube-tests $ "prefix") => false
   (provided
    (lkt/run-tests $ "prefix" (constantly true))
    => {:foo {:status :pass}
        :bar {:status :fail}}
    (lkt/to-xunit {:foo {:status :pass}
                   :bar {:status :fail}} "prefix-results.xml") => nil)

   (lkt/kube-tests $ "prefix" ..some-filter..) => true
   (provided
    (lkt/run-tests $ "prefix" ..some-filter..)
    => {:foo {:status :pass}}
    (lkt/to-xunit {:foo {:status :pass}} "prefix-results.xml") => nil)))
