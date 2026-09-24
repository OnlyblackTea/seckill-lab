#!/usr/bin/env bash
# Stage 1 verification driver — runs ON the VM (10.32.10.102).
# Matrix: baseline RED -> my implementation GREEN -> reference-solution cross-check,
# plus EXPLAIN before/after and the leftmost-prefix / filesort experiments.
set -u

R=~/seckill-verify/seckill-lab
FIX=~/seckill-verify
ART=$FIX/artifacts
MAPPER=$R/src/main/resources/mapper/ProductMapper.xml
MIG=$R/src/main/resources/db/migration/V1__stage01_product_search_index.sql
STATS="$FIX/STATUS.txt"
: > "$STATS"
rm -rf "$ART"; mkdir -p "$ART"

log() { echo "$*" >> "$STATS"; }
say() { echo "### $*"; }

# MySQL one-liners live in files (keeps quoting out of the ssh command line).
cat > "$FIX/q-reset.sql" <<'SQL'
DROP DATABASE IF EXISTS seckill_lab;
SQL
cat > "$FIX/q-count.sql" <<'SQL'
SELECT COUNT(*) AS rows_in_product FROM seckill_lab.product;
SHOW TABLES FROM seckill_lab;
SQL
cat > "$FIX/q-explain.sql" <<'SQL'
EXPLAIN SELECT id, name, category_id, status, price, stock FROM product
 WHERE category_id = 10 AND status = 1 AND price BETWEEN 50 AND 200
 ORDER BY price ASC LIMIT 20;
SQL
cat > "$FIX/q-explain-reordered.sql" <<'SQL'
EXPLAIN SELECT id, name, category_id, status, price, stock FROM product
 WHERE category_id = 10 AND price BETWEEN 50 AND 200 AND status = 1
 ORDER BY price ASC LIMIT 20;
SQL
cat > "$FIX/q-matching.sql" <<'SQL'
SELECT COUNT(*) AS matching_rows FROM product
 WHERE category_id = 10 AND status = 1 AND price BETWEEN 50 AND 200;
SQL
cat > "$FIX/q-showindex.sql" <<'SQL'
SHOW INDEX FROM product;
SQL

mysql_f() { docker exec -i seckill_mysql mysql -uroot -pbagu1234 -h 127.0.0.1 seckill_lab < "$1" 2>&1; }
mysql_s() { printf '%s\n' "$1" > "$FIX/q-tmp.sql"; mysql_f "$FIX/q-tmp.sql"; }

# ---------- 0. normalize line endings in the transferred tree ----------
find "$R/src" -type f \( -name '*.java' -o -name '*.xml' -o -name '*.yml' -o -name '*.sql' \) -print0 \
  | xargs -0 -r sed -i 's/\r$//'
log "== 0. tree ready; CRLF normalized =="
log "mapper_md5_before=$(md5sum "$MAPPER" | cut -d' ' -f1)"
log "migration_md5_before=$(md5sum "$MIG" | cut -d' ' -f1)"
log "canonical_test_md5=$(md5sum "$R/src/test/java/com/devteam/seckill/stage01/ProductQueryIndexAcceptanceTest.java" | cut -d' ' -f1)"

# ---------- 1. pristine infra ----------
say "1. reset volumes, then docker compose up -d --wait"
( cd "$R/docker" && timeout 180 docker compose down -v ) > "$ART/compose-down.txt" 2>&1
log "compose_down_exit=$?"
( cd "$R/docker" && timeout 900 docker compose up -d --wait ) > "$ART/compose-up.txt" 2>&1
log "compose_up_exit=$?"
( cd "$R/docker" && docker compose ps ) >> "$ART/compose-up.txt" 2>&1

