# 工单路线图（TASKS）

> 你是秒杀组新人。每张工单 = 一个「线上问题」+ 一个数据库八股考点 + 一组验收测试。
> 流程：**读工单 → 亲手改代码 → 跑验收测试(红变绿) → 找导师 review → 解锁下一张**。
> 工单只在你准备好时逐个解锁，Stage 2+ 的任务书和验收测试暂不放出（防剧透）。

> **本看板是给人读的叙述层。** 机器可读的契约在两处：仓根 [`track.yaml`](../track.yaml)
> （这个 track 是什么、跑什么、被哪些 digest 锁定）和 [`stages/*/ticket.yaml`](../stages)
> （每张工单的题面、改动面、红绿判据、hints、答辩要求、导师权限、解锁依赖）。
> 本目录的 md 与 ticket.yaml 冲突时**以 ticket.yaml 为准**——事实只存一份，避免悄悄漂移。
> 契约格式来自发卷平台（bagu-trainer 设计 §4.2/§4.3），准入校验命令是 `paper validate-track`；
> **题面/判据/hints 该怎么写**（叙事优先 + 每条 hint 带标题）由平台仓的
> `bagu-trainer/docs/工单写作规范.md` 定，本仓只放这条指针、不复制正文。

## 进度看板

| Stage | 标题 | 八股考点 | 验收 | 状态 |
|-------|------|---------|------|------|
| 0 | [入职：拉起环境与骨架](stage-00-onboarding.md) | — | `mvn test -Dgroups=stage00` 绿 | ✅ 已完成（VM 实测绿） |
| 1 | [商品查询与索引](stage-01-product-query-index.md)<br>契约 `db-stage-01` | 索引/最左前缀/覆盖索引/EXPLAIN/索引失效 | `mvn test -Dgroups=stage01` 绿 | ✅ 已完成（证据 [`evidence-stage-01/`](evidence-stage-01/REPORT.md)） |
| 2 | [下单事务要原子](stage-02-order-transaction.md)<br>契约 `db-stage-02` | ACID/传播/回滚/@Transactional | `mvn test -Dgroups=stage02` 绿 | 🟥 已派发（骨架红在"库存没回滚"，参考解绿） |
| 3 | [高并发超卖了！](stage-03-oversell-and-lock.md)<br>契约 `db-stage-03` | 行锁/乐观vs悲观/丢失更新 | `mvn test -Dgroups=stage03` 绿<br>`mvn test -Dgroups=sim -Dtest.excludedGroups=` 零超卖 | 🟥 已派发（骨架实测超卖 30>10，参考解恰好 10） |
| 4 | [对账读到脏数据](stage-04-isolation-and-mvcc.md)<br>契约 `db-stage-04` | 四隔离级别/ReadView/快照读vs当前读 | `mvn test -Dgroups=stage04` 绿 | 🟥 已派发（骨架缺 order_stat，参考解绿） |
| 5 | [热点商品打挂 DB](stage-05-cache-triad.md)<br>契约 `db-stage-05` | 穿透/击穿/雪崩/缓存一致性 | `mvn test -Dgroups=stage05` 绿 | 🟥 已派发（参考解防击穿：DB 加载 20→1） |
| 6 | [死锁与分布式锁（选修）](stage-06-deadlock-and-lock.md)<br>契约 `db-stage-06` | 间隙锁/Next-Key/死锁排查/SET NX PX | `mvn test -Dgroups=stage06` 绿 | 🟥 已派发（骨架 stub 抛异常，参考解绿） |

> 全部 stage（0–6）现已**真机实测**：参考解 `solutions` 分支 26 个用例全绿，
> 骨架 `main` 各阶段红在各自的缺陷上。证据与原始日志见
> [`evidence-stages-02-06/REPORT.md`](evidence-stages-02-06/REPORT.md)。

## 怎么玩

1. 从 Stage 0 开始，确认环境跑得起来、骨架冒烟测试是绿的。
2. 打开 Stage 1 工单，先读 `stages/stage-01-product-query-index/ticket.yaml` 的
   `brief.problem`（题面：两个文件现在什么状态、要补成什么语义）和 `scope.files_to_change`
   （你的改动白名单），再动手改代码。叙述版任务书里的「关键思考」四问值得先想一遍。
3. 跑 `mvn test -Dgroups=stage01`，一开始应该是**红**的（这是正常的，说明工单还没做完）。
4. 改对之后变**绿**，把你的改动和 EXPLAIN 输出贴给导师 review。
5. review 通过 → 导师讲解对应八股 → 解锁 Stage 2。

## 约定

- **改动白名单**：只改 ticket.yaml `scope.files_to_change` 里列的文件。改验收测试、`pom.xml`、
  fixture **一律不参与阅卷**，还会被标成越界（`out_of_scope_edits`）交给评审重点核查（设计 §7.3/§11.2）。
- **导师不代写（D1）**：`mentor_policy.may_write` 绝不与 `files_to_change` 相交——这是准入门硬卡的
  不变量。导师只给思路、给 hints、陪你读 EXPLAIN；业务代码永远是你自己写。
- **参考解只存在于 `solutions` 分支，绝不进 `main`**。这就是 `mentor_policy.mode: mentor_then_reveal`：
  陪练期不递到眼前，**过关后**才拿参考解跟你对照「你的解法 vs 参考解」（设计 §8）。
  卡住了先自己想，再看 hints，最后才找导师。
- 每张工单对应一组提交，提交信息规范见 `docs/架构与约定.md`。
- 遇到不懂的先查 `docs/新手开发参考.md`。
