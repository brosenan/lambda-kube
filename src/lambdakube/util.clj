(ns lambdakube.util
  (:require [lambdakube.core :as lk]
            [clojure.string :as str]
            [clojure.java.shell :as sh]
            [clojure.data.json :as json]))

(defn add-clj-container [pod cont deps code]
  (let [proj (pr-str `(~'defproject ~'myproj "0.0.1-SNAPSHOT"
                       :dependencies ~deps
                       :main ~'main))
        code (str/join "\n" (map pr-str code))]
    (-> pod
        (lk/add-container cont "clojure:lein-2.8.1")
        (lk/add-files-to-container cont (keyword (str (name cont) "-clj")) "/src"
                                   {"project.clj" proj
                                    "src/main.clj" code})
        (lk/update-container cont assoc :command
                             ["sh" "-c" "cp -r /src /work && cd /work && lein run"]))))


(defn wait-for-service-port [pod dep portname]
  (let [{:keys [hostname ports]} dep
        cont (keyword (str "wait-for-" (name hostname) "-" (name portname)))]
    (-> pod
        (lk/add-init-container cont "busybox"
                               {:command ["nc" "-z" hostname (str (ports portname))]}))))


(defn test [$ name config deps func]
  (let [func' (fn [& args]
                (-> (apply func args)
                    (update :metadata assoc :name :test)
                    (lk/job :Never)))]
    (-> $
        (update :tests assoc name config)
        (lk/rule name deps func'))))

(defn- kubectl [& args]
  (let [ret (apply sh/sh "kubectl" args)]
      (when-not (= (:exit ret) 0)
        (throw (Exception. (:err ret))))
      (:out ret)))

(defn run-test [$ test prefix]
  (let [kns (str prefix "-" (name test))
        depl (lk/get-deployable $ ((:tests $) test))
        yaml (lk/to-yaml depl)
        filename (str kns ".yaml")]
    (spit filename yaml)
    (kubectl "create" "ns" kns)
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
          log (kubectl "-n" kns "logs" "-ljob-name=test")]
      (when (= status :pass)
        (kubectl "delete" "ns" kns))
      {:log log
       :status status})))

(defn run-tests [$ prefix]
  (into {} (for [[k v] (:tests $)]
             [k (run-test $ k prefix)])))
