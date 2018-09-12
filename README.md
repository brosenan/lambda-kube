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
          [lambdakube.util :as lku]
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
                                         (lk/add-env {} {:GET_HOST_FROM :env
                                                         :REDIS_MASTER_SERVICE_HOST (:hostname master)
                                                         :REDIS_SLAVE_SERVICE_HOST (:hostname slave)}))
                       ;; We load three files from resources and mount them to the container
                       (lk/add-files-to-container :php-redis :new-gb-fe-files "/var/www/html"
                                                  (map-resources ["index.html" "controllers.js" "guestbook.php"]))
                       ;; Wait for the master and slave to come up
                       (lku/wait-for-service-port master :redis)
                       (lku/wait-for-service-port slave :redis)
                       ;; Then we wrap the pod with a deployment, specifying the number of replicas.
                       (lk/deployment num-replicas)
                       ;; Finally, we expose port 80 using a NodePort service.
                       (lk/expose-cluster-ip :frontend (lk/port :php-redis :web 80 80)))))))
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
Kubernetes is great. With Kubernetes, the _imperative_ notion of
_installing_ software becomes a thing of the past, while the
_declarative_ notion of _describing_ how software is to be installed
becomes the way it is done. A declarative definition allows us,
developers, to specify just how we want our software installed, and to
have it under source control, so that we can install it once and
again, exactly the same way.

To allow us to do so, Kubernetes defines a declarative language -- a
language of _API Objects_. Today, these API objects are either written
by hands, typically in [YAML](http://yaml.org/) format, or rendered
using [Go templates](https://golang.org/pkg/text/template/) that come
as part of a [Helm](https://helm.sh/) chart.

While the language provided by Kubernetes exposes its entire API,
providing developers access to all its wealth, this language does not
provide one important thing -- _abstraction_.

In programming languages, abstraction mechanisms allow developers
define new concepts, and then use them (and reuse them) in different
contexts. _Functions_, for example, provide a powerful abstraction
mechanism in which some computation is given a name, allowing it to be
reused in different contexts, with different parameters.

Kubernetes allows us to define objects such
as
[Pods](https://kubernetes.io/docs/concepts/workloads/pods/pod/),
[Deployments](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/) and
[ConfigMaps](https://kubernetes.io/docs/tutorials/configuration/). These
are the building blocks provided by Kubernetes. But it doesn't give
you a mechanism to create your own blocks. This is Lambda-Kube comes
into the picture.

# What Is Lambda-Kube?
Lambda-Kube is a Clojure library. It contains functions, most of which
are purely-functional (have no side-effects). These functions allow
developers to build objects in the Kubernetes object language. These
objects can then be handed to Kubernetes, to turn them into a running
system.

Clojure is very good in making functions, and especially pure
functions, compose. Unlike the Kubernetes object language, in which
object definitions are often big, allowing many options, Lambda-Kube
functions are designed to be simple, but are designed to compose well,
following
the
[Unix Philosophy](https://en.wikipedia.org/wiki/Unix_philosophy). 

Lambda-Kube provides a collection of functions that represent the
major Kubernetes objects and some patterns regarding them, but users
are encouraged to extend this library with their own functions, adding
functionality Lambda-Kube does not address.

# Is Lambda-Kube A Helm Replacement?
Well, yes and no.

No, because Helm is considered a "_package manager for Kubernetes_",
while Lambda-Kube is a library for building Kubernetes
objects. However, by generating Kubernetes objects, Lambda-Kube
provides an alternative to Helm's use of Go
templates. [Here is why we believe this alternative is better](helm.md).

Additionally, it allows users to write reusable Clojure libraries that
describe the deployment of services. As such, they can play the
role [charts](https://docs.helm.sh/developing_charts/) play in
Helm. Clojure already has a few good package managers
(e.g., [Leiningen](https://leiningen.org/)
and [Boot](https://github.com/boot-clj/boot)), which can install such
libraries on demand. This makes _them_ (and not Lambda-Kube itself),
Helm-replacements.

# What Lambda-Kube Is
Lambda-Kube is a Clojure library. Its [core namespace](core.md) contains three
families of functions:
1. Functions for [defining API objects](core.md#basic-api-object-functions), such as Pods, Deployments, Services, etc.
2. Functions for [augmenting API objects](core.md#modifier-functions), adding things to them or updating their properties.
3. Functions for [defining _modules_](core.md#dependency-injection), supporting the gradual definition of a complete system, based on [Dependency Injection (DI)](https://en.wikipedia.org/wiki/Dependency_injection).

In addition, it has a [utility namespace](util.md), which provides
functions for common patters, and a [testing framework](testing.md),
which facilitates the definition of integration tests.

Lambda-Kube takes follows a few best practices made to make the
systems you build with it maintainable.
1. It is purely functional. It uses no side-effects and even no macros. Just plain old Clojure functions (with the exception of functions that actually interact with Kubernetes).
3. Functions are simple and cohesive, intended to do one thing and to it well.
2. Functions are composable. Augmentation is always done on the first argument. This makes most functions compatible with Clojure's [threading macro](https://clojuredocs.org/clojure.core/-%3E) (->).

# What Lambda-Kube is Not

Lambda-Kube does not aspire to:
1. Be Comprehensive. If you find functionality that is missing, feel free to write it yourself. If you feel it can be useful to others, please open a PR.
2. Validate it Outputs. Kubernetes will always be better than anyone in validating its input. We do not attempt to replicate its logic. Lambda-Kube will allow you to build invalid objects, and it's up to you to make sure you get it right. Fortunately, it does make it easy to test things as you write them, by using `lein auto run`.

## License

Copyright © 2018 Boaz Rosenan

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
