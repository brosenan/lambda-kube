(ns lambdakube.util
  (:require [lambdakube.core :as lk]
            [clojure.string :as str]))

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
