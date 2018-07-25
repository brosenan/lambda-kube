Lambda-kube is a Clojure library for building inputs for Kubernetes.

# Documentation
* [Core Library](core.md)

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
1. Functions for [defining](core.md#basic-api-object-functions) API objects, such as Pods, Deployments, Services, etc.
2. Functions for [augmenting](core.md#modifier-functions) API object.
3. Functions for [defining _modules_](core.md#dependency-injection), supporting the gradual definition of a complete system, based on [Dependency Injection (DI)](https://en.wikipedia.org/wiki/Dependency_injection).



