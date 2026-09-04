# seckill-lab · 交互式后端数据库八股实战仓库

> **你的身份**：秒杀组新入职的后端开发。
> **这个仓库**：模拟一个大厂开发组维护的代码库，它按「工单」逐步给你派发开发任务，
> 你亲手写 Spring Boot + MyBatis 代码解决一个个「线上问题」，在过程中吃透数据库八股。
> **导师（AI）职责**：搭台、派活、review、讲八股。**你职责**：动手实现、判断验收。

哲学：**人决策验收、AI 产出、验证闭环**——测试给出客观的红绿信号，你负责动手 + 决策，我负责带教。

---

## 技术栈

- **Spring Boot 3.2.5** + **Java 17** + **Maven**
- **MyBatis 3.0.3**（原生 XML Mapper，**手写 SQL**——不用 MyBatis-Plus/JPA，为的是让你看见每条 SQL、每个索引、每次加锁）
- **MySQL 8.0 (InnoDB)** + **Redis 7**
- **Docker Compose** 起基础设施
- **JUnit 5** + Surefire `@Tag` 分阶段验收（红 → 绿）

## 快速开始

```bash
# 1. 起 MySQL(13306) + Redis(16379)
cd docker && docker compose up -d && docker compose ps   # 等 healthy

# 2. 回根目录，编译 + 启动应用(8080)
mvn -q -DskipTests compile
mvn -q spring-boot:run
#   另开终端验证： curl localhost:8080/products/1

# 3. 跑骨架冒烟测试（应为绿）
mvn test -Dgroups=stage00
```

> ⚠️ **端口说明**：MySQL 用 **13306**、Redis 用 **16379**，故意避开默认端口——
> 因为运行 VM 上 **5432 被别的项目占用**。不要去动 5432。

详细的运行/调试/八股讲解见 **[`docs/新手开发参考.md`](docs/新手开发参考.md)**（新手唯一入口）。

## 从哪开始

1. 读 **[`docs/新手开发参考.md`](docs/新手开发参考.md)** —— 环境、Spring Boot+MyBatis、EXPLAIN、测试、调试。
2. 打开 **[`TASKS/README.md`](TASKS/README.md)** —— 工单路线图与进度看板。
3. 做 **[`TASKS/stage-00-onboarding.md`](TASKS/stage-00-onboarding.md)** —— 入职：拉起环境、跑通冒烟测试。
4. 做 **[`TASKS/stage-01-product-query-index.md`](TASKS/stage-01-product-query-index.md)** —— 你的第一张开发工单。

## 阶段路线

| Stage | 工单 | 八股考点 | 状态 |
|-------|------|---------|------|
| 0 | 入职：拉起环境与骨架 | — | ✅ 骨架已交付 |
| 1 | 商品查询慢 → 写查询 + 加索引 + EXPLAIN | 索引/最左前缀/覆盖索引/EXPLAIN/索引失效 | 🟥 已派发 |
| 2 | 扣库存+建订单要原子 | ACID/传播/回滚/@Transactional | 🔒 待解锁 |
| 3 | 高并发超卖了！ | 行锁/乐观vs悲观/丢失更新 | 🔒 待解锁 |
| 4 | 对账读到脏数据/不一致 | 四隔离级别/MVCC/ReadView | 🔒 待解锁 |
| 5 | 热点商品打挂 DB | Redis 穿透/击穿/雪崩/一致性 | 🔒 待解锁 |
| 6 | 死锁与分布式锁（选修） | 间隙锁/Next-Key/死锁排查/SET NX PX | 🔒 待解锁 |

> Stage 2+ 的工单与验收测试**随你进度逐个解锁**，不预先放出（防剧透）。
> 每阶段流程：发工单 → 你实现 → 导师 review + 跑验收 → 讲八股 → 解锁下一阶段。

## 仓库结构

```
seckill-lab/
├─ docker/            MySQL(13306)+Redis(16379) compose、my.cnf、建表/种子脚本
├─ docs/              新手开发参考 · 架构与约定 · 数据库八股地图
├─ TASKS/             工单：路线图 + stage-00 + stage-01（Stage 2+ 待解锁）
├─ sim/               requests.http 手动用例 + 并发模拟器说明
├─ src/main/          应用代码（controller→service→mapper→XML）+ resources(配置/Mapper/迁移)
└─ src/test/          SmokeTest(stage00) · stage01 验收 · sim 并发模拟器
```

分层与命名规范见 [`docs/架构与约定.md`](docs/架构与约定.md)。

## 测试怎么跑

```bash
mvn test -Dgroups=stage00                    # 骨架冒烟（绿）
mvn test -Dgroups=stage01                    # Stage 1 验收（改代码前红，改对后绿）
mvn test -Dgroups=sim -Dtest.excludedGroups= # 并发模拟器
mvn test                                     # 全部（sim 默认排除）
```

**前置**：跑测试前 MySQL 必须已 `docker compose up -d` 并 healthy。测试连**真 MySQL**，
不用 H2/Testcontainers——因为 H2 没有 InnoDB 的间隙锁/MVCC/EXPLAIN 行为，会教错八股。

## 关于 `solutions` 分支

Stage 1 的参考解只存在于 **`solutions` 分支**，**绝不进 `main`**（防剧透）。
强烈建议先自己做完、review 通过后再对照参考解。你面试时要能讲出**自己的思路**。

---

> 状态说明：本仓库的 Stage 0 骨架、文档、Stage 1 工单与验收测试均已交付。
> 由于开发机未装 JDK/Maven、且导师无 VM shell 权限，**代码尚未经过编译/运行验证**；
> 你在 VM 上按「快速开始」跑通后，把输出反馈给导师，进入正式的「验证闭环」。

---

## License

Copyright (c) 2026 OnlyblackTea

本题库采用 **MIT License** 开源，完整条款见 [`LICENSE`](LICENSE)。

> MIT 宽松许可，便于本仓库作为**社区贡献题库**被 fork、复用、改编，并经标准贡献工作流（脚手架 + 准入校验门）贡献进发卷平台。
