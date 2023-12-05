#!/bin/bash

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

mkdir -p results
for file in "${files[@]}"; do
  instance=$(echo "$file" | sed 's/.*\///' | sed 's/\..*//')

  echo "Running parallel $instance"
  timeout -v 3660 /usr/bin/time -f %e java -jar target/minicpbp-1.0.jar --input "$file" --branching dom-wdeg --search-type dfs --timeout 3600 --workers 6 > /dev/null 2> results/results-parallel-"$instance".time
done

# iterate 5 by 5 and launch 5 instances in parallel
imax=$((${#files[@]}/5))
for i in $(seq 0 $imax); do
  for j in {0..4}; do
    # break if we are out of bounds
    if [ $((i * 5 + j)) -ge ${#files[@]} ]; then
      break
    fi

    file=${files[$((i * 5 + j))]}
    instance=$(echo "$file" | sed 's/.*\///' | sed 's/\..*//')

    echo "Running sequential $instance"
    timeout -v 3660 /usr/bin/time -f %e java -jar target/minicpbp-1.0.jar --input "$file" --branching dom-wdeg --search-type dfs --timeout 3600 --workers 1 > /dev/null 2> results/results-sequential-"$instance".log &
  done
  wait
done
