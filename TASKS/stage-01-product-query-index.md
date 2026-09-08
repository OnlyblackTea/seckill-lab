# Stage 1 · 工单：商品搜索查询慢

> 状态：🟥 已派发（验收测试初始为红）· 考点清单见 ticket.yaml 的 `brief.concepts`
>
> **本工单的权威契约**：[`stages/stage-01-product-query-index/ticket.yaml`](../stages/stage-01-product-query-index/ticket.yaml)
> 版本锚点：工单 id `db-stage-01` · `schema_version=1` · `ticket_version=1` · `test_version=1`
>
> 本文是给人读的**叙述视图**（线上问题的故事 + 关键思考引导）。**改动面、红绿判据、hints 只在
> ticket.yaml 里存一份**，本文不复述——两份清单并存迟早悄悄漂移。冲突时以 ticket.yaml 为准。

## 一、线上问题（工单背景）

**来自：商品组 · 优先级：P1**

> 运营反馈：商品列表页的「按分类 + 上架状态 + 价格区间」搜索越来越慢。
> `product` 表现在有 5 万多行，每次搜索都卡。DBA 看了一眼说：**「你们这查询走的是全表扫描，
> 一个合适的索引都没有。」** 请你排查并优化，用 EXPLAIN 证明索引真的被用上了。

这不是编的——本项目的 `product` 表**故意只建了主键，没有任何二级索引**，
所以带 `WHERE category_id / status / price` 的查询必然全表扫描。

## 二、你要做什么

一句话：正好改**两个文件**——补全 `searchProducts` 的 XML SQL，再给 `product` 表加一个合适的联合索引。

权威定义在 ticket.yaml 的两处，动手前先读：

| 要知道什么 | 去 ticket.yaml 哪里看 |
|---|---|
| 改哪两个文件（也就是你的改动白名单，改别的算越界） | `scope.files_to_change` |
| 这两个文件现在是什么状态、要补成什么语义<br>（SQL 形状、`resultMap`、索引名的硬要求） | `brief.problem` |

## 三、关键思考（不给答案，自己想）

设计联合索引的列顺序时，问自己：

1. WHERE 里哪些条件是**等值**（`=`），哪些是**范围**（`BETWEEN`）？
2. `ORDER BY` 用的是哪一列？
3. **最左前缀原则**下，等值列、范围列、排序列在联合索引里应该怎么排？
   （提示：范围列之后的索引列会失效——想想这对 `ORDER BY` 意味着什么。）
4. 能不能做成**覆盖索引**，让查询完全不回表？`Extra` 里看到 `Using index` 就是。

想清楚这几点，你的索引顺序自然就定了。这是面试最爱问的「联合索引怎么设计」。

## 四、怎么验收

```bash
mvn test -Dgroups=stage01
```

**一开始必须是红的**，而且必须红在该红的地方：`searchProducts` 未绑定 + EXPLAIN 全表扫描。

具体判据不在本文重复，去 ticket.yaml 看：

- `grader.correctness.expect` —— 红/绿各是什么样、fixture 命中几条、以及**哪种红是红错了地方**
  （迁移脚本若变成「整份全是注释」，`@Sql` 会先抛空脚本错，那不是我们要的红）；
- `grader.performance.assert` —— EXPLAIN 上硬断言什么。

两个断言都过 = 工单完成 = 测试变绿。

## 五、Hints

卡住再看，别直接看答案：**ticket.yaml 的 `hints`**，H1 → H4 阶梯式，越往后越接近答案。

平台版里每展开一条都会计入学习者画像，成为「独立性」评审维度的输入（设计 §11.2）——
所以现在也建议你先自己想，想不动了再一级级往下开。

## 六、做完之后

按 ticket.yaml `submission.template.require` 的**三件套**贴给导师 review。这就是「PR 即答辩」
（设计 §11.1，本项目的「做完之后三件套」正是它的出处）。

review 通过 → 导师带你过一遍「索引 & 执行计划」八股（`docs/数据库八股地图.md` Stage 1，
即 ticket.yaml 的 `submission.bagu_map_ref`）→ 解锁 Stage 2（下单事务）。

> 参考解在 `solutions` 分支。ticket.yaml 的 `mentor_policy.mode: mentor_then_reveal` 说的就是这件事：
> 陪练期导师只给思路、**不代写**（D1：`may_write` 绝不与 `files_to_change` 相交，准入门硬卡），
> **过关之后**才拿参考解跟你对照「你的解法 vs 参考解」。
> 强烈建议先自己做完再看——你面试时要能讲出自己的思路，而不是背一个标准答案。
