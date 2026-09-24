# Stage 4 · 工单：对账读到脏数据/不一致

> 状态：⬜ 未开始（骨架红）· 前置工单：Stage 3（锁 & 超卖，`db-stage-03`）·
> 考点清单见 ticket.yaml 的 `brief.concepts`
>
> 预期红绿（真机证据跑完后按 Stage 1 的格式补进 `TASKS/evidence-stage-04/REPORT.md`）：
> `mvn -B -Dgroups=stage04 test` 一开始应为 `exit=1`，且红在这两处——
> ① 迁移脚本还没建 `order_stat` 表 → 表不存在；② `sumAmountByProduct` / `countByProduct`
> 未绑定 → `BindingException`。你补完两个文件后应为 `exit=0`。
>
> **本工单的权威契约**：[`stages/stage-04-isolation-and-mvcc/ticket.yaml`](../stages/stage-04-isolation-and-mvcc/ticket.yaml)
> 版本锚点：工单 id `db-stage-04` · `schema_version=1` · `ticket_version=1` · `test_version=1`
>
> 本文是给人读的**叙述视图**（线上问题的故事 + 关键思考引导）。**改动面、红绿判据、hints 只在
> ticket.yaml 里存一份**，本文不复述——两份清单并存迟早悄悄漂移。冲突时以 ticket.yaml 为准。

## 一、线上问题（工单背景）

**来自：数据组 · 优先级：P1**

> 对账/统计报表（「某商品成交额多少、订单数多少」）在**并发下读到脏数据**：同一个统计事务里
> 前后两次读到的值对不上；偶尔还读到别的会话**尚未提交**的中间态（订单已经插进去了，
> 库存还没扣完），报表数字来回跳、口径对不上。请你说清楚这是哪种隔离问题，
> 并把「对账口径」落到一张能持久化的表上。

这不是编的——本项目的验收测试会自己开**两个独立的 JDBC 连接**、手工
`START TRANSACTION` / `COMMIT`，在 InnoDB 默认的 `REPEATABLE READ` 下观察你的 SQL
到底看得见什么、看不见什么。

顺便先把一个最容易混的点摆出来：**「扣库存成功」和「随后读到旧库存」可以同时成立。**
`UPDATE product SET stock = stock - 1 WHERE stock > 0` 是**当前读**——它读最新已提交版本并加锁；
而同事务里随后的一句普通 `SELECT` 在 RR 下是**快照读**——它按 ReadView 去找「我能看见的那个版本」。
如果 ReadView 在 UPDATE 之前就已经生成，那句 `SELECT` 会读回扣减**之前**的库存，
看上去就像「扣成功但库存没变」。这正是报表来回跳那类 bug 的来源，也是本工单要你亲手复现的。

## 二、你要做什么

一句话：正好改**两个文件**——补全 `sumAmountByProduct` / `countByProduct` 两条汇总 SQL，
再把迁移脚本补成**幂等建表**脚本，让「对账口径」有 `order_stat` 这个持久化落点。

权威定义在 ticket.yaml 的两处，动手前先读：

| 要知道什么 | 去 ticket.yaml 哪里看 |
|---|---|
| 改哪两个文件（也就是你的改动白名单，改别的算越界） | `scope.files_to_change` |
| 这两个文件现在是什么状态、要补成什么语义<br>（两条 SQL 的形状、`order_stat` 的表结构、幂等写法） | `brief.problem` |

两个文件的分工：

- `src/main/resources/mapper/OrderMapper.xml` —— 两条 `<select>` 的 `id` 必须与方法名逐字一致
  （项目约定见 `docs/架构与约定.md` §4），一条算成交额、一条数订单数；
- `src/main/resources/db/migration/V2__stage04_order_stat.sql` —— 现在只有注释 + 末尾一条
  `SELECT 1;` 占位（那条占位是防 `@Sql` 把「整份全是注释」判成空脚本），不建任何表；
  你要把它补成真正建表的脚本，且**重复执行不报错**（`@Sql` 每个测试方法前都会跑一次）。

## 三、关键思考（不给答案，自己想）

