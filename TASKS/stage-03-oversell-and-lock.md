# Stage 3 · 工单：高并发超卖了！

> 状态：🟥 已派发（验收测试初始为红）· 考点清单见 ticket.yaml 的 `brief.concepts`
>
> **本工单的权威契约**：[`stages/stage-03-oversell-and-lock/ticket.yaml`](../stages/stage-03-oversell-and-lock/ticket.yaml)
> 版本锚点：工单 id `db-stage-03` · `schema_version=1` · `ticket_version=1` · `test_version=1`
>
> 本文是给人读的**叙述视图**（线上问题的故事 + 关键思考引导）。**改动面、红绿判据、hints 只在
> ticket.yaml 里存一份**，本文不复述——两份清单并存迟早悄悄漂移。冲突时以 ticket.yaml 为准。

## 一、线上问题（工单背景）

**来自：交易组 · 优先级 P0**

> 秒杀开场，100 个请求同时打一个**库存只有 10** 的热点商品。
> 结果：**卖出去 30 多件**，库存还显示 9。用户付了钱、货发不出去，客服被打爆。
>
> DBA 捞出来一看：`product` 的库存是被"读出来、减一、再写回去"的——
> 两个请求同时读到 `stock=10`，各自写回 `9`，这就是**丢失更新**。

骨架里 `SeckillService.placeOrder()` 就是这么写的（**故意的教学样本**）：

```java
Integer stock = productMapper.selectStock(productId);   // ① 读
if (stock == null || stock <= 0) { return soldOut; }     // ② 应用层判断
productMapper.updateStock(productId, stock - 1);         // ③ 写回
```

① 和 ③ 之间没有任何互斥：**读-判断-写**三步不是一个原子操作。

## 二、你要做什么

一句话：**让"判断够不够"和"扣减"变成一个原子操作**，并发下不超卖。

| 要知道什么 | 去 ticket.yaml 哪里看 |
|---|---|
| 改哪两个文件（改动白名单，改别的算越界） | `scope.files_to_change` |
| 这两个文件现在什么状态、要补成什么语义 | `brief.problem` |
| 红/绿各是什么样、`sim` 的指标 | `grader.correctness.expect` / `grader.concurrency` |

两条路线，选一条（**两条都判绿**）：

- **原子更新（推荐）**：把判断塞进 `WHERE` ——
  `UPDATE product SET stock = stock - 1 WHERE id = ? AND stock > 0`，
  返回受影响行数，`0` 就代表售罄。一条语句搞定，不用显式加锁。
- **悲观锁**：`SELECT ... FOR UPDATE` 先把行锁住，再读、判断、写回。
  注意：锁要放在**事务**里（Stage 2 的实现）才有效，否则语句结束锁就放了。

> `product` 表有一个 `version INT` 字段，是给**乐观锁**预留的（CAS：
> `UPDATE ... SET stock = stock - 1, version = version + 1 WHERE version = ?`）。
> 想走这条也行，但要在 service 层处理"更新 0 行 → 重试"。

## 三、关键思考（不给答案，自己想）

1. 为什么"先查再改"在并发下一定会出问题？把两个线程的时间线画出来：
   谁在什么时刻读、什么时刻写、对方看到的是哪个值？
2. `UPDATE ... WHERE stock > 0` 为什么不需要显式加锁？InnoDB 执行 UPDATE 时做了什么？
   它读的是**快照**还是**最新已提交版本**？（这是 Stage 4 的伏笔：当前读 vs 快照读）
3. 悲观锁和乐观锁分别适合什么场景？秒杀这种"写冲突极多"的场景哪个更合适？为什么？
4. 如果改用乐观锁，`version` 冲突了怎么办？重试几次？重试要放在事务里面还是外面？

## 四、怎么验收

```bash
mvn test -Dgroups=stage03                              # 10 库存 + 30 线程，判据：恰好卖 10 件
mvn test -Dgroups=sim -Dtest.excludedGroups=           # 100 线程打 HTTP，判据：零超卖
```

**一开始必须是红的**，而且必须红在"超卖"上，不是红在编译或异常上。

具体判据（多了少了都不行）去 ticket.yaml 看：

- `grader.correctness.expect` —— 骨架红/参考解绿的形状；
- `grader.concurrency.assert` —— 并发指标（成功数 ≤ 初始库存、最终库存 = 0、订单数 = 成功数）。

## 五、Hints

卡住再看，别直接看答案：**ticket.yaml 的 `hints`**，H1 → H4 阶梯式。

- H1：把"判断"从 Java 挪进 SQL 的 `WHERE`。
- H2：想想为什么一条 UPDATE 就能免疫并发（它加了什么锁、读的是哪个版本）。
- H3：手动复现并发（`docker compose exec mysql` 开两个会话，或直接压 `sim`）。
- H4：悲观锁 vs 乐观锁的差异、以及"锁必须活在事务里"这个前提。

## 六、做完之后

按 ticket.yaml `submission.template.require` 的**三件套**贴给导师 review：

1. 改后的 `seckill()` / SQL；
2. 一句话解释**为什么你的方案在并发下不会超卖**（选了原子更新还是锁，为什么）；
3. 并发实测证据：初始库存 → 成功数 → 最终库存 → 落库订单数的前后对比。

review 通过 → 导师带你过「锁 & 超卖」八股（`docs/数据库八股地图.md` Stage 3）→ 解锁 Stage 4（隔离级别与 MVCC）。

> 参考解在 `solutions` 分支。ticket.yaml 的 `mentor_policy.mode: mentor_then_reveal` 说的就是这件事：
> 陪练期导师只给思路、**不代写**（D1：`may_write` 绝不与 `files_to_change` 相交，准入门硬卡），
> **过关之后**才拿参考解跟你对照。
