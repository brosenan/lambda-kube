(ns lambdakube.core
  (:require [loom.graph :refer [digraph]]
            [loom.alg :refer [topsort]]
            [clojure.string :as str]
            [yaml.core :as yaml]
            [clojure.java.shell :as sh]))

(defn field-conj [m k v]
  (if (contains? m k)
    (update m k conj v)
    ;; else
    (assoc m k [v])))

(defn extract-additional [obj]
  (cond
    (map? obj) (let [obj (into {} (for [[k v] obj]
                                    [k (extract-additional v)]))
                     additions-from-fields (mapcat (fn [[k v]] (-> v meta :additional)) obj)
                     explicit-additions (:$additional obj)
                     additional (concat explicit-additions
                                        (mapcat #(-> % meta :additional) explicit-additions)
                                        additions-from-fields)]
                 (with-meta (dissoc obj :$additional)
                   {:additional additional}))
    (sequential? obj) (let [obj (map extract-additional obj)
                            additional (mapcat #(-> % meta :additional) obj)]
                        (with-meta obj {:additional additional}))
    :else obj))

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

(defn job
  ([pod restart-policy]
   (job pod restart-policy {}))
  ([pod restart-policy attrs]
   {:apiVersion "batch/v1"
    :kind "Job"
    :metadata {:name (-> pod :metadata :name)
               :labels (-> pod :metadata :labels)}
    :spec (merge {:template (-> pod
                                (dissoc :apiVersion :kind)
                                (update :metadata dissoc :name)
                                (update :spec assoc :restartPolicy restart-policy))}
                 attrs)}))

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

(defn config-map [name m]
  {:apiVersion "v1"
   :kind "ConfigMap"
   :metadata {:name name}
   :data m})

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
    (-> container
        (update :env concat envs))))

(defn add-init-container
  ([pod name image options]
   (let [container (-> options
                       (merge {:name name
                               :image image}))]
     (update pod :spec field-conj :initContainers container)))
  ([pod name image]
   (add-init-container pod name image {})))

(defn update-container [pod cont-name f & args]
  (let [update-cont (fn [cont]
                      (if (= (:name cont) cont-name)
                        (apply f cont args)
                        ;; else
                        cont))]
    (update-in pod [:spec :containers] #(map update-cont %))))

(defn- mount-func [name mounts]
  (apply comp (for [[cont path] mounts]
                #(update-container % cont field-conj :volumeMounts
                                   {:name name
                                    :mountPath path}))))

(defn add-volume [pod name spec mounts]
  (-> pod
      (update :spec field-conj :volumes (-> {:name name}
                                            (merge spec)))
      ((mount-func name mounts))))

(defn add-files-to-container [pod cont unique base-path mounts]
  (let [relpathmap (into {} (for [[i [path val]] (map-indexed vector mounts)]
                              [(str "c" i) val]))
        items (vec (for [[i [path val]] (map-indexed vector mounts)]
                     {:key (str "c" i)
                      :path path}))]
    (-> pod
        (field-conj :$additional (config-map unique relpathmap))
        (add-volume unique {:configMap {:name unique
                                        :items items}}
                    {cont base-path}))))

(defn update-template [ctrl f & args]
  (apply update-in ctrl [:spec :template] f args))

(defn add-volume-claim-template [sset name spec mounts]
  (-> sset
      (update :spec field-conj :volumeClaimTemplates {:metadata {:name name}
                                                      :spec spec})
      (update-template (mount-func name mounts))))

(defn add-annotation [obj key val]
  (-> obj
      (update-in [:metadata :annotations] assoc key val)))

(defn expose [depl name portfunc attrs editfunc]
  (let [pod (-> depl :spec :template)
        srv {:kind "Service"
             :apiVersion "v1"
             :metadata {:name name}
             :spec (-> attrs
                       (merge {:selector (-> pod :metadata :labels)}))}
        [pod srv] (portfunc [pod srv editfunc])]
    (-> depl
        (field-conj :$additional srv)
        (update :spec assoc :template pod))))

(defn expose-cluster-ip
  ([depl name portfunc]
   (expose-cluster-ip depl name portfunc {}))
  ([depl name portfunc attrs]
   (expose depl name portfunc (merge attrs {:type :ClusterIP})
           (fn [svc portname podport svcport]
             (update svc :spec field-conj :ports {:port svcport
                                                  :name portname
                                                  :targetPort portname})))))

(defn expose-headless
  ([depl name portfunc]
   (expose-headless depl name portfunc {}))
  ([depl name portfunc attrs]
   (expose-cluster-ip depl name portfunc (merge attrs {:clusterIP :None}))))

(defn expose-node-port [depl name portfunc]
  (expose depl name portfunc {:type :NodePort}
          (fn [svc portname podport svcport]
            (let [ports {:targetPort portname
                         :name portname
                         :port podport}
                  ports (if (nil? svcport)
                          ports
                          ;; else
                          (assoc ports :nodePort svcport))]
              (update svc :spec field-conj :ports ports)))))

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

(defn- describe-single [api-obj descs]
  (->> descs
       (map (fn [f] (f api-obj)))
       (reduce merge {})))

(defn- describe [api-obj descs]
  (let [api-obj (extract-additional api-obj)
        objs (cons api-obj (-> api-obj meta :additional))]
    (->> objs
         (map #(describe-single % descs))
         (reduce merge {}))))

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
                                 (if (contains? config res)
                                   (throw (Exception. (str "Conflicting prerequisites for resource " res)))
                                   ;; else
                                   (let [api-obj (apply func (map config deps))
                                         desc (describe api-obj descs)
                                         extracted (extract-additional api-obj)]
                                     [(concat out [extracted] (-> extracted meta :additional))
                                      (assoc config res desc)]))
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

(defn port
  ([cont portname podport]
   (port cont portname podport nil))
  ([cont portname podport svcport]
   (fn [[pod svc edit-svc]]
     [(-> pod
          (update-container cont field-conj :ports {:containerPort podport
                                                    :name portname}))
      (-> svc
          (edit-svc portname podport svcport))
      edit-svc])))

(defn standard-descs [$]
  (-> $
      (desc (fn [obj]
              (when (contains? (:metadata obj) :annotations)
                {:annotations (-> obj :metadata :annotations)})))
      (desc (fn [svc]
              (when (= (:kind svc) "Service")
                {:hostname (-> svc :metadata :name)
                 :ports (->> (for [{:keys [name port]} (-> svc :spec :ports)]
                               [name port])
                             (into {}))})))))
