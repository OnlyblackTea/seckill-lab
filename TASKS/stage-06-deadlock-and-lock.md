# Stage 6 · 工单：交叉更新死锁 & 跨实例重复扣减（选修）

> 状态：⬜ 待解锁（本工单已定稿，但 Stage 6 的 canonical 测试尚未放出，故**尚无真机实测证据**；
> 下面凡涉及红/绿具体形状的描述都标注为「预期」，实测后回填——Stage 1 的实测记录见
> [`evidence-stage-01/REPORT.md`](evidence-stage-01/REPORT.md)，那是本目录的证据体例）
> 考点清单见 ticket.yaml 的 `brief.concepts`
>
> **本阶段为选修：不做也不影响主线。** 它排在 Stage 5 之后，是主线八股（索引 → 事务 → 锁 →
> 隔离级别 → 缓存）之外的加餐——但它是「死锁排查」和「分布式锁」这两个高频面试题唯一能让你
> 亲手落地的地方，而且这两个坑在生产上真的会踩。
>
> 预期初始【红】：`LockService.tryLock / unlock / isHeldBy` 目前都是 `TODO`，调用抛
> `UnsupportedOperationException`。
> 预期改对后【绿】：`mvn -B -Dgroups=stage06 test` → `exit=0`（5 个用例：互斥 / 释放后可重抢 /
> TTL 到期自动释放 / 误删防护 / 反向加锁顺序并发下单全部成功且无死锁异常）。
>
> **本工单的权威契约**：[`stages/stage-06-deadlock-and-lock/ticket.yaml`](../stages/stage-06-deadlock-and-lock/ticket.yaml)
> 版本锚点：工单 id `db-stage-06` · `schema_version=1` · `ticket_version=1` · `test_version=1`
>
> 本文是给人读的**叙述视图**（线上问题的故事 + 关键思考引导）。**改动面、红绿判据、hints 只在
> ticket.yaml 里存一份**，本文不复述——两份清单并存迟早悄悄漂移。冲突时以 ticket.yaml 为准。

## 一、线上问题（工单背景）

**来自：交易组 · 优先级：P1**

> 工单箱里同时躺了两个人的报告，看起来无关，其实是同一个病根。
>
> **① DBA 的告警**：生产上偶发 `Deadlock found when trying to get lock; try restarting transaction`。
> 两笔互不相干的秒杀单，能把对方锁死——一笔回滚，用户看到「下单失败，请重试」。
> **② 运维的告警**：秒杀服务扩到**两个实例**之后，同一个用户抢同一件商品，出现了**重复扣减**。
> 更诡异的是：本地单实例怎么压都复现不出来。
>
> 组里的原话是：**「你们的锁只在自己那个 JVM 里有效，一到多实例就是摆设；
> 而你们给同一个订单加锁的顺序，两个事务正好是反的。」**

这两件事都不是编的，骨架里各自留了落点：

- `SeckillService.seckill()` 里，扣库存（写 `product`）与建订单（写 `seckill_order`）是**两行、两步**，
  中间没有任何「谁先谁后」的约定。两个并发事务只要以**相反顺序**碰这两行，就构成循环等待：
  事务甲持有 A 等 B，事务乙持有 B 等 A，InnoDB 的死锁检测只能挑一个当牺牲品回滚。
- `LockService` 是**分布式锁**的落点：进程内的 `synchronized` / `ReentrantLock` 只在**一个 JVM** 内
  互斥，两个实例各自「以为自己是唯一持有者」，于是双双扣减成功。跨进程互斥必须靠外部存储
  （本项目是 Redis）。骨架的接口已经定好，`tryLock` / `unlock` / `isHeldBy` 三个方法全是 `TODO`。

## 二、你要做什么

一句话：正好改**两个文件**——用 Redis 实现 `LockService` 的三方法（抢锁 / 释放 / 查询），
再让 `SeckillService` 的下单路径**按固定顺序**加锁或更新，破坏循环等待。

权威定义在 ticket.yaml 的两处，动手前先读：

| 要知道什么 | 去 ticket.yaml 哪里看 |
|---|---|
| 改哪两个文件（也就是你的改动白名单，改别的算越界） | `scope.files_to_change` |
| 这两个文件现在是什么状态、要补成什么语义<br>（三方法的签名与语义、锁 key 命名约定、`SET NX PX` + Lua 的硬要求、固定加锁顺序） | `brief.problem` |

## 三、关键思考（不给答案，自己想）

写 Redis 锁之前，先问自己：

