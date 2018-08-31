Lambda-kube is a Clojure library for building inputs for Kubernetes.

[![Clojars Project](https://img.shields.io/clojars/v/brosenan/lambdakube.svg)](https://clojars.org/brosenan/lambdakube)
[![Build Status](https://travis-ci.com/brosenan/lambda-kube.svg?branch=master)](https://travis-ci.com/brosenan/lambda-kube)

# Usage
Add lambdakube as a dependency to your `project.clj`, and
(preferably), [lein-auto](https://github.com/weavejester/lein-auto) as
a plugin.

`:require` the namespace:
```clojure
(:require [lambdakube.core :as lk]
          [clojure.java.io :as io])
```

Define a module function, defining the different parts of the system.
```clojure
;; A module function takes an injector ($) as parameter, and adds rules to it.
(defn module [$]
  (-> $
      ;; A rule defines a resource (:frontend) and dependencies ([:num-fe-replicas]).
      (lk/rule :frontend [:backend-master :backend-slave :num-fe-replicas]
               (fn [master slave num-replicas]
                 ;; We start with an empty pod.
                 (-> (lk/pod :frontend {:app :guesbook
                                        :tier :frontend})
                     ;; We add a container, specifying a name, image and environments.
                     (lk/add-container :php-redis "gcr.io/google-samples/gb-frontend:v4"
                                       (lk/add-env {}  {:GET_HOST_FROM :dns}))
                     ;; We load three files from resources and mount them to the container
                     (lk/add-files-to-container :php-redis :new-gb-fe-files "/var/www/html"
                                                (map-resources ["index.html" "controllers.js" "guestbook.php"]))
                     ;; Wait for the master and slave to come up
                     (lku/wait-for-service-port master :redis)
                     (lku/wait-for-service-port slave :redis)
                     ;; Then we wrap the pod with a deployment, specifying the number of replicas.
                     (lk/deployment num-replicas)
                     ;; Finally, we expose port 80 using a NodePort service.
                     (lk/expose-node-port :frontend (lk/port :php-redis :web 80)))))))
```

Define configuration.
```clojure
(def config
  {:num-fe-replicas 3})
```

Define a `-main` function.
```clojure
(defn -main []
  (-> (lk/injector)
      module
      lk/standard-descs
      (lk/get-deployable config)
      lk/to-yaml
      (lk/kube-apply (io/file "guestbook.yaml"))))
```

Run it:
```
$ lein auto run
```

It will create a YAML file (`my-app.yaml`) and call `kubectl apply` on
it. Then it will remain to watch your source files for changes, and
when changed, will re-apply automatically.

A complete example can be found [here](https://github.com/brosenan/lambdakube-example).

# Documentation
* [Core Library](core.md)
* [Utility Functions](util.md)
* [Test Framework](testing.md)

# Rationale
Kubernetes is a really great tool, which transforms the task of
deploying complex systems from an IT task to a software development
task. Lambda-kube takes this one step further and turns the generation of
the YAML files which describe the deployment declaratievly, from a
"configuration" task to a programming task.

Imagine you are developing a new database system. Your database system
consists of a few different kinds of nodes. Now imagine you wish to
allow your users to deploy this database as part of their software. A
good first step in this direction would be to package your software as
Docker images, one for each type of node. But a Docker image does not
answer the question of how these nodes should be connected to one
another.

To answer this question, you could, for example, provide example YAML
files for Kubernetes, to give the user a sense of what they need to do
in order to deploy your database as part of their system, given they
are using Kubernetes. However, the files you provide are no more than
an example. Eventually, the user will have to update these files to
match their needs.

Lambda-kube allows you to provide them a Clojure library, which generates
these YAML files according to the recipe you design, but matching the
parameters they provide. Being a Clojure library, it can be then
integrated in a library they write, which integrates your database
with their software, and potentially other microservices, each coming
with its own Lambda-kube-based library.

# Why Not Helm?
The above description matches the mission of the Helm project, and
Helm charts could indeed stand in place of Lambda-kube libraries. However,
the way I see it, Helm has two significant drawbacks.

## Text-based Templates
Helm charts use text-based templates to generate YAML files. While
this can work properly in simple cases, this can break horribly for
others.

For example, the template engine used by Helm does nothing to escape
string values. For example, if you provide a string value to a field
and that string value contains new-lines, these new-line characters
will break the YAML syntax.

Similarly, if you use a template to create a block (e.g., a map of
values), it is your responsibility to indent the output properly, or
else you will break the YAML syntax.

## Lack of Abstraction
One of the problems with Kubernetes YAML files to begin with, is their
verbosity. These files make extensive use of names, which are defined
in one place, and used in another. These names bloat up the YAML
files.

Helm does not provide a real answer for this bloat. It hides the bloat
in charts, but the charts are even more verbose than the YAML files
they produce. Wanting to account for every possibility, real-life
charts are bloated with many esoteric options, making them hard for
developers to understand them and maintain them.

The root cause for this is that Go Templates do not provide a good
abstraction mechanism. What you want to have is the ability to create
small things, each responsible for one thing, and to have the
mechanism to compose them together. Go Templates are not good at this,
but functional programming is.

# What Lambda-Kube Is
Lambda-Kube is a Clojure library. It contains three families of functions:
1. Functions for [defining API objects](core.md#basic-api-object-functions), such as Pods, Deployments, Services, etc.
2. Functions for [augmenting API objects](core.md#modifier-functions), adding things to them or updating their properties.
3. Functions for [defining _modules_](core.md#dependency-injection), supporting the gradual definition of a complete system, based on [Dependency Injection (DI)](https://en.wikipedia.org/wiki/Dependency_injection).

Lambda-Kube takes follows a few best practices made to make the systems you build with it maintainable.
1. It is purely functional. It uses no side-effects and even no macros. Just plain old Clojure functions.
3. Functions are simple and cohesive, intended to do one thing and to it well.
2. Functions are composable. Augmentation is always done on the first argument. This makes most functions compatible with Clojure's [threading macro](https://clojuredocs.org/clojure.core/-%3E) (->).

If a function we provide does not do exactly what you are looking for,
you can replace it, or better yet, augment it with your own. For
example, our `pod` function creates a very basic pod. It allows you to
add additional fields, but if there is a pattern you want in your
pods, and the `pod` function doesn't support it, you can (and should)
write an augmentation function to modify the pod in any way you want.
