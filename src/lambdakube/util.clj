(ns lambdakube.util
  (:require [lambdakube.core :as lk]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [digest])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:dynamic *docker-repo* nil)

(defn mk-temp-dir [prefix]
  (-> (Files/createTempDirectory prefix (into-array FileAttribute []))
      .toFile))

(defn lk-dir []
  (io/file (System/getProperty "user.home") ".lambda-kube"))

(defn file-exists? [file]
  (.isFile file))

(defmacro with-docker-repo [& exprs]
  `(let [d# (lk-dir)
         f# (io/file d# "docker-repo")]
     (binding [*docker-repo* (if (file-exists? f#)
                               (-> f# slurp str/trim)
                               ;; else
                               nil)]
       ~@exprs)))

(defn sh [& args]
  (let [{:keys [exit err]} (apply sh/sh args)]
    (when (not= exit 0)
      (throw (Exception. (str "Error status from command: " (str/join " " args) "\n" err))))))

(defn log [& args]
  (apply println args))

(defn create-clj-image [base-image proj]
  (let [d (mk-temp-dir "clj-nano")
        tag (str *docker-repo* "/clj-nanoservice:" (-> proj pr-str digest/sha-256 (subs 0 16)))]
    (spit (io/file d "Dockerfile")
          (str/join "\n" [(str "FROM " base-image)
                          "WORKDIR /src"
                          "COPY project.clj ."
                          "RUN lein deps"]))
    (spit (io/file d "project.clj") (pr-str proj))
    (log "Building image" tag)
    (sh "docker" "build" "-t" tag "." :dir d)
    (log "Pushing image" tag)
    (sh "docker" "push" tag :dir d)
    tag))

(defn add-clj-container [pod cont deps constants code
                         & {:keys [source-file proj lein]
                            :or {source-file "src/main.clj"
                                 proj {}
                                 lein "run"}}]
  (let [projmap (-> {:dependencies deps
                     :main 'main}
                    (merge proj))
        proj (concat ['defproject 'myproj "0.0.1-SNAPSHOT"]
                     (mapcat identity projmap))
        code (concat [(first code)]
                     (for [[k v] constants]
                       (list 'def (-> k name symbol) v))
                     (rest code))
        code (str/join "\n" (map pr-str code))
        tag "clojure:lein-2.8.1"
        tag (if (nil? *docker-repo*)
            tag
            ;; else
            (create-clj-image tag proj))]
    (-> pod
        (lk/add-container cont tag)
        (lk/add-files-to-container cont (keyword (str (name cont) "-clj")) "/src"
                                   {"project.clj" (pr-str proj)
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

(defn add-itd-annotations [pod cls url]
  (-> pod
      (lk/add-annotation :class (.getName cls))
      (lk/add-annotation :jar url)))

(defn inject-driver [cont itf dep]
  (let [interface-name (-> itf
                           .getName
                           (str/replace "." "_")
                           (str/upper-case)
                           (keyword))]
    (lk/add-env cont {interface-name (json/write-str dep)})))
