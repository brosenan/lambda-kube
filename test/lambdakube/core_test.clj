(ns lambdakube.core-test
  (:require [midje.sweet :refer :all]
            [lambdakube.core :as lkb]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]))

;; # Basic API Object Functions

;; The following functions create basic API objects.

;; The `pod` function creates a pod with no containers.
(fact
 (lkb/pod :foo {:app :bar})
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:name :foo
                :labels {:app :bar}}
     :spec {}})

;; `pod` can take a third argument with additional spec parameters.
(fact
 (lkb/pod :foo {:app :bar} {:foo :bar})
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:name :foo
                :labels {:app :bar}}
     :spec {:foo :bar}})

;; The `deployment` function creates a deployment, based on the given
;; pod as template. The deployment takes its name from the given pod,
;; and removes the name from the template.
(fact
 (-> (lkb/pod :foo {:bar :baz})
     (lkb/add-container :bar "some-image")
     (lkb/deployment 3))
 => {:apiVersion "apps/v1"
     :kind "Deployment"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 3
            :selector
            {:matchLabels {:bar :baz}}
            :template
            {:metadata
             {:labels {:bar :baz}}
             :spec
             {:containers
              [{:name :bar
                :image "some-image"}]}}}})

;; The `stateful-set` function wraps the given pod with a Kubernetes
;; stateful set.
(fact
 (-> (lkb/pod :foo {:bar :baz})
     (lkb/add-container :bar "some-image")
     (lkb/stateful-set 5))
 => {:apiVersion "apps/v1"
     :kind "StatefulSet"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 5
            :selector
            {:matchLabels {:bar :baz}}
            :serviceName :foo
            :template
            {:metadata
             {:labels {:bar :baz}}
             :spec
             {:containers
              [{:name :bar
                :image "some-image"}]}}
            :volumeClaimTemplates []}})

;; # Modifier Functions

;; The following functions augment basic API objects by adding
;; content. They always take the API object as a first argument.

;; ## Add Functions

;; The `add-container` function adds a container to a pod. The
;; function takes the container name and the image to be used as
;; explicit parameters, and an optional map with additional parameters.
(fact
 (-> (lkb/pod :foo {})
     (lkb/add-container :bar "bar-image" {:ports [{:containerPort 80}]}))
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:name :foo
                :labels {}}
     :spec {:containers [{:name :bar
                          :image "bar-image"
                          :ports [{:containerPort 80}]}]}})

;; `add-env` augments the parameters of a _container_, and adds an
;; environment variable binding.
(fact
 (-> (lkb/pod :foo {})
     (lkb/add-container :bar "bar-image" (-> {:ports [{:containerPort 80}]}
                                             (lkb/add-env {:FOO "BAR"}))))
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:name :foo
                :labels {}}
     :spec {:containers [{:name :bar
                          :image "bar-image"
                          :ports [{:containerPort 80}]
                          :env [{:name :FOO
                                 :value "BAR"}]}]}})

;; If an `:env` key already exists, new entries are added to the list.
(fact
 (-> (lkb/pod :foo {})
     (lkb/add-container :bar "bar-image" (-> {:env [{:name :QUUX :value "TAR"}]}
                                             (lkb/add-env {:FOO "BAR"}))))
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:name :foo
                :labels {}}
     :spec {:containers [{:name :bar
                          :image "bar-image"
                          :env [{:name :QUUX
                                 :value "TAR"}
                                {:name :FOO
                                 :value "BAR"}]}]}})

;; `add-init-container` adds a new [init container](https://kubernetes.io/docs/concepts/workloads/pods/init-containers/) to a pod.
(fact
 (-> (lkb/pod :foo {})
     (lkb/add-init-container :bar "my-image:tag"))
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:name :foo
                :labels {}}
     :spec {:initContainers [{:name :bar
                              :image "my-image:tag"}]}}

 ;; And with additional params...
 (-> (lkb/pod :foo {})
     (lkb/add-init-container :bar "my-image:tag" {:other :params}))
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:name :foo
                :labels {}}
     :spec {:initContainers [{:name :bar
                              :image "my-image:tag"
                              :other :params}]}})

