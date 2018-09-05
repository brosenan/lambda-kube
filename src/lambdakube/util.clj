(ns lambdakube.util
  (:require [lambdakube.core :as lk]
            [clojure.string :as str]))

(defn add-clj-container [pod cont deps constants code
                         & {:keys [source-file proj lein]
                            :or {source-file "src/main.clj"
                                 proj {}
                                 lein "run"}}]
  (let [projmap (-> {:dependencies deps
                     :main 'main}
                    (merge proj))
        proj (pr-str (concat ['defproject 'myproj "0.0.1-SNAPSHOT"]
                             (mapcat identity projmap)))
        code (concat [(first code)]
                     (for [[k v] constants]
                       (list 'def (-> k name symbol) v))
                     (rest code))
        code (str/join "\n" (map pr-str code))]
    (-> pod
        (lk/add-container cont "clojure:lein-2.8.1")
        (lk/add-files-to-container cont (keyword (str (name cont) "-clj")) "/src"
                                   {"project.clj" proj
                                    source-file code})
        (lk/update-container cont assoc :command
                             ["sh" "-c" (str "cp -r /src /work && cd /work && lein " lein)]))))


(defn wait-for-service-port [pod dep portname]
  (let [{:keys [hostname ports]} dep
        cont (keyword (str "wait-for-" (name hostname) "-" (name portname)))]
    (-> pod
        (lk/add-init-container cont "busybox"
                               {:command ["sh"
                                          "-c"
                                          (str "while ! nc -z " hostname " " (ports portname) "; do sleep 1; done")]}))))


(defn add-clj-test-container [pod cont deps constants exprs]
  (add-clj-container pod cont deps constants exprs
                     :source-file "test/main_test.clj"
                     :lein "test"))

(defn add-midje-container [pod cont deps constants exprs]
  (add-clj-container pod cont deps constants exprs
                     :source-file "test/main_test.clj"
                     :lein "midje"
                     :proj {:profiles {:dev {:dependencies '[[midje "1.9.2"]]
                                             :plugins '[[lein-midje "3.2.1"]]}}}))
