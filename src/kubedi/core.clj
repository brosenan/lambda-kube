(ns kubedi.core)

(defn pod [name labels]
  {:apiVersion "v1"
   :kind "Pod"
   :metadata {:name :foo
              :labels labels}
   :spec {:containers []}})

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

(defn expose [depl options]
  [depl
   {:apiVersion "v1"
    :kind "Service"
    :metadata {:name (-> depl :metadata :name)}
    :spec (-> options
              (merge {:selector (-> depl :metadata :labels)}))}])
