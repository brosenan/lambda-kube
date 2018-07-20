#!/bin/bash

basedir=$(dirname $0)
docdir=$basedir
files=$(find $basedir -name "*_test.clj")
awkfile=$basedir/clj-to-md.awk

for file in $files; do
    target=$docdir/$(basename $file _test.clj).md
    awk -f $awkfile $file > $target
done
