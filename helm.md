Lambda-kube makes Helm redundant. Here is a description of why we
thing Lambda-kube provides a better solution.

# Text-based Templates
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

# Lack of Abstraction
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