1. `SETNX` + 单独的 `EXPIRE` 是**两条命令**，为什么不行？（提示：两条命令之间如果进程挂了会怎样？
   客户端和 Redis 之间的网络呢？想想这把锁会不会变成**永不过期**的死锁。）
   而 `SET key value NX PX ttl` 一条命令解决了什么？——这就是 H1 说的「原子抢锁 + 过期兜底」。
2. 锁的 value 为什么**不能**是固定字符串（比如 `"1"`）？如果你 `DEL` 的时候只认 key 不认 value，
   举一个「你能把别人的锁删掉」的具体时间线出来。（H2 会给你一个 GC 停顿的版本。）
3. 「先 `GET` 判断是不是我的，再 `DEL`」这两步之间，**锁有可能过期并被别人抢走**吗？
   如果会，那这两步还必须是什么关系？为什么这个关系只能用 Lua 表达，而不能靠 Java 里加个
   `synchronized` 解决？（提示：你的 Java 锁锁不住别的实例。）
4. `SELECT ... FOR UPDATE` 按主键加行锁；那么两个事务分别以 `[A, B]` 和 `[B, A]` 的顺序去锁 A、B，
   死锁在**哪一个瞬间**成立？把「持有」和「等待」画成一张有向图，你会看到什么形状？
   ——现在反推：**只要所有事务都按同一个顺序拿锁，这个形状还能形成吗？** 为什么？
   （这就是「破坏死锁四条件里的循环等待」这句话的全部含义。）
5. 分布式锁的 TTL 设多长才安全？「业务没执行完锁先过期了」会发生什么？看门狗续期在解决什么？
   续期的代价是什么？（先想清楚，再看 H1/H4 与 map 里「锁续期」那一行。）

想清楚这几点，你的实现自然就定了。这是面试最爱问的「Redis 分布式锁怎么实现、为什么要 Lua、
如何避免死锁」。

## 四、怎么验收

```bash
mvn test -Dgroups=stage06
```

**一开始必须是红的**，而且必须红在该红的地方：`tryLock` 未实现，抛 `UnsupportedOperationException`。

具体判据不在本文重复，去 ticket.yaml 看：

- `grader.correctness.expect` —— 红/绿各是什么样、五个用例各断言什么，以及**误删防护那个用例
  为什么是本工单的教学重点**（它不是「顺手加个校验」，而是分布式锁正确性的分水岭）；
- `grader.performance` —— 本工单是 `null`（这一阶段没有索引/执行计划可断言）；
- `grader.concurrency` —— `deadlock_probe`：断言的是「**没有**死锁异常 + 全部下单成功」，
  注意它和 Stage 3 那个「零超卖」是不同的断言方向。

额外一条回归（**依赖 Stage 2/3 已完成**——`seckill()` 真正实现了才谈得上超卖）：

```bash
mvn test -Dgroups=sim -Dtest.excludedGroups=
```

**零超卖必须依然是零超卖。** 你为消除死锁而调整的加锁顺序，不能把 Stage 3 的成果弄丢
（默认 Surefire 排除 `sim` 分组，所以要显式 `-Dgroups=sim -Dtest.excludedGroups=` 打开）。

两条命令都过 = 工单完成 = 测试变绿。

## 五、Hints

卡住再看，别直接看答案：**ticket.yaml 的 `hints`**，H1 → H4 阶梯式，越往后越接近答案。

平台版里每展开一条都会计入学习者画像，成为「独立性」评审维度的输入（设计 §11.2）——
所以现在也建议你先自己想，想不动了再一级级往下开。

## 六、做完之后

按 ticket.yaml `submission.template.require` 的**三件套**贴给导师 review。这就是「PR 即答辩」
（设计 §11.1，本项目的「做完之后三件套」正是它的出处）。注意本工单的**第二件**才是真正拉开
差距的地方：**光有一套能跑通的抢锁代码不算过关，你得讲得出你的锁为什么不会误删、
为什么持锁进程崩了不会永久死锁，并拿出前后对比的实测证据。**

review 通过 → 导师带你过一遍「死锁 & 分布式锁」八股（`docs/数据库八股地图.md` Stage 6，
即 ticket.yaml 的 `submission.bagu_map_ref`）→ 主线到此走完；再往后是 map 附录里那些
「本项目不涉及、但你该文字了解」的考点（分库分表 / 主从复制 / binlog 三格式 / 两阶段提交）。

> 参考解在 `solutions` 分支。ticket.yaml 的 `mentor_policy.mode: mentor_then_reveal` 说的就是这件事：
> 陪练期导师只给思路、**不代写**（D1：`may_write` 绝不与 `files_to_change` 相交，准入门硬卡），
> **过关之后**才拿参考解跟你对照「你的解法 vs 参考解」。
> 强烈建议先自己做完再看——你面试时要能讲出自己的思路，而不是背一个标准答案。
