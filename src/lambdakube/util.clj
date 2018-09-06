(ns lambdakube.util
  (:require [lambdakube.core :as lk]
            [clojure.string :as str]
            [clojure.data.json :as json]))

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


(defn- get-ver [lib]
  (or
   (->> ;; We start by feching the classpath and splitting it.
    (-> (System/getProperty "java.class.path")
        (str/split #":"))
    ;; Then we split each path using the path separator
    (map #(str/split % #"/"))
    ;; When we reverse the path, the jar file name becomes the first
    ;; element, the version becomes the second and the library name
    ;; becomes the third
    (map reverse)
    ;; We filter based on the library name.
    (filter #(= (nth % 2) lib))
    ;; Take the first (and hopefully, only) result.
    first
    ;; And extract its version.
    second)
   ;; For the current project, we there is a property
   (System/getProperty (str lib ".version"))))

(defn add-itd-annotations [pod cls proj base-url]
  (-> pod
      (lk/add-annotation :class (.getName cls))
      (lk/add-annotation :jar (str base-url "/"
                                   (str/replace (namespace proj) "." "/") "/"
                                   (name proj) "/"
                                   (get-ver (name proj)) "/"
                                   (name proj) "-" (get-ver (name proj)) "-standalone.jar"))))

(defn inject-driver [cont itf dep]
  (let [interface-name (-> itf
                           .getName
                           (str/replace "." "_")
                           (str/upper-case)
                           (keyword))]
    (lk/add-env cont {interface-name (json/write-str dep)})))
