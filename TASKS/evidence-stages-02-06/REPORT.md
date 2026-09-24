# Stage 2–6 验收证据（真机实跑）

> 工单：`db-stage-02` … `db-stage-06`（`stages/*/ticket.yaml`）
> 环境：VM `10.32.10.102`（Ubuntu / linux-amd64）· OpenJDK `17.0.20` · Maven `3.8.7` ·
> Docker `29.1.3` · `mysql:8.0` + `redis:7`（`docker/docker-compose.yml`，13306 / 16379）
> 跑法：每轮先 `docker compose down -v` 重置数据卷与种子（50003 行），
> 然后用**一次** `mvn -B -Dgroups=stage00,…,stage06,sim -Dtest.excludedGroups= test` 跑全部阶段。
> 两个被测 ref：`main`（骨架 TODOs）与 `solutions`（全阶段参考解）。
>
> 本目录的 `mvn-*.log` / `STATUS*.txt` 都是从真跑里原样取回的产物，未经手工编辑。
> 复现脚本：`verify-all.sh`（逐阶段跑）与 VM 上的 `verify-one.sh`（一次跑全组）。

## 一、判别性：同一套测试在两个 ref 上结果相反

> 最终状态的一轮：`main` 上 Stage 1 是**已交付**阶段（`searchProducts` 已绑定 + V1 已建索引），
> 所以它跑绿；红的是 Stage 2–6。`solutions` 上全绿。

| ref | `mvn -Dgroups=stage00…stage06,sim test` | 结论 |
|---|---|---|
| `main`（骨架，Stage 1 已交付） | `exit=1` · Tests run: **26, Failures: 6, Errors: 14** | stage00/01 绿；stage02–06 红在各自缺陷上 |
| `solutions`（参考解） | `exit=0` · Tests run: **26, Failures: 0, Errors: 0** · BUILD SUCCESS | 绿 |

参考解逐类结果（一次运行内）：

```
Tests run: 3, Failures: 0, Errors: 0 -- SmokeTest                    (stage00)
Tests run: 2, Failures: 0, Errors: 0 -- ProductQueryIndexAcceptanceTest        (stage01)
Tests run: 3, Failures: 0, Errors: 0 -- SeckillTransactionAcceptanceTest       (stage02)
Tests run: 1, Failures: 0, Errors: 0 -- OversellPreventionAcceptanceTest       (stage03)
Tests run: 6, Failures: 0, Errors: 0 -- IsolationLevelAcceptanceTest           (stage04)
Tests run: 6, Failures: 0, Errors: 0 -- ProductCacheAcceptanceTest             (stage05)
Tests run: 4, Failures: 0, Errors: 0 -- DistributedLockAcceptanceTest          (stage06)
Tests run: 1, Failures: 0, Errors: 0 -- SeckillLoadSimulator                   (sim)
```

## 二、"红在正确的地方"逐阶段对照（骨架）

| Stage | 骨架的红是什么 | 判据为什么这么设计 |
|---|---|---|
| 2 | 重复下单触发唯一键失败后，**库存仍被扣掉**（`rollback_onOrderInsertFailure_restoresStock` 断言失败，1 failure / 0 error） | 扣库存与建订单不在同一事务 → 库存回不去。这就是线上"库存扣了订单没建" |
| 3 | 30 线程抢 10 库存时**超卖**：`成功下单数必须恰好等于初始库存 … expected: <10> but was: <30>` | "先读 → 判断 → 写回"三步之间有窗口 → 丢失更新 |
| 4 | `order_stat` 表不存在 / 聚合 SQL 未绑定（6 个用例全 error） | V2 迁移只有占位、`sumAmountByProduct` 未绑定 |
| 5 | `getProductCached` 抛 `UnsupportedOperationException`（4 error + 1 failure） | 缓存读写、空值哨兵、单飞全部未实现 |
| 6 | `LockServiceImpl.tryLock` 抛 `UnsupportedOperationException`（3 error + 1 failure） | 锁与按序加锁未实现 |
| sim | 100 线程打接口，接口 501/异常 → 零超卖判据不成立 | sim 是 Stage 3 的并发判据，骨架下必然不达标 |

> 这些"红"都是**业务缺陷**造成的，不是编译错、不是脚本为空、不是装配失败——
> 也就是 ticket.yaml 里 `grader.correctness.expect` 说的"红在对的地方"。

## 三、两次真机发现并修掉的"假红"（值得记下来）

1. **stage02 曾经全绿**：骨架里带了 `countByUserAndProduct` 预检查，重复下单在预检查处就被拒，
   INSERT 根本不执行，于是没有"扣了库存但订单没建"的失败路径，判据失效。
   去掉预检查后（参考解保留预检查，两条路线都判绿），stage02 才真的红。
2. **stage03 曾经红在 `insertOrder` 未绑定**：那是"红在错误的地方"。
   把 `insertOrder` / `countByProduct` 绑进骨架（它们不是 Stage 2/3 的教学点）之后，
   stage03 才红在超卖本身。

## 四、关键实测数字

**Stage 3 并发（30 线程 / 库存 10）**

| ref | 成功下单 | 售罄拒绝 | 最终库存 | 落库订单数 |
|---|---|---|---|---|
| 骨架 | **30（超卖！）** | 0 | 7 | 30 |
| 参考解 | **10** | 20 | **0** | **10** |

> 骨架那一行就是工单里"100 个请求卖出 30 多件、库存还剩 9"的真身：
> 卖出去 30 件，库存却只从 10 掉到 7 —— 27 件的扣减被丢失更新吃掉了。

**sim 并发模拟器（100 线程打 HTTP / 库存 10）**

| ref | 成功(200) | 售罄(410) | 重复(409) | 未实现(501) | 最终库存 | 订单数 |
|---|---|---|---|---|---|---|
| 骨架 | 0（接口不可用） | 0 | 0 | 0 | 4 | 0 |
| 参考解 | **10** | 90 | 0 | 0 | **0** | **10** |

零超卖成立：`成功数(10) ≤ 初始库存(10)`，且 `最终库存 = 初始库存 - 成功数`。

**Stage 5 防击穿（20 个并发线程查同一个不存在的 id）**

| ref | DB 加载次数 |
|---|---|
| 骨架 | 每次请求各加载一次（20） |
| 参考解 | **1** |

> 这个数字是这道题最有价值的部分：只加锁（20 → 4~6）、或者把"写缓存"留在锁外，
> 都防不住击穿；必须是「双重检查 + 查 DB 与写缓存在同一个临界区 + 空值哨兵也算命中」三件齐全。

## 五、产物清单

| 文件 | 是什么 |
|---|---|
| `STATUS.txt` | 逐阶段跑的机读汇总（环境、种子数、每阶段 exit/计数） |
| `mvn-stage0{2,3,4,5,6}.log` / `mvn-sim.log` | 逐阶段 Maven 真跑日志（参考解） |
| `verify-all.sh` | VM 上逐阶段跑的驱动脚本 |

VM 上另存的一次跑全组日志：`~/verify-one-*/artifacts/mvn-all.log`（`main` / `solutions` 各一份）。

## 六、复现方式

```bash
cd docker && docker compose down -v && docker compose up -d --wait   # 等 healthy + 种子灌完
cd .. && mvn -B -Dgroups=stage00,stage01,stage02,stage03,stage04,stage05,stage06,sim \
         -Dtest.excludedGroups= test
#   main      -> 26 run, 5 failures, 16 errors（各阶段红在自己的缺陷上）
#   solutions -> 26 run, 0 failures, 0 errors
```
