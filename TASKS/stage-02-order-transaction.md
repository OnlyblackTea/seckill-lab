# Stage 2 · 工单：下单事务要原子

> 状态：🟥 已派发（Stage 2 的骨架、canonical 测试与参考解都已真机实测：
> 骨架红在**回滚**这一条上，参考解 3 个用例全绿——证据见
> [`evidence-stages-02-06/REPORT.md`](evidence-stages-02-06/REPORT.md)）
> 考点清单见 ticket.yaml 的 `brief.concepts`
>
> 实测形状：`mvn -B -Dgroups=stage02 test` → 骨架 1 failure（`rollback_onOrderInsertFailure_restoresStock`）·
> 参考解 `Tests run: 3, Failures: 0, Errors: 0` · `exit=0`。
>
> **本工单的权威契约**：[`stages/stage-02-order-transaction/ticket.yaml`](../stages/stage-02-order-transaction/ticket.yaml)
> 版本锚点：工单 id `db-stage-02` · `schema_version=1` · `ticket_version=2` · `test_version=1`
>
> 本文是给人读的**叙述视图**（线上问题的故事 + 关键思考引导）。**改动面、红绿判据、hints 只在
> ticket.yaml 里存一份**，本文不复述——两份清单并存迟早悄悄漂移。冲突时以 ticket.yaml 为准。
> 写作规范见平台仓的 `bagu-trainer/docs/工单写作规范.md`（本仓只放指针，不复制正文）。

## 一、线上问题（工单背景）

**来自：交易组 · 优先级：P0**

> 运营和客服一起找上来：秒杀**下单偶尔丢数据**。用户明明抢到了，`product.stock` 也确实少了 1，
> 可是 `seckill_order` 里查不到这张订单；偶尔又反过来——订单建出来了，库存却没动。
> 财务月底对账直接对不上，差了几百单。DBA 看完代码只说了一句：
> **「扣库存和写订单是两个独立事务，中间任何一步失败，另一半就留在库里了。」**
> 请你把它改成一个原子操作。

这不是编的——本骨架的下单链路把「扣库存」和「建订单」拆成了两个独立的数据访问动作，各自自动提交，
中间没有任何东西把它们绑在一起。**没有事务，就没有原子性**：第一步成功、第二步失败时，谁也不会
替你把第一步撤回来。这就是 ACID 里 A（Atomicity）在代码上的落点。

一个提醒：骨架的下单流程**功能上是通的**——能下单、能扣库存、售罄会拒绝。它缺的只有原子性，
所以这一关的红不是"一片红"，而是**一条断言**红：重复下单失败之后，库存没有回到原值。

## 二、你要做什么

一句话：把「扣库存」和「建订单」收进**同一个事务**，并说清哪些异常会回滚、哪些不会。
要改的文件、事务边界该放哪一层、扣库存的 SQL 形状与受影响行数的判法、以及顺序约束，
都在 ticket.yaml 的 `brief.problem` 里——动手前先读那一段（它已经按"叙事优先"重写过，
不是清单堆叠）。`scope.files_to_change` 就是你的改动白名单，改别的算越界。

> `OrderMapper.xml` 在改动面里，但**骨架已经把 `insertOrder` / `countByProduct` 绑定好了**
> （`useGeneratedKeys="true" keyProperty="id"` 也在），它留给你的是可以照抄的样式，不是待办的活。
> 这道题的红**不在**那条语句上。

## 三、关键思考（不给答案，自己想）

写 `@Transactional` 之前，先问自己：

1. 事务边界为什么打在 **service 层**，而不是 controller 或 mapper？（读
   `docs/架构与约定.md` 第 1 节——项目规范里已经给了结论，你要能讲出**理由**：
   放 controller 会把 HTTP 处理时间算进事务、拉长持锁时间；放 mapper 又太细、盖不住「多步操作」。）
2. Spring 的默认回滚规则对**哪类异常**生效？受检异常抛出来会回滚吗？
   （提示：`rollbackFor` 是干什么用的，以及你这条链路上实际会抛什么。）
3. 你的「重复下单」路径里，唯一键冲突抛出的 `DuplicateKeyException` 属于哪一类异常？
   顺着第 2 问想：它会把**已经扣掉的那一次库存**一起回滚吗？
4. 哪些写法会让 `@Transactional` **静默失效**？至少想出两种——自调用、异常被 catch 吞掉、
   方法不是 public、多线程……（提示：`@Transactional` 是**代理**在起作用，想清楚代理绕过了会怎样。）

想清楚这几点，你的实现自然就定了。这是面试最爱问的「事务边界 + 回滚规则 + 事务失效场景」。

## 四、怎么验收

```bash
mvn test -Dgroups=stage02
```

**一开始必须是红的**，而且必须红在该红的地方：`rollback_onOrderInsertFailure_restoresStock`
——建订单失败之后库存没有回到原值。另外两个用例（正常下单、售罄不扣成负数）在骨架上本来就是绿的，
它们保证你没有为了"让回滚通过"而把功能改坏。

具体判据不在本文重复，去 ticket.yaml 看：

- `grader.correctness.expect` —— 红/绿各是什么样、三个用例各断言什么，以及**顺序要求**
  （必须在「扣库存成功之后」再插订单：这不是风格偏好，而是让核心用例真的能验到回滚）；
- `grader.performance` / `grader.concurrency` —— 本工单都是 `null`，事务只谈正确性，并发与超卖是 Stage 3。

三个用例都过 = 工单完成 = 测试变绿。

## 五、Hints

卡住再看，别直接看答案：**ticket.yaml 的 `hints`**，H1 → H4 阶梯式，越往后越接近答案。
每条都有标题（「事务边界」「回滚规则」「怎么验证回滚」「再想一层」），页面上默认折叠——
先读标题、判断要不要展开，比一条条点开试省事得多。

平台版里每展开一条都会计入学习者画像，成为「独立性」评审维度的输入（设计 §11.2）——
所以现在也建议你先自己想，想不动了再一级级往下开。

## 六、做完之后

按 ticket.yaml `submission.template.require` 的**三件套**填进平台的「提交」（答辩文本）。这就是「提交即答辩」
（设计 §11.1，本项目的「做完之后三件套」正是它的出处）。注意后两件才是本工单的重点：
**光有能跑通的代码不算过关，你得讲得出事务边界为什么在那、哪些异常会回滚，并拿出回滚的实测证据。**

review 通过 → 导师带你过一遍「事务 & ACID」八股（`docs/数据库八股地图.md` Stage 2，
即 ticket.yaml 的 `submission.bagu_map_ref`）→ 解锁 Stage 3（超卖与锁）。

> 参考解在 `solutions` 分支。ticket.yaml 的 `mentor_policy.mode: mentor_then_reveal` 说的就是这件事：
> 陪练期导师只给思路、**不代写**（D1：`may_write` 绝不与 `files_to_change` 相交，准入门硬卡），
> **过关之后**才拿参考解跟你对照「你的解法 vs 参考解」。
> 强烈建议先自己做完再看——你面试时要能讲出自己的思路，而不是背一个标准答案。
