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
      :metadata {:labels {:app :nginx} :name :foo}
      :spec {:replicas 3
             :selector {:matchLabels {:app :nginx}}
             :template {:metadata {:labels {:app :nginx}}
                        :spec {:containers [{:image "nginx:1.7.9"
                                             :name :nginx
                                             :ports [{:containerPort 80}]}]}}}}
     {:kind "Service"
      :apiVersion "v1"
      :metadata {:name :foo}
      :spec
      {:selector {:app :nginx}
       :ports [{:protocol :TCP
                :port 80
                :targetPort 9376}]}}])


(defn to-yaml [objs]
  (->> objs
       (map #(yaml/generate-string % :dumper-options {:flow-style :block :scalar-style :plain}))
       (str/join "---\n")))

'(println (-> (kdi/pod :nginx-deployment {:app :nginx})
             (kdi/add-container :nginx "nginx:1.7.9" {:ports [{:containerPort 80}]})
             (kdi/deployment 3)
             (kdi/expose {:type :NodePort
                          :ports [{:port 80
                                   :targetPort 80
                                   :protocol :TCP}]})
             (to-yaml)))
