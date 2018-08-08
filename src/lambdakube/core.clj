(ns lambdakube.core
  (:require [loom.graph :refer [digraph]]
            [loom.alg :refer [topsort]]
            [clojure.string :as str]
            [yaml.core :as yaml]
            [clojure.java.shell :as sh]))

(defn- field-conj [m k v]
  (if (contains? m k)
    (update m k conj v)
    ;; else
    (assoc m k [v])))

(defn pod
  ([name labels options]
   {:apiVersion "v1"
    :kind "Pod"
    :metadata {:name name
               :labels labels}
    :spec options})
  ([name labels]
   (pod name labels {})))

(defn deployment [pod replicas]
  (let [name (-> pod :metadata :name)
        labels (-> pod :metadata :labels)
        template (-> pod
                     (update :metadata dissoc :name)
                     (dissoc :apiVersion :kind))]
    {:apiVersion "apps/v1"
     :kind "Deployment"
     :metadata {:name name
                :labels labels}
     :spec {:replicas replicas
            :selector {:matchLabels labels}
            :template template}}))

(defn stateful-set
  ([pod replicas options]
   (let [name (-> pod :metadata :name)
         labels (-> pod :metadata :labels)
         template (-> pod
                      (update :metadata dissoc :name)
                      (dissoc :apiVersion :kind))]
     {:apiVersion "apps/v1"
      :kind "StatefulSet"
      :metadata {:name name
                 :labels labels}
      :spec (-> options
                (merge {:replicas replicas
                        :selector
                        {:matchLabels labels}
                        :serviceName name
                        :template template
                        :volumeClaimTemplates []}))}))
  ([pod replicas]
   (stateful-set pod replicas {})))

(defn add-container
  ([pod name image options]
   (let [container (-> options
                       (merge {:name name
                               :image image}))]
     (update pod :spec field-conj :containers container)))
  ([pod name image]
   (add-container pod name image {})))

(defn add-env [container envs]
  (let [envs (for [[name val] envs]
                           {:name name
                            :value val})]
    (if (contains? container :env)
      (-> container
          (update :env concat envs))
      ;; else
      (-> container
          (assoc :env (vec envs))))))

(defn add-init-container
  ([pod name image options]
   (let [container (-> options
                       (merge {:name name
                               :image image}))]
     (update pod :spec field-conj :initContainers container)))
  ([pod name image]
   (add-init-container pod name image {})))

(defn add-volume-claim-template [sset name spec mounts]
  (let [add-mount (fn [cont]
                    (if (contains? mounts (:name cont))
                      (let [new-mount {:mountPath (mounts (:name cont))
                                       :name name}]
                        (field-conj cont :volumeMounts new-mount))
                      ;; else
                      cont))]
    (-> sset
        (update-in [:spec :volumeClaimTemplates] conj {:metadata {:name name}
                                                       :spec spec})
        (update-in [:spec :template :spec :containers] #(map add-mount %)))))

(defn update-template [ctrl f & args]
  (apply update-in ctrl [:spec :template] f args))

(defn update-container [pod cont-name f & args]
  (let [update-cont (fn [cont]
                      (if (= (:name cont) cont-name)
                        (apply f cont args)
                        ;; else
                        cont))]
    (update-in pod [:spec :containers] #(map update-cont %))))

(defn expose [depl options]
  [depl
   {:apiVersion "v1"
    :kind "Service"
    :metadata {:name (-> depl :metadata :name)}
    :spec (-> options
              (merge {:selector (-> depl :metadata :labels)}))}])

(defn expose-headless [ctrl]
  (expose ctrl {:ports (for [cont (-> ctrl :spec :template :spec :containers)
                             port (-> cont :ports)]
                         (if (contains? port :name)
                           {:port (-> port :containerPort)
                            :name (-> port :name)}
                           ;; else
                           {:port (-> port :containerPort)}))
                :clusterIP :None}))

(defn injector []
  {:rules []
   :descs []})

(defn rule [$ res deps func]
  (update $ :rules conj [func deps res]))

(defn get-resource [$ res])

(defn- append [list obj]
  (if (sequential? obj)
    (concat list obj)
    ;; else
    (conj list obj)))

(defn- sorted-rules [rules]
  (let [rulemap (into {} (map-indexed vector rules))
        g (apply digraph (concat
                          ;; Inputs
                          (for [[index [func deps res]] rulemap
                                dep deps]
                            [dep index])
                          ;; Outputs
                          (for [[index [func deps res]] rulemap]
                            [index res])))]
    (map rulemap (topsort g))))

(defn- describe [api-obj descs]
  (->> descs
       (map (fn [f] (f api-obj)))
       (reduce merge {})))

(defn get-deployable [{:keys [rules descs]} config]
  (let [rules (sorted-rules rules)]
    (loop [rules rules
           config config
           out []]
      (if (empty? rules)
        out
        ;; else
        (let [rule (first rules)]
          (if (nil? rule)
            (recur (rest rules) config out)
            ;; else
            (let [[func deps res] rule
                  [out config] (if (every? (partial contains? config) deps)
                                 (let [api-obj (apply func (map config deps))
                                       desc (describe api-obj descs)]
                                   [(append out api-obj) (assoc config res desc)])
                                 ;; else
                                 [out config])]
              (recur (rest rules) config out))))))))

(defn desc [$ func]
  (update $ :descs conj func))

(defn to-yaml [v]
  (->> v
       (map #(yaml/generate-string % :dumper-options {:flow-style :block :scalar-style :plain}))
       (str/join "---\n")))

(defn kube-apply [content file]
  (when-not (and (.exists file)
                 (= (slurp file) content))
    (spit file content)
    (let [res (sh/sh "kubectl" "apply" "-f" (str file))]
      (when-not (= (:exit res) 0)
        (.delete file)
        (throw (Exception. (:err res)))))))
