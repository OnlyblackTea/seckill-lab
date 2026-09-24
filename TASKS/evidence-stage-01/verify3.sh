#!/usr/bin/env bash
# Reference-answer cross-check: run the canonical test against the solutions-branch files.
set -u
FIX=~/seckill-verify
R=$FIX/seckill-lab
S=$FIX/STATUS3.txt
: > "$S"

rm -rf "$FIX/refrepo2"
git init -q "$FIX/refrepo2"
cd "$FIX/refrepo2" || exit 1
git remote add src ~/workspace/_workspaces/ti-2
git fetch -q src 'refs/remotes/origin/*:refs/remotes/origin/*'
echo "--- fetched refs ---" >> "$S"
git for-each-ref --format='%(refname) %(objectname:short)' >> "$S"

if git checkout -q origin/solutions -- src/main/resources/mapper/ProductMapper.xml src/main/resources/db/migration/V1__stage01_product_search_index.sql; then
  echo "--- reference index DDL ---" >> "$S"
  grep -i "ADD INDEX" src/main/resources/db/migration/V1__stage01_product_search_index.sql >> "$S"
  echo "--- reference searchProducts ---" >> "$S"
  sed -n '/searchProducts/,/<\/select>/p' src/main/resources/mapper/ProductMapper.xml >> "$S"

  cp src/main/resources/mapper/ProductMapper.xml "$R/src/main/resources/mapper/ProductMapper.xml"
  cp src/main/resources/db/migration/V1__stage01_product_search_index.sql "$R/src/main/resources/db/migration/V1__stage01_product_search_index.sql"

  ( cd "$R" && timeout 2400 mvn -B -Dgroups=stage01 test ) > "$FIX/artifacts/mvn-reffix.log" 2>&1
  echo "reffix_mvn_exit=$?" >> "$S"
  grep -E "Tests run:|BUILD " "$FIX/artifacts/mvn-reffix.log" | tail -6 >> "$S"
  grep -A6 "Stage1 EXPLAIN" "$FIX/artifacts/mvn-reffix.log" >> "$S"

  n1=$(diff "$FIX/refrepo2/src/main/resources/mapper/ProductMapper.xml" "$FIX/ProductMapper.xml" | wc -l)
  n2=$(diff "$FIX/refrepo2/src/main/resources/db/migration/V1__stage01_product_search_index.sql" "$FIX/V1__stage01_product_search_index.sql" | wc -l)
  echo "mapper_diff_lines=$n1" >> "$S"
  echo "migration_diff_lines=$n2" >> "$S"
  echo "--- mapper diff (reference vs mine) ---" >> "$S"
  diff "$FIX/refrepo2/src/main/resources/mapper/ProductMapper.xml" "$FIX/ProductMapper.xml" >> "$S" 2>&1
else
  echo "reference checkout FAILED" >> "$S"
fi

echo "=== stage-01-product-query-index-sol tip ===" >> "$S"
git log --oneline -1 origin/stage-01-product-query-index-sol >> "$S" 2>&1
git show --stat --oneline origin/stage-01-product-query-index-sol >> "$S" 2>&1 | head -12
echo "=== status after ref run (does ref DDL match mine?) ===" >> "$S"
md5sum "$R/src/main/resources/db/migration/V1__stage01_product_search_index.sql" >> "$S"
cat "$S"
