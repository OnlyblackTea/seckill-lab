#!/usr/bin/env bash
# One-shot verification: all stage groups + sim in a single Maven run.
# usage: verify-one.sh <tarball> <tag>
set -u
TAR=$1
TAG=$2
BASE=$HOME/verify-one-$TAG
R=$BASE/seckill-lab
ART=$BASE/artifacts
S=$BASE/STATUS.txt
rm -rf "$BASE"
mkdir -p "$BASE" "$ART"
tar -xf "$TAR" -C "$BASE"
: > "$S"
log() { echo "$*" >> "$S"; }

log "=== tag=$TAG tarball=$TAR ==="
log "Transactional(solution marker): $(grep -c '^    @Transactional' "$R/src/main/java/com/devteam/seckill/service/SeckillService.java")"
log "updateStock call(skeleton marker): $(grep -c 'productMapper.updateStock' "$R/src/main/java/com/devteam/seckill/service/SeckillService.java")"
log "RedisLockService present: $(test -f "$R/src/main/java/com/devteam/seckill/service/RedisLockService.java" && echo yes || echo no)"
log "V1 ADD INDEX: $(grep -c 'ADD INDEX' "$R/src/main/resources/db/migration/V1__stage01_product_search_index.sql")"
log "V2 CREATE TABLE: $(grep -c 'CREATE TABLE IF NOT EXISTS' "$R/src/main/resources/db/migration/V2__stage04_order_stat.sql")"

find "$R/src" -type f \( -name '*.java' -o -name '*.xml' -o -name '*.yml' -o -name '*.sql' \) -print0 | xargs -0 -r sed -i 's/\r$//'
( cd "$R/docker" && timeout 200 docker compose down -v ) > "$ART/down.txt" 2>&1
( cd "$R/docker" && timeout 900 docker compose up -d --wait ) > "$ART/up.txt" 2>&1
log "compose_up_exit=$?"
c=0
for i in $(seq 1 120); do
  if docker exec seckill_mysql mysql -uroot -pbagu1234 -h 127.0.0.1 -N -B \
       -e "SELECT COUNT(*) FROM seckill_lab.product;" > /tmp/cnt.txt 2>/dev/null; then
    c=$(tr -d '[:space:]' < /tmp/cnt.txt)
    if [ -n "$c" ] && [ "$c" -ge 50003 ] 2>/dev/null; then break; fi
  fi
  sleep 5
done
log "seed_count=$c"

# one Maven run, all groups in the same JVM (context cached, far faster than per-group runs)
( cd "$R" && timeout 3000 mvn -B -Dgroups=stage00,stage01,stage02,stage03,stage04,stage05,stage06,sim -Dtest.excludedGroups= test ) > "$ART/mvn-all.log" 2>&1
log "mvn_all_exit=$?"

echo "---- per-class results ----" >> "$S"
grep -E "Tests run:.*in com\.devteam" "$ART/mvn-all.log" >> "$S"
echo "---- totals ----" >> "$S"
grep -E "^\[INFO\] Tests run:|^\[ERROR\] Tests run:|BUILD (SUCCESS|FAILURE)" "$ART/mvn-all.log" | tail -4 >> "$S"
echo "---- failures ----" >> "$S"
grep -E "<<< (FAILURE|ERROR)|^\[ERROR\]   [A-Za-z]|AssertionFailedError|expected:|BindingException|UnsupportedOperationException" "$ART/mvn-all.log" | head -25 >> "$S"
echo "---- sim output ----" >> "$S"
grep -E "成功下单|售罄|重复下单|未实现|最终库存|落库订单数|并发线程数" "$ART/mvn-all.log" | head -12 >> "$S"
echo "---- stage05 stampede ----" >> "$S"
grep -E "DB 加载次数" "$ART/mvn-all.log" | head -3 >> "$S"
cat "$S"