;; The `add-volume-claim-template` function takes a stateful-set, adds
;; a volume claim template to its spec and mounts it to the given
;; paths within the given containers.
(fact
 (-> (lkb/pod :foo {:bar :baz})
     (lkb/add-container :bar "some-image")
     (lkb/add-container :baz "some-other-image")
     (lkb/stateful-set 5 {:additional-arg 123})
     (lkb/add-volume-claim-template :vol-name
                                    ;; Spec
                                    {:accessModes ["ReadWriteOnce"]
                                     :storageClassName :my-storage-class
                                     :resources {:requests {:storage "1Gi"}}}
                                    ;; Mounts
                                    {:bar "/var/lib/foo"}))
 => {:apiVersion "apps/v1"
     :kind "StatefulSet"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 5
            :selector
            {:matchLabels {:bar :baz}}
            :serviceName :foo
            :template
            {:metadata
             {:labels {:bar :baz}}
             :spec
             {:containers
              [{:name :bar
                :image "some-image"
                :volumeMounts
                [{:name :vol-name
                  :mountPath "/var/lib/foo"}]}
               {:name :baz
                :image "some-other-image"}]}}
            :volumeClaimTemplates
            [{:metadata {:name :vol-name}
              :spec {:accessModes ["ReadWriteOnce"]
                     :storageClassName :my-storage-class
                     :resources {:requests {:storage "1Gi"}}}}]
            :additional-arg 123}})

;; If the `:volumeMounts` entry already exists in the container, the
;; new mount is appended.
(fact
 (-> (lkb/pod :foo {:bar :baz})
     (lkb/add-container :bar "some-image" {:volumeMounts [{:foo :bar}]})
     (lkb/stateful-set 5)
     (lkb/add-volume-claim-template :vol-name
                                    ;; Spec
                                    {:accessModes ["ReadWriteOnce"]
                                     :storageClassName :my-storage-class
                                     :resources {:requests {:storage "1Gi"}}}
                                    ;; Mounts
                                    {:bar "/var/lib/foo"}))
 => {:apiVersion "apps/v1"
     :kind "StatefulSet"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 5
            :selector
            {:matchLabels {:bar :baz}}
            :serviceName :foo
            :template
            {:metadata
             {:labels {:bar :baz}}
             :spec
             {:containers
              [{:name :bar
                :image "some-image"
                :volumeMounts
                [{:foo :bar}
                 {:name :vol-name
                  :mountPath "/var/lib/foo"}]}]}}
            :volumeClaimTemplates
            [{:metadata {:name :vol-name}
              :spec {:accessModes ["ReadWriteOnce"]
                     :storageClassName :my-storage-class
                     :resources {:requests {:storage "1Gi"}}}}]}})

;; ## Update Functions

;; While `add-*` functions are good for creating new API objects, we
;; sometimes need to update existing ones. For example, given a
;; deployment, we sometimes want to add an environment to one of the
;; containers in the template.

;; `update-*` work in a similar manner to Clojure's `update`
;; function. It takes an object to be augmented, an augmentation
;; function which takes the object to update as its first argument,
;; and additional arguments for that function. Then it applies the
;; augmentation function on a portion of the given object, and returns
;; the updated object.

;; `update-template` operates on controllers (deployments,
;; stateful-sets, etc). It takes a pod-modifying function and applies
;; it to the template. For example, we can use it to add a container
;; to a pod already within a deployment.
(fact
 (-> (lkb/pod :foo {:bar :baz})
     (lkb/deployment 3)
     ;; The original pod has no containers. We add one now.
     (lkb/update-template lkb/add-container :bar "some-image"))
 => {:apiVersion "apps/v1"
     :kind "Deployment"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 3
            :selector
            {:matchLabels {:bar :baz}}
            :template
            {:metadata
             {:labels {:bar :baz}}
             :spec
             {:containers
              [{:name :bar
                :image "some-image"}]}}}})

;; `update-container` works on a pod. It takes a container name, and
;; applies the augmentation function with its arguments on the
;; container with the given name. It can be used in conjunction with
;; `update-template` to operate on a controller.
(fact
 (-> (lkb/pod :foo {:bar :baz})
     (lkb/add-container :bar "some-image")
     (lkb/add-container :baz "some-other-image")
     (lkb/deployment 3)
     ;; We add an environment to a container.
     (lkb/update-template lkb/update-container :bar lkb/add-env {:FOO "BAR"}))
 => {:apiVersion "apps/v1"
     :kind "Deployment"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 3
            :selector
            {:matchLabels {:bar :baz}}
            :template
            {:metadata
             {:labels {:bar :baz}}
             :spec
             {:containers
              [{:name :bar
                :image "some-image"
                :env [{:name :FOO
                       :value "BAR"}]}
               {:name :baz
                :image "some-other-image"}]}}}})

