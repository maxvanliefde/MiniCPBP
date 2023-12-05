#!/bin/bash

# choose the instances to run
files=(
  ~/XCSPInstances/ColouredQueens/ColouredQueens-m1-s1/ColouredQueens-06.xml
  ~/XCSPInstances/ColouredQueens/ColouredQueens-m1-s1/ColouredQueens-07.xml
  ~/XCSPInstances/DeBruijnSequence/DeBruijnSequence-m1-s1/DeBruijnSequence-03-03.xml
  ~/XCSPInstances/CostasArray/CostasArray-m1-s1/CostasArray-12.xml
  ~/XCSPInstances/QueensKnights/QueensKnights-m1-s1/QueensKnights-020-05-mul.xml
  ~/XCSPInstances/SchurrLemma/SchurrLemma-mod-s1/SchurrLemma-100-9-mod.xml
  ~/XCSPInstances/QueenAttacking/QueenAttacking-m1-s1/QueenAttacking-05.xml
  ~/XCSPInstances/PigeonsPlus/PigeonsPlus-m1-s1/pigeonsPlus-08-07.xml
  ~/XCSPInstances/Langford/Langford-m1-k3/Langford-3-15.xml
)

# choose a branching heuristic
branching="first-fail-random-value"
#branching="dom-wdeg"

dir="results-speedups-$branching"
echo "Results are stored in $dir"
mkdir -p $dir

### PARALLEL
echo "instance,time1,time2,time3,time4,time5" > "$dir/results-parallel.csv"
for file in "${files[@]}"; do
  instance=$(echo "$file" | sed 's/.*\///' | sed 's/\..*//')

  echo "Running parallel $instance"
  echo -n "$instance" >> "$dir/results-parallel.csv"
  for i in {0..5}; do
    echo -n "."
    time=$(timeout -v 3660 /usr/bin/time -f %e java -jar target/minicpbp-1.0.jar --input "$file" --branching $branching --search-type dfs --timeout 3600 --workers "$(nproc)" 2>&1 > /dev/null)
    echo -n ",$time" >> "$dir/results-parallel.csv"
  done
  echo " done"
  echo "" >> "$dir/results-parallel.csv"
done

### SEQUENTIAL
# the 5 instances are run in parallel
for file in "${files[@]}"; do
  instance=$(echo "$file" | sed 's/.*\///' | sed 's/\..*//')

  echo "Running sequential $instance (5 times)....."
  for i in {0..5}; do
    timeout -v 3660 /usr/bin/time -f %e java -jar target/minicpbp-1.0.jar --input "$file" --branching $branching --search-type dfs --timeout 3600 --workers 1 > /dev/null 2> "$dir/results-sequential-$instance-$i.csv" &
  done
  echo " done"
  wait
done