先别写 SQL，先回答下面几个问题——面试里这部分比代码值钱：

1. 四种隔离级别（READ UNCOMMITTED / READ COMMITTED / **REPEATABLE READ** / SERIALIZABLE）
   分别解决脏读、不可重复读、幻读里的哪几个？InnoDB 默认停在哪个、为什么？
2. 一条普通的 `SELECT` 读的到底是「磁盘上的最新值」还是「某个历史版本」？
   InnoDB 靠什么结构（每行的隐藏列 + undo log 版本链）把这个历史版本拼出来？
3. **ReadView 是什么时候生成的？** RR 与 RC 的差别就藏在这一句里。想清楚它，
   「同一事务内两次读一致」和「跨事务能看到新提交」为什么不矛盾，就自然通了。
4. 为什么 `UPDATE ... WHERE stock > 0` 和紧随其后的一句 `SELECT stock` 可能给出**互相打脸**的答案？
   哪一个是**快照读**、哪一个是**当前读**？如果要在同一事务里核对刚写下去的值，该怎么读？
5. 你的两条汇总 SQL ——「别人刚插了订单但还没提交」时应该返回什么？
   「别人已提交、我开一个新事务再读」时又应该返回什么？这两问就是验收测试的两条主线。

想清楚这几点，你的两条 SQL 和建表脚本自然就定了。这是面试最爱问的「MVCC + 隔离级别」。

## 四、怎么验收

```bash
mvn test -Dgroups=stage04
```

**一开始必须是红的**，而且必须红在该红的地方：`order_stat` 表不存在 + 两条汇总 SQL 未绑定。

具体判据不在本文重复，去 ticket.yaml 看：

- `grader.correctness.expect` —— 红/绿各是什么样（表存在且脚本幂等、未提交不可见、
  同一会话两次快照读一致、汇总 SQL 能看到**已提交**的最新值），以及
  **哪种红是红错了地方**（迁移脚本若被改成「整份全是注释」，`@Sql` 会先抛空脚本错，那不是我们要的红）；
- `grader.performance` / `grader.concurrency` —— 本工单**都是 `null`**：Stage 4 考的是**可见性语义**，
  不看执行计划、也不在这里跑并发压测（并发超卖是 Stage 3 的 `sim` 组）。

四条判据全过 = 工单完成 = 测试变绿。

## 五、Hints

卡住再看，别直接看答案：**ticket.yaml 的 `hints`**，H1 → H4 阶梯式，越往后越接近答案。
（H1 管建表脚本的幂等写法，H2 管两条汇总 SQL 的形状，H3 给两个会话手动复现的命令，
H4 讲 MVCC / ReadView 与「快照读 vs 当前读」——但都不给可直接抄的完整代码。）

平台版里每展开一条都会计入学习者画像，成为「独立性」评审维度的输入（设计 §11.2）——
所以现在也建议你先自己想，想不动了再一级级往下开。

## 六、做完之后

按 ticket.yaml `submission.template.require` 的**三件套**贴给导师 review。这就是「PR 即答辩」
（设计 §11.1，本项目的「做完之后三件套」正是它的出处）：

1. 改后的两条汇总 SQL + 幂等建表脚本；
2. 一句话讲清你观察到的隔离级别行为（未提交不可见 / 快照一致 / 当前读看最新）；
3. 实测证据：两个会话的时间线与查询结果对比。

review 通过 → 导师带你过一遍「隔离级别 & MVCC」八股（`docs/数据库八股地图.md` Stage 4，
即 ticket.yaml 的 `submission.bagu_map_ref`）→ 解锁 Stage 5（Redis 缓存三件套）。

> 参考解在 `solutions` 分支。ticket.yaml 的 `mentor_policy.mode: mentor_then_reveal` 说的就是这件事：
> 陪练期导师只给思路、**不代写**（D1：`may_write` 绝不与 `files_to_change` 相交，准入门硬卡），
> **过关之后**才拿参考解跟你对照「你的解法 vs 参考解」。
> 强烈建议先自己做完再看——你面试时要能讲出自己的思路，而不是背一个标准答案。
