#!/usr/bin/env bash
# Stage 1 follow-up: reference-answer cross-check + cleaner index experiments.
set -u
R=~/seckill-verify/seckill-lab
FIX=~/seckill-verify
ART=$FIX/artifacts
MAPPER=$R/src/main/resources/mapper/ProductMapper.xml
MIG=$R/src/main/resources/db/migration/V1__stage01_product_search_index.sql
S=$FIX/STATUS2.txt
: > "$S"
log() { echo "$*" >> "$S"; }
say() { echo "### $*"; }
mysql_s() { printf '%s\n' "$1" > "$FIX/q-tmp.sql"; docker exec -i seckill_mysql mysql -uroot -pbagu1234 -h 127.0.0.1 seckill_lab < "$FIX/q-tmp.sql" 2>&1 | grep -v "Warning] Using a password"; }

EXPLAIN="EXPLAIN SELECT id, name, category_id, status, price, stock FROM product WHERE category_id = 10 AND status = 1 AND price BETWEEN 50 AND 200 ORDER BY price ASC LIMIT 20;"

say "A. seed population sanity (is the canonical filter meaningful?)"
mysql_s "SELECT category_id, COUNT(*) c FROM product GROUP BY category_id ORDER BY c DESC LIMIT 5;" >> "$S"

say "B. isolate experiments (drop my index first)"
mysql_s "ALTER TABLE product DROP INDEX idx_product_search;"
mysql_s "ALTER TABLE product ADD INDEX idx_wrong_order (category_id, price, status);" > /dev/null
log "-- only (category_id, price, status) present --"
mysql_s "$EXPLAIN" >> "$S"
mysql_s "ALTER TABLE product DROP INDEX idx_wrong_order;"
mysql_s "ALTER TABLE product ADD INDEX idx_price_first (price, category_id, status);" > /dev/null
log "-- only (price, category_id, status) present --"
mysql_s "$EXPLAIN" >> "$S"
mysql_s "ALTER TABLE product DROP INDEX idx_price_first;"
mysql_s "ALTER TABLE product ADD INDEX idx_cover (category_id, status, price, id, name, stock);" > /dev/null
log "-- covering variant (category_id, status, price, id, name, stock): Extra should say Using index --"
mysql_s "$EXPLAIN" >> "$S"
mysql_s "ALTER TABLE product DROP INDEX idx_cover;"

say "C. reference answer from origin/solutions of the ti-2 clone"
rm -rf "$FIX/refrepo"
if git clone -q --no-checkout ~/workspace/_workspaces/ti-2 "$FIX/refrepo" 2>"$ART/refclone2.err"; then
  if ( cd "$FIX/refrepo" && git checkout -q origin/solutions -- src/main/resources/mapper/ProductMapper.xml src/main/resources/db/migration/V1__stage01_product_search_index.sql ) 2>>"$ART/refclone2.err"; then
    log "reference_ref=$(cd "$FIX/refrepo" && git log --oneline -1 origin/solutions)"
    log "-- reference index DDL --"
    grep -i "ADD INDEX\|idx_product_search" "$FIX/refrepo/src/main/resources/db/migration/V1__stage01_product_search_index.sql" >> "$S"
    cp "$FIX/refrepo/src/main/resources/mapper/ProductMapper.xml" "$MAPPER"
    cp "$FIX/refrepo/src/main/resources/db/migration/V1__stage01_product_search_index.sql" "$MIG"
    ( cd "$R" && timeout 2400 mvn -B -Dgroups=stage01 test ) > "$ART/mvn-reffix.log" 2>&1
    log "reffix_mvn_exit=$?"
    grep -E "Tests run:|BUILD " "$ART/mvn-reffix.log" | tail -6 >> "$S"
    grep -A6 "Stage1 EXPLAIN" "$ART/mvn-reffix.log" >> "$S"
    diff "$FIX/refrepo/src/main/resources/mapper/ProductMapper.xml" "$FIX/ProductMapper.xml" > "$ART/diff-mapper.txt" 2>&1
    log "mapper_diff_lines=$(wc -l < "$ART/diff-mapper.txt")"
    diff "$FIX/refrepo/src/main/resources/db/migration/V1__stage01_product_search_index.sql" "$FIX/V1__stage01_product_search_index.sql" > "$ART/diff-mig.txt" 2>&1
    log "migration_diff_lines=$(wc -l < "$ART/diff-mig.txt")"
  else
    log "reference checkout failed"; cat "$ART/refclone2.err" >> "$S"
  fi
else
  log "clone failed"; cat "$ART/refclone2.err" >> "$S"
fi

say "DONE2"
