BEGIN {code=1; FS=";; "; print "```clojure";}
/^[;][;] (.*)/ {if(code==1) {print "```"}; print $2; code=0}
/^ *$/ {print}
/^[^;]/ {if(code==0) {print "```clojure"}; print; code=1}
END {if(code) {print "```\n"}}
