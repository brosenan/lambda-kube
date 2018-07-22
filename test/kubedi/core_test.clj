(ns kubedi.core-test
  (:require [midje.sweet :refer :all]
            [kubedi.core :as kdi]
            [yaml.core :as yaml]
            [clojure.string :as str]))

;; # Basic API Object Functions

;; The following functions create basic API objects.

;; The `pod` function creates a pod with no containers.
(fact
 (kdi/pod :foo {:app :bar})
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:name :foo
                :labels {:app :bar}}
     :spec {:containers []}})

;; `pod` can take a third argument with additional spec parameters.
(fact
 (kdi/pod :foo {:app :bar} {:foo :bar})
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:name :foo
                :labels {:app :bar}}
     :spec {:containers []
            :foo :bar}})

;; The `deployment` function creates a deployment, based on the given
;; pod as template. The deployment takes its name from the given pod,
;; and removes the name from the template.
(fact
 (-> (kdi/pod :foo {:bar :baz})
     (kdi/add-container :bar "some-image")
     (kdi/deployment 3))
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
 (-> (kdi/pod :foo {:bar :baz})
     (kdi/add-container :bar "some-image")
     (kdi/stateful-set 5))
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
 (-> (kdi/pod :foo {})
     (kdi/add-container :bar "bar-image" {:ports [{:containerPort 80}]}))
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
 (-> (kdi/pod :foo {})
     (kdi/add-container :bar "bar-image" (-> {:ports [{:containerPort 80}]}
                                             (kdi/add-env {:FOO "BAR"}))))
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
 (-> (kdi/pod :foo {})
     (kdi/add-container :bar "bar-image" (-> {:env [{:name :QUUX :value "TAR"}]}
                                             (kdi/add-env {:FOO "BAR"}))))
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

;; The `add-volume-claim-template` function takes a stateful-set, adds
;; a volume claim template to its spec and mounts it to the given
;; paths within the given containers.
(fact
 (-> (kdi/pod :foo {:bar :baz})
     (kdi/add-container :bar "some-image")
     (kdi/add-container :baz "some-other-image")
     (kdi/stateful-set 5 {:additional-arg 123})
     (kdi/add-volume-claim-template :vol-name
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
 (-> (kdi/pod :foo {:bar :baz})
     (kdi/add-container :bar "some-image" {:volumeMounts [{:foo :bar}]})
     (kdi/stateful-set 5)
     (kdi/add-volume-claim-template :vol-name
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
 (-> (kdi/pod :foo {:bar :baz})
     (kdi/deployment 3)
     ;; The original pod has no containers. We add one now.
     (kdi/update-template kdi/add-container :bar "some-image"))
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
 (-> (kdi/pod :foo {:bar :baz})
     (kdi/add-container :bar "some-image")
     (kdi/add-container :baz "some-other-image")
     (kdi/deployment 3)
     ;; We add an environment to a container.
     (kdi/update-template kdi/update-container :bar kdi/add-env {:FOO "BAR"}))
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
 (-> (kdi/pod :nginx-deployment {:app :nginx})
     (kdi/add-container :nginx "nginx:1.7.9" {:ports [{:containerPort 80}]})
     (kdi/deployment 3)
     (kdi/expose {:ports [{:protocol :TCP
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
 (-> (kdi/pod :nginx-deployment {:app :nginx})
     (kdi/add-container :nginx "nginx:1.7.9" {:ports [{:containerPort 80 :name :web}]})
     (kdi/add-container :sidecar "my-sidecar" {:ports [{:containerPort 3333}]})
     (kdi/deployment 3)
     (kdi/expose-headless))
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
;; objects. If we consider Kubedi to be a language, these are the
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
      (kdi/rule :my-deployment []
                (fn []
                  (-> (kdi/pod :my-pod {:app :my-app})
                      (kdi/deployment 3))))))

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
 (-> (kdi/injector {})
     (module1)
     (kdi/get-deployable))
 => [(-> (kdi/pod :my-pod {:app :my-app})
         (kdi/deployment 3))])

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
      (kdi/rule :not-going-to-work [:does-not-exist]
                (fn [does-not-exist]
                  (kdi/pod :no-pod {:app :no-app})))
      (kdi/rule :my-deployment [:my-deployment-num-replicas]
                (fn [num-replicas]
                  (-> (kdi/pod :my-pod {:app :my-app})
                      (kdi/deployment num-replicas))))))

;; Now, if we provide a configuration that only contains
;; `:my-deployment-num-replicas`, but not `:not-going-to-work`,
;; `:my-deployment` will be created, but not `:not-going-to-work`.
(fact
 (-> (kdi/injector {:my-deployment-num-replicas 5})
     (module2)
     (kdi/get-deployable))
 => [(-> (kdi/pod :my-pod {:app :my-app})
         (kdi/deployment 5))])

;; If the rule emits a list (e.g., in the case of a service attached
;; to a deployment), the list is flattened.
(defn module3 [$]
  (-> $
      (kdi/rule :my-service [:my-deployment-num-replicas]
                (fn [num-replicas]
                  (-> (kdi/pod :my-service {:app :my-app})
                      (kdi/deployment num-replicas)
                      (kdi/expose {}))))))

(fact
 (-> (kdi/injector {:my-deployment-num-replicas 5})
     (module3)
     (kdi/get-deployable))
 => (-> (kdi/pod :my-service {:app :my-app})
        (kdi/deployment 5)
        (kdi/expose {})))

;; Resources may depend on one another. The following module depends
;; on `:my-service`.
(defn module4 [$]
  (-> $
      (kdi/rule :my-pod [:my-service]
                (fn [my-service]
                  (kdi/pod :my-pod {:app :my-app})))))
(fact
 (-> (kdi/injector {:my-deployment-num-replicas 5})
     (module4)
     (module3)
     (kdi/get-deployable))
 => (concat [(kdi/pod :my-pod {:app :my-app})]
            (-> (kdi/pod :my-service {:app :my-app})
                (kdi/deployment 5)
                (kdi/expose {}))))

;; # Turning this to Usable YAML Files

(defn to-yaml [objs]
  (->> objs
       (map #(yaml/generate-string % :dumper-options {:flow-style :block :scalar-style :plain}))
       (str/join "---\n")))

'(println (-> (kdi/pod :nginx-deployment {:app :nginx})
             (kdi/add-container :nginx "nginx:1.7.9" {:ports [{:containerPort 80}]})
             (kdi/deployment 3)
             (kdi/expose-headless)
             (to-yaml)))

'(println (-> (kdi/pod :nginx {:app :nginx} {:terminationGracePeriodSeconds 10})
             (kdi/add-container :nginx "k8s.gcr.io/nginx-slim:0.8" {:ports [{:containerPort 80
                                                                             :name "web"}]})
             (kdi/stateful-set 3)
             (kdi/add-volume-claim-template :www
                                            {:accessModes ["ReadWriteOnce"]
                                             :resources {:requests {:storage "1Gi"}}}
                                            {:nginx "/usr/share/nginx/html"})
             (kdi/expose-headless)
             (to-yaml)))