;; # Exposure Functions

;; There are several ways to expose a service under Kubernetes. The
;; `expose*` family of functions wraps an existing deployment with a
;; list, containing the deployment itself (unchanged) and a service,
;; which exposes it.

;; The `expose` function is the most basic among them. The service it
;; provides takes its spec as argument.
(fact
 (-> (lkb/pod :nginx-deployment {:app :nginx})
     (lkb/add-container :nginx "nginx:1.7.9" {:ports [{:containerPort 80}]})
     (lkb/deployment 3)
     (lkb/expose {:ports [{:protocol :TCP
                           :port 80
                           :targetPort 9376}]}))
 => [{:apiVersion "apps/v1"
      :kind "Deployment"
      :metadata {:labels {:app :nginx} :name :nginx-deployment}
      :spec {:replicas 3
             :selector {:matchLabels {:app :nginx}}
             :template {:metadata {:labels {:app :nginx}}
                        :spec {:containers [{:image "nginx:1.7.9"
                                             :name :nginx
                                             :ports [{:containerPort 80}]}]}}}}
     {:kind "Service"
      :apiVersion "v1"
      :metadata {:name :nginx-deployment}
      :spec
      {:selector {:app :nginx}
       :ports [{:protocol :TCP
                :port 80
                :targetPort 9376}]}}])


;; The `expose-headless` wraps the given controller (deployment,
;; statefulset) with a headless service. The service exposes all the
;; ports listed as `:containerPort`s in all the containers in the
;; controller's template. For ports with a `:name`, the name is also
;; copied over.
(fact
 (-> (lkb/pod :nginx-deployment {:app :nginx})
     (lkb/add-container :nginx "nginx:1.7.9" {:ports [{:containerPort 80 :name :web}]})
     (lkb/add-container :sidecar "my-sidecar" {:ports [{:containerPort 3333}]})
     (lkb/deployment 3)
     (lkb/expose-headless))
 => [{:apiVersion "apps/v1"
      :kind "Deployment"
      :metadata {:labels {:app :nginx}
                 :name :nginx-deployment}
      :spec {:replicas 3
             :selector {:matchLabels {:app :nginx}}
             :template
             {:metadata {:labels {:app :nginx}}
              :spec {:containers
                     [{:image "nginx:1.7.9"
                       :name :nginx
                       :ports [{:containerPort 80
                                :name :web}]}
                      {:image "my-sidecar"
                       :name :sidecar
                       :ports [{:containerPort 3333}]}]}}}}
     {:kind "Service"
      :apiVersion "v1"
      :metadata {:name :nginx-deployment}
      :spec
      {:selector {:app :nginx}
       :clusterIP :None
       :ports [{:port 80 :name :web}
               {:port 3333}]}}])


;; # Dependency Injection

;; Functions such as `pod` and `deployment` help build Kubernetes API
;; objects. If we consider Lambda-Kube to be a language, these are the
;; _common nouns_. They can be used to build general Pods,
;; Deployments, StatefulSets, etc, and can be used to develop other
;; functions that create general things such as a generic Redis
;; database, or a generic Nginx deployment, which can also be
;; represented as a function.

;; However, when we go down to the task of defining a system, we need
;; a way to define _proper nouns_, such as _our_ Redis database and
;; _our_ Nginx deployment.

;; This distinction is important because when creating a generic Nginx
;; deployment, it stands on its own, and is unrelated to any Redis
;; database that may or may not be used in conjunction with
;; it. However, when we build our application, which happens to have
;; some, e.g., PHP code running on top of Nginx, which happens to
;; require a database, this is when we need the two to be
;; connected. We need to connect them by, e.g., adding environment
;; variables to the Nginx container, so that PHP code that runs over
;; it will be able to connect to the database.

;; This is where dependency injection comes in. Dependency Injection
;; (DI) is a general concept that allows developers to define proper
;; nouns in their software in an incremental way. It starts with some
;; configuration, which provides arbitrary settings. Then a set of
;; resources is being defined. Each such resource may depend on other
;; resources, including configuration.

;; Our implementation of DI, resources are identified with symbols,
;; corresponding to the proper nouns. These nouns are defined in
;; functions, named _modules_, which take a single parameter -- an
;; _injector_ (marked as `$` by convention), and augment it by adding
;; new rules to it.
(defn module1 [$]
  (-> $
      (lkb/rule :my-deployment []
                (fn []
                  (-> (lkb/pod :my-pod {:app :my-app})
                      (lkb/deployment 3))))))

