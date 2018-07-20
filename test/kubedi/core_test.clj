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
;; controller's template.
(fact
 (-> (kdi/pod :nginx-deployment {:app :nginx})
     (kdi/add-container :nginx "nginx:1.7.9" {:ports [{:containerPort 80}]})
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
                       :ports [{:containerPort 80}]}
                      {:image "my-sidecar"
                       :name :sidecar
                       :ports [{:containerPort 3333}]}]}}}}
     {:kind "Service"
      :apiVersion "v1"
      :metadata {:name :nginx-deployment}
      :spec
      {:selector {:app :nginx}
       :clusterIP :None
       :ports [{:port 80}
               {:port 3333}]}}])


(defn to-yaml [objs]
  (->> objs
       (map #(yaml/generate-string % :dumper-options {:flow-style :block :scalar-style :plain}))
       (str/join "---\n")))

'(println (-> (kdi/pod :nginx-deployment {:app :nginx})
             (kdi/add-container :nginx "nginx:1.7.9" {:ports [{:containerPort 80}]})
             (kdi/deployment 3)
             (kdi/expose-headless)
             (to-yaml)))

(println (-> (kdi/pod :nginx {:app :nginx} {:terminationGracePeriodSeconds 10})
             (kdi/add-container :nginx "k8s.gcr.io/nginx-slim:0.8" {:ports [{:containerPort 80
                                                                             :name "web"}]})
             (kdi/stateful-set 3)
             (kdi/add-volume-claim-template :www
                                            {:accessModes ["ReadWriteOnce"]
                                             :storageClassName :my-storage-class
                                             :resources {:requests {:storage "1Gi"}}}
                                            {:nginx "/usr/share/nginx/html"})
             (kdi/expose-headless)
             (to-yaml)))
