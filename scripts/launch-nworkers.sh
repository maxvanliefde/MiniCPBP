#!/bin/bash

files=(
  ~/XCSPInstances/ColouredQueens/ColouredQueens-m1-s1/ColouredQueens-07.xml
  ~/XCSPInstances/DeBruijnSequence/DeBruijnSequence-m1-s1/DeBruijnSequence-03-03.xml
)
mkdir -p results-nworkers

max_workers=$(($(nproc) * 2))
for file in "${files[@]}"; do
  instance=$(echo "$file" | sed 's/.*\///' | sed 's/\..*//')
  result_file="results-nworkers/results-nworkers-$instance.csv"
  echo "nworkers,time1,time2,time3,time4,time5" > "$result_file"
  for nworkers in $(seq 1 "$max_workers"); do
    echo -n "Running $nworkers workers on $instance"
    echo -n "$nworkers" >> "$result_file"
    for i in $(seq 1 5); do
      echo -n "."
      time=$(timeout -v 3660 /usr/bin/time -f %e java -jar target/minicpbp-1.0.jar --input "$file" --branching dom-wdeg --search-type dfs --timeout 3600 --workers "$nworkers" 2>&1 > /dev/null)
      echo -n ",$time" >> "$result_file"
    done
    echo "" >> "$result_file"
    echo " done"
  done
done