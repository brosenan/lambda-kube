#!/bin/bash

basedir=$(dirname $0)
docdir=$basedir
files=$(find $basedir -name "*_test.clj")
awkfile=$basedir/clj-to-md.awk

for file in $files; do
    target=$docdir/$(basename $file _test.clj).md
    grep "^;; #" $file | sed "s/#/  /g" | sed "s/;;    \( *\)/\1* /" | perl -p -e "s/[*] (.*)/* [\1](#\L\1)/" | perl -p -e "s/(#[^ ]*) /\1-/g" | perl -p -e "s/(-[^ ]*) /\1-/g" | perl -p -e "s/(-[^ ]*) /\1-/g" | perl -p -e "s/(-[^ ]*) /\1-/g" > .tmp
    awk -f $awkfile $file >> .tmp
    mv .tmp $target
done
