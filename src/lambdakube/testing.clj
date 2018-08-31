(ns lambdakube.testing
  (:require [lambdakube.core :as lk]
            [clojure.java.shell :as sh]
            [clojure.data.json :as json]))

(defn test [$ name config deps func]
  (let [func' (fn [& args]
                (-> (apply func args)
                    (update :metadata assoc :name :test)
                    (lk/job :Never {:backoffLimit 0})))]
    (-> $
        (update :tests assoc name config)
        (lk/rule name deps func'))))

(defn- kubectl [& args]
  (let [ret (apply sh/sh "kubectl" args)]
      (when-not (= (:exit ret) 0)
        (throw (Exception. (:err ret))))
      (:out ret)))

(defn log [msg]
  (println msg))

(defn run-test [$ test prefix]
  (let [kns (str prefix "-" (name test))
        depl (lk/get-deployable $ ((:tests $) test))
        yaml (lk/to-yaml depl)
        filename (str kns ".yaml")]
    (spit filename yaml)
    (log (str "Creating namespace " kns))
    (kubectl "create" "ns" kns)
    (log (str "Deploying test " test))
    (kubectl "-n" kns "apply" "-f" filename)
    (let [status (loop []
                   (let [out (kubectl "-n" kns "get" "job" "test" "-o" "json")
                         job (json/read-str out)
                         status (job "status")]
                     (if (and (contains? status "active")
                              (> (status "active") 0))
                       (recur)
                       ;; else
                       (if (and (contains? status "succeeded")
                                (> (status "succeeded") 0))
                         :pass
                         ;; else
                         :fail))))
          joblog (kubectl "-n" kns "logs" "-ljob-name=test")]
      (log (str "Test " test " completed. Status: " status))
      (when (= status :pass)
        (log (str "Deleting namespace " kns))
        (kubectl "delete" "ns" kns))
      {:log joblog
       :status status})))

(defn run-tests
  ([$ prefix]
   (run-tests $ prefix (constantly true)))
  ([$ prefix pred]
   (->> (:tests $)
        (filter (comp pred second))
        (map (fn [[k v]]
               [k (run-test $ k prefix)]))
        (into {}))))