say "1b. wait for seed data"
c=0
for i in $(seq 1 120); do
  if docker exec seckill_mysql mysql -uroot -pbagu1234 -h 127.0.0.1 -N -B \
       -e "SELECT COUNT(*) FROM seckill_lab.product;" > /tmp/cnt.txt 2>/dev/null; then
    c=$(tr -d '[:space:]' < /tmp/cnt.txt)
    if [ -n "$c" ] && [ "$c" -ge 50003 ] 2>/dev/null; then
      log "seed_count=$c (iteration $i)"; break
    fi
  fi
  sleep 5
done
log "seed_count_final=$c"

echo "== indexes on product BEFORE (pristine) ==" >> "$STATS"
mysql_f "$FIX/q-showindex.sql" >> "$STATS"
echo "== row count / tables ==" >> "$STATS"
mysql_f "$FIX/q-count.sql" >> "$STATS"

say "1c. baseline EXPLAIN (no secondary index)"
mysql_f "$FIX/q-explain.sql" > "$ART/explain-before.txt"
cat "$ART/explain-before.txt"
cat "$ART/explain-before.txt" >> "$STATS"

# ---------- 2. RED: untouched skeleton ----------
say "2. RED run — searchProducts unbound + no index"
( cd "$R" && timeout 2400 mvn -B -Dgroups=stage01 test ) > "$ART/mvn-red.log" 2>&1
log "red_mvn_exit=$?"
rm -rf "$ART/surefire-red"; cp -r "$R/target/surefire-reports" "$ART/surefire-red" 2>/dev/null
echo "== RED maven summary ==" >> "$STATS"
grep -E "Tests run:|BUILD |Invalid bound statement" "$ART/mvn-red.log" | tail -12 >> "$STATS"
echo "== RED surefire cases ==" >> "$STATS"
grep -hE "Tests run|<<< (FAILURE|ERROR)" "$ART/surefire-red"/*.txt 2>/dev/null | head -12 >> "$STATS"
grep -ho "Invalid bound statement (not found)[^<]*" "$ART/surefire-red"/*.txt 2>/dev/null | head -2 >> "$STATS"
grep -ho "EXPLAIN 的 key 为 NULL[^<]*\|type=ALL[^<]*\|全表扫描[^<]*" "$ART/surefire-red"/*.txt 2>/dev/null | head -3 >> "$STATS"

# ---------- 3. GREEN: my implementation ----------
say "3. GREEN run — my searchProducts + idx_product_search(category_id,status,price)"
cp "$FIX/ProductMapper.xml" "$MAPPER"
cp "$FIX/V1__stage01_product_search_index.sql" "$MIG"
log "patched_mapper_md5=$(md5sum "$MAPPER" | cut -d' ' -f1)"
log "patched_migration_md5=$(md5sum "$MIG" | cut -d' ' -f1)"
( cd "$R" && timeout 2400 mvn -B -Dgroups=stage01 test ) > "$ART/mvn-green.log" 2>&1
log "green_mvn_exit=$?"
rm -rf "$ART/surefire-green"; cp -r "$R/target/surefire-reports" "$ART/surefire-green" 2>/dev/null
echo "== GREEN maven summary ==" >> "$STATS"
grep -E "Tests run:|BUILD " "$ART/mvn-green.log" | tail -8 >> "$STATS"
echo "== GREEN surefire cases ==" >> "$STATS"
grep -hE "Tests run" "$ART/surefire-green"/*.txt 2>/dev/null | head -6 >> "$STATS"
echo "== GREEN in-test EXPLAIN dump ==" >> "$STATS"
grep -A6 "Stage1 EXPLAIN" "$ART/mvn-green.log" >> "$STATS"

say "3b. EXPLAIN with the index in place"
mysql_f "$FIX/q-explain.sql" > "$ART/explain-after.txt"
cat "$ART/explain-after.txt" >> "$STATS"
echo "== indexes on product AFTER ==" >> "$STATS"
mysql_f "$FIX/q-showindex.sql" >> "$STATS"

say "3c. leftmost-prefix experiment: (category_id, price, status)"
mysql_s "ALTER TABLE product ADD INDEX idx_wrong_order (category_id, price, status);" > /dev/null 2>&1
mysql_f "$FIX/q-explain.sql" > "$ART/explain-wrong-order.txt"
echo "== EXPLAIN with (category_id, price, status) ==" >> "$STATS"
cat "$ART/explain-wrong-order.txt" >> "$STATS"
mysql_s "ALTER TABLE product DROP INDEX idx_wrong_order;" > /dev/null 2>&1

say "3d. naive order (price first): index unusable for the equality filters"
mysql_s "ALTER TABLE product ADD INDEX idx_price_first (price, category_id, status);" > /dev/null 2>&1
mysql_f "$FIX/q-explain.sql" > "$ART/explain-price-first.txt"
echo "== EXPLAIN with (price, category_id, status) ==" >> "$STATS"
cat "$ART/explain-price-first.txt" >> "$STATS"
mysql_s "ALTER TABLE product DROP INDEX idx_price_first;" > /dev/null 2>&1

say "3e. row-count sanity for the canonical filter"
mysql_f "$FIX/q-matching.sql" >> "$STATS"
mysql_f "$FIX/q-explain-reordered.sql" > "$ART/explain-where-reordered.txt"
echo "== EXPLAIN with WHERE terms reordered (same index) ==" >> "$STATS"
cat "$ART/explain-where-reordered.txt" >> "$STATS"

# ---------- 4. cross-check: reference solution from solutions branch ----------
say "4. cross-check against the solutions-branch reference answer"
rm -rf "$FIX/refrepo"
if git clone -q "$R" "$FIX/refrepo" 2>"$ART/refclone.err"; then
  if ( cd "$FIX/refrepo" && git checkout -q origin/solutions -- src/main/resources/mapper/ProductMapper.xml src/main/resources/db/migration/V1__stage01_product_search_index.sql ) 2>>"$ART/refclone.err"; then
    log "reference_ref=$(cd "$FIX/refrepo" && git log --oneline -1 origin/solutions)"
    echo "== reference index DDL ==" >> "$STATS"
    grep -i "ADD INDEX" "$FIX/refrepo/src/main/resources/db/migration/V1__stage01_product_search_index.sql" >> "$STATS"
    cp "$FIX/refrepo/src/main/resources/mapper/ProductMapper.xml" "$MAPPER"
    cp "$FIX/refrepo/src/main/resources/db/migration/V1__stage01_product_search_index.sql" "$MIG"
    ( cd "$R" && timeout 2400 mvn -B -Dgroups=stage01 test ) > "$ART/mvn-reffix.log" 2>&1
    log "reffix_mvn_exit=$?"
    echo "== REFERENCE-FIX maven summary ==" >> "$STATS"
    grep -E "Tests run:|BUILD " "$ART/mvn-reffix.log" | tail -8 >> "$STATS"
    say "4b. diff: reference vs mine"
    diff "$FIX/refrepo/src/main/resources/mapper/ProductMapper.xml" "$FIX/ProductMapper.xml" > "$ART/diff-mapper.txt" 2>&1
    log "mapper_diff_lines=$(wc -l < "$ART/diff-mapper.txt")"
    diff "$FIX/refrepo/src/main/resources/db/migration/V1__stage01_product_search_index.sql" "$FIX/V1__stage01_product_search_index.sql" > "$ART/diff-mig.txt" 2>&1
    log "migration_diff_lines=$(wc -l < "$ART/diff-mig.txt")"
  else
    log "reference checkout failed; see refclone.err"; cat "$ART/refclone.err" >> "$STATS"
  fi
else
  log "scratch clone failed; reference cross-check skipped"; cat "$ART/refclone.err" >> "$STATS"
fi

# ---------- 5. artifact digests ----------
say "5. artifacts"
( cd "$ART" && sha256sum *.txt *.log > "$ART/CHECKSUMS.txt" 2>/dev/null )
log "artifacts_listing:"; ls -l "$ART" >> "$STATS"
log "=== END ==="
say "DONE"