;; This module uses the `rule` function to define a single _rule_. A
;; rule has a _name_, a vector of _dependencies_, and a function that
;; takes the dependency values and returns an API object. In this
;; case, the name is `:my-deployment`, there are no dependencies, and
;; the API object is a deployment of three pods.

;; The `injector` function creates an injector based on the given
;; configuration. This injector can be passed to the module to add the
;; rules it defines. Then the function `get-deployable` to get all the
;; API objects in the system.
(fact
 (-> (lkb/injector {})
     (module1)
     (lkb/get-deployable))
 => [(-> (lkb/pod :my-pod {:app :my-app})
         (lkb/deployment 3))])

;; Rules may depend on configuration parameters. These parameters need
;; to be listed as dependencies, and then, if they exist in the
;; injector's configuration, their values are passed to the
;; function. In the following example, the module has two rules:
;; `:my-deployment`, and `:not-going-to-work`. The former is similar
;; to the one defined in `module1`, but takes the number of replicas
;; from the configuration. The latter depends on the parameter
;; `:does-not-exist`.
(defn module2 [$]
  (-> $
      (lkb/rule :not-going-to-work [:does-not-exist]
                (fn [does-not-exist]
                  (lkb/pod :no-pod {:app :no-app})))
      (lkb/rule :my-deployment [:my-deployment-num-replicas]
                (fn [num-replicas]
                  (-> (lkb/pod :my-pod {:app :my-app})
                      (lkb/deployment num-replicas))))))

;; Now, if we provide a configuration that only contains
;; `:my-deployment-num-replicas`, but not `:not-going-to-work`,
;; `:my-deployment` will be created, but not `:not-going-to-work`.
(fact
 (-> (lkb/injector {:my-deployment-num-replicas 5})
     (module2)
     (lkb/get-deployable))
 => [(-> (lkb/pod :my-pod {:app :my-app})
         (lkb/deployment 5))])

;; If the rule emits a list (e.g., in the case of a service attached
;; to a deployment), the list is flattened.
(defn module3 [$]
  (-> $
      (lkb/rule :my-service [:my-deployment-num-replicas]
                (fn [num-replicas]
                  (-> (lkb/pod :my-service {:app :my-app})
                      (lkb/deployment num-replicas)
                      (lkb/expose {}))))))

(fact
 (-> (lkb/injector {:my-deployment-num-replicas 5})
     (module3)
     (lkb/get-deployable))
 => (-> (lkb/pod :my-service {:app :my-app})
        (lkb/deployment 5)
        (lkb/expose {})))

;; Resources may depend on one another. The following module depends
;; on `:my-service`.
(defn module4 [$]
  (-> $
      (lkb/rule :my-pod [:my-service]
                (fn [my-service]
                  (lkb/pod :my-pod {:app :my-app})))))
(fact
 (-> (lkb/injector {:my-deployment-num-replicas 5})
     (module4)
     (module3)
     (lkb/get-deployable))
 => (concat [(lkb/pod :my-pod {:app :my-app})]
            (-> (lkb/pod :my-service {:app :my-app})
                (lkb/deployment 5)
                (lkb/expose {}))))

;; ## Describers and Descriptions

;; When one resource depends on another, it often needs information
;; about the other in order to perform its job properly. For example,
;; if the dependency is a service, the resource depending on this
;; service may need the host name and port number of that service.

;; One option would be to provide the complete API object as the
;; dependency information. However, that would defeat the purpose of
;; using DI. The whole idea behind using DI is a decoupling between a
;; resource and its dependencies. If we provide the API object to the
;; rule function, we force it to know what its dependency is, and how
;; to find information there.

;; But almost any problem in computer science can be solved by adding
;; another level of indirection (the only one that isn't is the
;; problem of having too many levels of indirection). In our case, the
;; extra level of indirection is provided by _describers_.

;; Describers are functions that examine an API object, and extract
;; _descriptions_. A description is a map, containing information
;; about the object. Describers are defined inside modules, using the
;; `desc` functions. All describers are applied to all objects. If a
;; describer is not relevant to a certain object it may return
;; `nil`. If it is, it should return a map with some fields
;; representing the object.

