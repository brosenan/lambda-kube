* [Clojure Nanoservices](#clojure-nanoservices)
```clojure
(ns lambdakube.util-test
  (:require [midje.sweet :refer :all]
            [lambdakube.core :as lk]
            [lambdakube.util :as lku]))

```
# Clojure Nanoservices

It is sometimes useful to create a "nanoservice", a service
consisting of only a few lines of code, from within a Lambda-Kube
library. Such nanoservices can be useful as it avoids the need for
building a dedicated Docker image. However, it is not meant to be
used for more than small and simple nanoservices (and tests), since
the code is written as a quoted s-expression, hindering the ability
to test it within the enclosing project.

The `add-clj-container` function careates a Clojure nanoservice in
a container, and adds it to a pod. It takes the following arguments:
1. The pod to augment.
2. The name of the container to create.
3. A leiningen-style dependencies vector (quoted).
4. A vector containing the nanoservice code (quoted).

The code should start with the `ns` macro, where the namespace
should always be `main`. A `-main` function needs to be implemented
as the nanoservice's entry-point.

The function adds a container based on the official `clojure` image
to the pod, and mounts two files to it, using a config-map. The
first file is a `project.clj` file, which contains the dependencies
and names the `main` namespace as the project's main
entry-point. The second file is `src/main.clj`, which contains the
nanoservice's code.
```clojure
(fact
 (-> (lk/pod :foo {:app :foo})
     (lku/add-clj-container :bar
                            '[[org.clojure/clojure "1.9.0"]
                              [aysylu/loom "1.0.1"]]
                            '[(ns main
                                (:require [clojure.string :as str]))
                              (defn -main []
                                (println "Hello, World"))]))
 => (let [proj (pr-str '(defproject myproj "0.0.1-SNAPSHOT"
                          :dependencies [[org.clojure/clojure "1.9.0"]
                                         [aysylu/loom "1.0.1"]]
                          :main main))
          code (str (pr-str '(ns main
                               (:require [clojure.string :as str])))
                    "\n"
                    (pr-str '(defn -main []
                                (println "Hello, World"))))]
      (-> (lk/pod :foo {:app :foo})
          (lk/add-container :bar "clojure:lein-2.8.1")
          (lk/add-files-to-container :bar :bar-clj "/src"
                                     {"project.clj" proj
                                      "src/main.clj" code})
          (lk/update-container :bar assoc :command
                               ["sh" "-c" "cp -r /src /work && cd /work && lein run"]))))

'(-> (lk/pod :foo {:app :foo})
    (lku/add-clj-container :bar '[[org.clojure/clojure "1.9.0"]]
                           '[(ns main)
                             (defn -main []
                               (println "Hello, World"))])
    (lk/job :Never)
    (lk/extract-additional)
    ((fn [x] (cons x (-> x meta :additional))))
    (lk/to-yaml)
    (println))

```

