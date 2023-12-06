#!/bin/bash

# $1 = input file
# $2 = branching
# $3 = workers
function launch {
  java -jar target/minicpbp-1.0.jar --input "$1" --branching "$2" --search-type dfs --timeout 10800 --workers "$3" > /dev/null
}

function monitor {
  while :; do free -m | awk 'NR==2 {print $3}'; sleep 0.5; done
}

monitor > ram-dump &
pid=$!

branching="dom-wdeg"
instance=~/XCSPInstances/Langford/Langford-m1-k3/Langford-3-15.xml

sleep 5

launch $instance $branching 1
sleep 5

launch $instance $branching "$(nproc)"
sleep 5

instance=~/XCSPInstances/PigeonsPlus/PigeonsPlus-m1-s1/pigeonsPlus-08-07.xml

launch $instance $branching 1
sleep 5

launch $instance $branching "$(nproc)"
sleep 5

kill $pid