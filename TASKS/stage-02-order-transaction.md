# Stage 2 · 工单：下单事务要原子

> 状态：⬜ 待解锁（本工单已定稿，但 Stage 2 的骨架与 canonical 测试尚未放出，故**尚无真机实测证据**；
> 下面凡涉及红/绿具体形状的描述都标注为「预期」，实测后回填——Stage 1 的实测记录见
> [`evidence-stage-01/REPORT.md`](evidence-stage-01/REPORT.md)，那是本目录的证据体例）
> 考点清单见 ticket.yaml 的 `brief.concepts`
>
> 预期初始【红】：`seckill()` 现在是 `throw new UnsupportedOperationException("秒杀下单逻辑尚未实现")`，
> 再加 `OrderMapper.xml` 里的 `insertOrder` / `countByProduct` 仍是未绑定占位（调用抛
> `BindingException: Invalid bound statement (not found)`）——两处都会让验收测试失败。
> 预期改对后【绿】：`mvn -B -Dgroups=stage02 test` → `exit=0` · Tests run: 2, Failures: 0, Errors: 0。
>
> **本工单的权威契约**：[`stages/stage-02-order-transaction/ticket.yaml`](../stages/stage-02-order-transaction/ticket.yaml)
> 版本锚点：工单 id `db-stage-02` · `schema_version=1` · `ticket_version=1` · `test_version=1`
>
> 本文是给人读的**叙述视图**（线上问题的故事 + 关键思考引导）。**改动面、红绿判据、hints 只在
> ticket.yaml 里存一份**，本文不复述——两份清单并存迟早悄悄漂移。冲突时以 ticket.yaml 为准。

## 一、线上问题（工单背景）

**来自：交易组 · 优先级：P0**

> 运营和客服一起找上来：秒杀**下单偶尔丢数据**。用户明明抢到了，`product.stock` 也确实少了 1，
> 可是 `seckill_order` 里查不到这张订单；偶尔又反过来——订单建出来了，库存却没动。
> 财务月底对账直接对不上，差了几百单。DBA 看完代码只说了一句：
> **「扣库存和写订单是两个独立事务，中间任何一步失败，另一半就留在库里了。」**
> 请你把它改成一个原子操作。

这不是编的——本骨架把「扣库存」和「建订单」拆成了两个独立的数据访问动作，各自自动提交，
中间没有任何东西把它们绑在一起。**没有事务，就没有原子性**：第一步成功、第二步失败时，
谁也不会替你把第一步撤回来。这就是 ACID 里 A（Atomicity）在代码上的落点。

## 二、你要做什么

一句话：正好改**两个文件**——补全 `OrderMapper.xml` 里 `insertOrder` / `countByProduct` 两条 SQL，
再让 `SeckillService.seckill()` 在**同一个事务**里「扣库存 + 建订单」。

权威定义在 ticket.yaml 的两处，动手前先读：

| 要知道什么 | 去 ticket.yaml 哪里看 |
|---|---|
| 改哪两个文件（也就是你的改动白名单，改别的算越界） | `scope.files_to_change` |
| 这两个文件现在是什么状态、要补成什么语义<br>（扣库存的 SQL 形状、订单的 5 个列、售罄异常、事务要求） | `brief.problem` |

## 三、关键思考（不给答案，自己想）

写 `@Transactional` 之前，先问自己：

1. 事务边界为什么打在 **service 层**，而不是 controller 或 mapper？（读
   `docs/架构与约定.md` 第 1 节——项目规范里已经给了结论，你要能讲出**理由**：
   放 controller 会把 HTTP 处理时间算进事务、拉长持锁时间；放 mapper 又太细、盖不住「多步操作」。）
2. Spring 的默认回滚规则对**哪类异常**生效？受检异常抛出来会回滚吗？
   （提示：`rollbackFor` 是干什么用的。这决定了你抛的 `SoldOutException` 要不要额外配。）
3. 你的「重复下单」路径里，唯一键冲突抛出的 `DuplicateKeyException` 属于哪一类异常？
   顺着第 2 问想：它会把**已经扣掉的那一次库存**一起回滚吗？
4. 哪些写法会让 `@Transactional` **静默失效**？至少想出两种——自调用、异常被 catch 吞掉、
   方法不是 public、多线程……（提示：`@Transactional` 是**代理**在起作用，想清楚代理绕过了会怎样。）

想清楚这几点，你的实现自然就定了。这是面试最爱问的「事务边界 + 回滚规则 + 事务失效场景」。

## 四、怎么验收

```bash
mvn test -Dgroups=stage02
```

**一开始必须是红的**，而且必须红在该红的地方：`seckill()` 未实现 + 两条 SQL 未绑定。

具体判据不在本文重复，去 ticket.yaml 看：

- `grader.correctness.expect` —— 红/绿各是什么样、两个用例各断言什么，以及**顺序要求**
  （必须在「扣库存成功之后」再插订单：这不是风格偏好，而是让第二个用例真的能验到回滚）；
- `grader.performance` / `grader.concurrency` —— 本工单都是 `null`，事务只谈正确性，并发与超卖是 Stage 3。

两个用例都过 = 工单完成 = 测试变绿。

## 五、Hints

卡住再看，别直接看答案：**ticket.yaml 的 `hints`**，H1 → H4 阶梯式，越往后越接近答案。

平台版里每展开一条都会计入学习者画像，成为「独立性」评审维度的输入（设计 §11.2）——
所以现在也建议你先自己想，想不动了再一级级往下开。

## 六、做完之后

按 ticket.yaml `submission.template.require` 的**三件套**贴给导师 review。这就是「PR 即答辩」
（设计 §11.1，本项目的「做完之后三件套」正是它的出处）。注意第二件和第三件才是本工单的重点：
**光有能跑通的代码不算过关，你得讲得出事务边界为什么在那、哪些异常会回滚，并拿出回滚的实测证据。**

review 通过 → 导师带你过一遍「事务 & ACID」八股（`docs/数据库八股地图.md` Stage 2，
即 ticket.yaml 的 `submission.bagu_map_ref`）→ 解锁 Stage 3（超卖与锁）。

> 参考解在 `solutions` 分支。ticket.yaml 的 `mentor_policy.mode: mentor_then_reveal` 说的就是这件事：
> 陪练期导师只给思路、**不代写**（D1：`may_write` 绝不与 `files_to_change` 相交，准入门硬卡），
> **过关之后**才拿参考解跟你对照「你的解法 vs 参考解」。
> 强烈建议先自己做完再看——你面试时要能讲出自己的思路，而不是背一个标准答案。
