(ns lambdakube.core
  (:require [loom.graph :refer [digraph]]
            [loom.alg :refer [topsort]]))

(defn pod
  ([name labels options]
   {:apiVersion "v1"
    :kind "Pod"
    :metadata {:name name
               :labels labels}
    :spec (-> options
              (merge {:containers []}))})
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
     (update-in pod [:spec :containers] conj container)))
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

(defn add-volume-claim-template [sset name spec mounts]
  (let [add-mount (fn [cont]
                    (if (contains? mounts (:name cont))
                      (let [new-mount {:mountPath (mounts (:name cont))
                                       :name name}]
                        (if (contains? cont :volumeMounts)
                          (-> cont
                              (update :volumeMounts conj new-mount))
                          ;; else
                          (-> cont
                              (assoc :volumeMounts [new-mount]))))
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

(defn injector [config]
  {:config config
   :rules []
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
  (let [rulemap (into {} (for [[func deps res] rules]
                           [res [func deps res]]))
        g (apply digraph (concat
                          ;; Vertices
                          (for [[func deps res] rules]
                            res)
                          ;; Edges
                          (for [[func deps res] rules
                                d deps]
                            [d res])))]
    (map rulemap (topsort g))))

(defn get-deployable [{:keys [config rules]}]
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
                                 [(append out (apply func (map config deps))) (assoc config res :foo)]
                                 ;; else
                                 [out config])]
              (recur (rest rules) config out))))))))

(defn desc [])
