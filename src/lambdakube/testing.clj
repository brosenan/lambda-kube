(ns lambdakube.testing
  (:require [lambdakube.core :as lk]
            [clojure.java.shell :as sh]
            [clojure.data.json :as json]
            [clojure.data.xml :as xml]
            [clojure.string :as str]))

(defn test [$ test config deps func]
  (let [func' (fn [run-this-test & args]
                (-> (apply func args)
                    (update :metadata assoc :name :test)
                    (lk/job :Never {:backoffLimit 0})))]
    (-> $
        (update :tests assoc test config)
        (lk/rule test (vec (cons (keyword (str "run-" (name test))) deps)) func'))))

(defn- kubectl [& args]
  (let [ret (apply sh/sh "kubectl" args)]
      (when-not (= (:exit ret) 0)
        (throw (Exception. (:err ret))))
      (:out ret)))

(defn log [msg]
  (println msg))

(defn run-test [$ test prefix]
  (let [kns (str prefix "-" (name test))
        test-config (-> ((:tests $) test)
                        (assoc (keyword (str "run-" (name test))) true))
        depl (lk/get-deployable $ test-config)
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
        (kubectl "delete" "-f" filename)
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


(defn insert-stylesheet-pi [xml index]
  (str (subs xml 0 index)
       "<?xml-stylesheet href=\"https://raw.githubusercontent.com/xunit/xunit/master/src/xunit.console/HTML.xslt\"?>"
       (subs xml index)))

(defn to-xunit [res filename]
  (let [passed (->> res
                    (map second)
                    (filter #(= (:status %) :pass))
                    (count))
        failed (->> res
                    (map second)
                    (filter #(= (:status %) :fail))
                    (count))
        xml (xml/sexp-as-element [:assemblies
                          [:assembly {:name "lambda-kube tests"
                                      :passed passed
                                      :failed failed}
                           (vec (concat [:collection {:name "tests"
                                                      :passed passed
                                                      :failed failed}]
                                        (for [[k v] res]
                                          [:test {:name (name k)
                                                  :result (if (= (:status v) :pass)
                                                            "Pass"
                                                            ;; else
                                                            "Fail")}])))]])
        xmlstr (xml/indent-str xml)
        index (+ (str/index-of xmlstr "?>") 2)
        xmlstr (insert-stylesheet-pi xmlstr index)]
    (spit filename xmlstr))
  nil)

(defn kube-tests
  ([$ prefix]
   (kube-tests $ prefix (constantly true)))
  ([$ prefix pred]
   (let [res (run-tests $ prefix pred)]
     (to-xunit res (str prefix "-results.xml"))
     (->> res
          (map (fn [[k v]] (assoc v :log (str "Test " k "\n" (:log v) "\n==="))))
          (filter #(= (:status %) :fail))
          (map :log)
          (reduce str "")))))

