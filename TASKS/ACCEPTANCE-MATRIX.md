# Stage 2–6 真机验收测试清单（交给学员/维护者执行）

> 被测对象是 **git 分支**，不是某个工作区。跑法：先把 ref 取到本地，再按阶段跑。
> 平台的 Forge 也是这么取代码的（`base_ref = origin/<stage目录名>`）。

## 0. 环境前置

```bash
cd docker && docker compose up -d && docker compose ps      # MySQL 13306 + Redis 16379 都 healthy
# 需要 JDK 17 + Maven；测试连真 MySQL/Redis（项目刻意不用 H2/Testcontainers）
```

## 1. 骨架分支：应当**红**（学员还没动手）

| 分支 | 命令 | 预期 |
|---|---|---|
| `stage-01-product-query-index` | `mvn test -Dgroups=stage01` | 红：`searchProducts` 未绑定 + EXPLAIN `type=ALL/key=NULL` |
| `stage-02-order-transaction` | `mvn test -Dgroups=stage02` | 红：重复下单失败后**库存没回滚** |
| `stage-03-oversell-and-lock` | `mvn test -Dgroups=stage03` | 红：**超卖**（`expected: <10> but was: <30>` 这类形状） |
| `stage-04-isolation-and-mvcc` | `mvn test -Dgroups=stage04` | 红：`order_stat` 不存在 / 聚合 SQL 未绑定 |
| `stage-05-cache-triad` | `mvn test -Dgroups=stage05` | 红：`getProductCached` 抛 `UnsupportedOperationException` |
| `stage-06-deadlock-and-lock` | `mvn test -Dgroups=stage06` | 红：`LockServiceImpl.tryLock` 抛 `UnsupportedOperationException` |

外加并发指标（对应 Stage 3 的 `grader.concurrency`，在 **`stage-03` 分支或其解分支**上跑）：

```bash
mvn test -Dgroups=sim -Dtest.excludedGroups=
# 参考解预期：成功 10 / 售罄 90 / 最终库存 0 / 订单 10 —— 零超卖
```

## 2. 参考解分支：应当**绿**（每个分支只实现到自己那一段）

| 分支 | 命令 | 预期 |
|---|---|---|
| `stage-01-product-query-index-sol` | `mvn test -Dgroups=stage01` | 绿（2 用例） |
| `stage-02-order-transaction-sol` | `mvn test -Dgroups=stage02` | 绿（3 用例） |
| `stage-03-oversell-and-lock-sol` | `mvn test -Dgroups=stage03` | 绿（1 用例，恰好卖 10） |
| `stage-04-isolation-and-mvcc-sol` | `mvn test -Dgroups=stage04` | 绿（6 用例） |
| `stage-05-cache-triad-sol` | `mvn test -Dgroups=stage05` | 绿（6 用例，防击穿 DB 加载 = 1） |
| `stage-06-deadlock-and-lock-sol` | `mvn test -Dgroups=stage06` | 绿（4 用例，含按序加锁无死锁） |

> 每个 `-sol` 分支**只**实现了到本阶段为止的参考解；后续阶段仍是骨架红线。
> 因此在这些分支上跑后续阶段的测试应当**红**——这正是"一阶段一 ref"的判别性。

## 3. 整体体检（可选）

```bash
# main = 完整骨架：stage00/01 绿，stage02-06 红在各自缺陷上
mvn test -Dgroups=stage00,stage01,stage02,stage03,stage04,stage05,stage06,sim -Dtest.excludedGroups=
# solutions = 全阶段参考解：26 run / 0 failures / 0 errors
```

## 4. 取 ref 的方式（任一）

```bash
# A) 直接 clone 整个仓（本地 12 条分支都在）
git clone F:/workspace/seckill-lab /tmp/seckill && cd /tmp/seckill
git checkout stage-03-oversell-and-lock-sol

# B) 只在当前仓里建 worktree（互不干扰）
git worktree add /tmp/s3 stage-03-oversell-and-lock-sol
```

## 5. 交给平台时

平台侧不需要你做任何"注册"——`--content-dir` 指到仓根即可；它按 `stages/*/ticket.yaml`
发现工单、按目录名找分支：

```bash
cd bagu-trainer && .venv/Scripts/python.exe -m platform_api.serve \
  --content-dir F:\workspace\seckill-lab --grading docker --store sqlite
```

`--grading docker` 会在那台机器上真跑 `mvn test -Dgroups=<ticket.grader.correctness.test_group>`，
并按 `track.yaml` 的 `runtime.compose` 先 `down -v` + `up -d --wait`（除非 `--grading-reset never`）。
