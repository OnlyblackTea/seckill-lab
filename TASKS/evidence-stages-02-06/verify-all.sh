#!/usr/bin/env bash
# Stage 2-6 verification (iteration): re-extract, compile, run all stage tests + sim.
set -u
BASE=$HOME/seckill-verify-all
R=$BASE/seckill-lab
ART=$BASE/artifacts
S=$BASE/STATUS.txt
rm -rf "$R" "$ART"
mkdir -p "$BASE" "$ART"
tar -xf "$HOME/seckill-all.tar" -C "$BASE"
: > "$S"
log() { echo "$*" >> "$S"; }
say() { echo "### $*"; }

find "$R/src" -type f \( -name '*.java' -o -name '*.xml' -o -name '*.yml' -o -name '*.sql' \) -print0 | xargs -0 -r sed -i 's/\r$//'
log "re-extracted and CRLF-normalized"

say "0. infra status"
( cd "$R/docker" && docker compose up -d --wait ) > "$ART/compose-up.txt" 2>&1
log "compose_up_exit=$?"
c=0
for i in $(seq 1 60); do
  if docker exec seckill_mysql mysql -uroot -pbagu1234 -h 127.0.0.1 -N -B \
       -e "SELECT COUNT(*) FROM seckill_lab.product;" > /tmp/cnt.txt 2>/dev/null; then
    c=$(tr -d '[:space:]' < /tmp/cnt.txt)
    if [ -n "$c" ] && [ "$c" -ge 50003 ] 2>/dev/null; then break; fi
  fi
  sleep 5
done
log "seed_count=$c"
docker exec seckill_redis redis-cli -p 6379 ping >> "$S" 2>&1

say "1. compile"
( cd "$R" && timeout 2400 mvn -B -DskipTests compile ) > "$ART/compile.log" 2>&1
log "compile_exit=$?"
grep -E "BUILD |ERROR.*\.java" "$ART/compile.log" | head -20 >> "$S"
if ! grep -q "BUILD SUCCESS" "$ART/compile.log"; then
  say "COMPILE FAILED - stopping"
  tail -40 "$S"
  exit 1
fi

say "2. per-stage acceptance tests"
for g in stage00 stage01 stage02 stage03 stage04 stage05 stage06; do
  ( cd "$R" && timeout 2400 mvn -B -Dgroups="$g" test ) > "$ART/mvn-$g.log" 2>&1
  rc=$?
  line=$(grep -E "Tests run:.*Failures" "$ART/mvn-$g.log" | tail -1)
  build=$(grep -E "BUILD (SUCCESS|FAILURE)" "$ART/mvn-$g.log" | tail -1)
  log "$g: exit=$rc | $line | $build"
done

say "3. concurrency simulator"
( cd "$R" && timeout 2400 mvn -B -Dgroups=sim -Dtest.excludedGroups= test ) > "$ART/mvn-sim.log" 2>&1
log "sim: exit=$?"
grep -E "Tests run:|BUILD (SUCCESS|FAILURE)|成功下单|售罄|最终库存|落库订单数" "$ART/mvn-sim.log" | tail -12 >> "$S"

say "4. failure details"
for g in stage00 stage01 stage02 stage03 stage04 stage05 stage06 sim; do
  if grep -qE "<<< (FAILURE|ERROR)" "$ART/mvn-$g.log" 2>/dev/null; then
    echo "---- $g ----" >> "$S"
    grep -E "<<< (FAILURE|ERROR)|^\[ERROR\]   [A-Za-z]|expected|Caused by" "$ART/mvn-$g.log" | head -25 >> "$S"
  fi
done

say "5. per-stage case list"
for g in stage00 stage01 stage02 stage03 stage04 stage05 stage06; do
  echo "== $g ==" >> "$S"
  grep -E "Tests run:.*in com\.devteam" "$ART/mvn-$g.log" 2>/dev/null | tail -3 >> "$S"
done

say "DONE"
cat "$S"