;; For example, the following module defines three describers. The
;; first extracts the name out of any object. The second returns the
;; port number for a service (or `nil` if not), and the third extracts
;; the labels.
(defn module5 [$]
  (-> $
      (lkb/desc (fn [obj]
                  {:name (-> obj :metadata :name)}))
      (lkb/desc (fn [obj]
                  (when (= (:kind "Service"))
                    {:port (-> obj :spec :ports first :port)})))
      (lkb/desc (fn [obj]
                  {:labels (-> obj :metadata :labels)}))
      (lkb/rule :first-pod []
                (fn []
                  (lkb/pod :my-first-pod {})))
      (lkb/rule :second-pod [:first-pod]
                (fn [first-pod]
                  (lkb/pod :my-first-pod {:the-name (:name first-pod)
                                          :the-port (:port first-pod)
                                          :the-labels (:labels first-pod)})))))

;; The module also defines two rules for two pods. The second pod
;; depends on the first one, and populates its labels with information
;; about the first pod (not a real-life scenario). When we call
;; `get-deployable`, we will get both pods. The labels in the second
;; pod will be set so that the name will be there, but not the port.
(fact
 (-> (lkb/injector {})
     (module5)
     (lkb/get-deployable))
 => [(lkb/pod :my-first-pod {})
     (lkb/pod :my-first-pod {:the-name :my-first-pod
                             :the-port nil
                             :the-labels {}})])


;; # Utility Functions

;; All the above functions are pure functions that help build
;; Kubernetes API objects for systems. The following functions help
;; translate these objects into a real update of the state of a
;; Kubernetes cluster.

;; `to-yaml` takes a vector of API objects and returns a YAML string
;; acceptable by Kubernetes.
(fact
 (-> (lkb/pod :nginx-deployment {:app :nginx})
     (lkb/add-container :nginx "nginx:1.7.9" {:ports [{:containerPort 80}]})
     (lkb/deployment 3)
     (lkb/expose-headless)
     (lkb/to-yaml)) =>
"apiVersion: apps/v1
kind: Deployment
metadata:
  name: nginx-deployment
  labels:
    app: nginx
spec:
  replicas: 3
  selector:
    matchLabels:
      app: nginx
  template:
    metadata:
      labels:
        app: nginx
    spec:
      containers:
      - ports:
        - containerPort: 80
        name: nginx
        image: nginx:1.7.9
---
apiVersion: v1
kind: Service
metadata:
  name: nginx-deployment
spec:
  ports:
  - port: 80
  clusterIP: None
  selector:
    app: nginx
")

;; `kube-apply` takes a string constructed by `to-yaml` and a `.yaml`
;; file. If the file does not exist, it creates it, and calls `kubectl
;; apply` on it.
(fact
 (let [f (io/file "foo.yaml")]
   (when (.exists f)
     (.delete f))
   (lkb/kube-apply "foo: bar" f) => irrelevant
   (provided
    (sh/sh "kubectl" "apply" "-f" "foo.yaml") => {:exit 0})
   (.exists f) => true
   (slurp f) => "foo: bar"))

;; If the file already exists, and has the exact same content as the
;; given string, nothing happens.
(fact
 (let [f (io/file "foo.yaml")]
   (lkb/kube-apply "foo: bar" f) => irrelevant
   (provided
    ;; Not called
    (sh/sh "kubectl" "apply" "-f" "foo.yaml") => {:exit 0} :times 0)))

;; If the file exists, but the new content is different than what was
;; stored in that file, the file is updated and `kubectl apply` is
;; called.
(fact
 (let [f (io/file "foo.yaml")]
   (lkb/kube-apply "foo: baz" f) => irrelevant
   (provided
    (sh/sh "kubectl" "apply" "-f" "foo.yaml") => {:exit 0})
   (.exists f) => true
   (slurp f) => "foo: baz"))

;; # Turning this to Usable YAML Files

'(println (-> (lkb/pod :nginx-deployment {:app :nginx})
             (lkb/add-container :nginx "nginx:1.7.9" {:ports [{:containerPort 80}]})
             (lkb/deployment 3)
             (lkb/expose-headless)
             (lkb/to-yaml)))

'(println (-> (lkb/pod :nginx {:app :nginx} {:terminationGracePeriodSeconds 10})
             (lkb/add-container :nginx "k8s.gcr.io/nginx-slim:0.8" {:ports [{:containerPort 80
                                                                             :name "web"}]})
             (lkb/stateful-set 3)
             (lkb/add-volume-claim-template :www
                                            {:accessModes ["ReadWriteOnce"]
                                             :resources {:requests {:storage "1Gi"}}}
                                            {:nginx "/usr/share/nginx/html"})
             (lkb/expose-headless)
             (lkb/to-yaml)))
