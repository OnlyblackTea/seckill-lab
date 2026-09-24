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
| 0 | 入职：拉起环境与骨架 | — | ✅ 已交付（VM 实测绿） |
| 1 | 商品查询慢 → 写查询 + 加索引 + EXPLAIN | 索引/最左前缀/覆盖索引/EXPLAIN/索引失效 | ✅ 已交付（VM 实测：骨架红 / 参考解绿） |
| 2 | 扣库存+建订单要原子 | ACID/传播/回滚/@Transactional | 🟥 已派发（契约 `db-stage-02`） |
| 3 | 高并发超卖了！ | 行锁/乐观vs悲观/丢失更新 | 🟥 已派发（契约 `db-stage-03`） |
| 4 | 对账读到脏数据/不一致 | 四隔离级别/MVCC/ReadView/快照读vs当前读 | 🟥 已派发（契约 `db-stage-04`） |
| 5 | 热点商品打挂 DB | Redis 穿透/击穿/雪崩/一致性 | 🟥 已派发（契约 `db-stage-05`） |
| 6 | 死锁与分布式锁（选修） | 间隙锁/Next-Key/死锁排查/SET NX PX | 🟥 已派发（契约 `db-stage-06`） |

> **全阶段真机实测（2026-09-24 · VM 10.32.10.102 · JDK17 + mysql:8.0/redis:7）**
>
> | ref | `mvn -Dgroups=stage00…stage06,sim test` |
> |---|---|
> | `solutions`（参考解） | `exit=0` · Tests run: **26, Failures: 0, Errors: 0** |
> | `main`（骨架） | `exit=1` · Tests run: **26, Failures: 6, Errors: 14** |
>
> 骨架各阶段红在**各自的缺陷**上：stage02 红在"重复下单失败后库存没回滚"、
> stage03 实测超卖（`expected: <10> but was: <30>`）、stage04 红在 `order_stat` 缺失、
> stage05 红在缓存未实现、stage06 红在锁未实现。
> 参考解侧：stage03 恰好卖 10 / 库存 0、sim 100 线程零超卖、stage05 防击穿 DB 加载 20→1。
> 证据与原始日志：[`TASKS/evidence-stages-02-06/REPORT.md`](TASKS/evidence-stages-02-06/REPORT.md)。

> 每个 stage 的题面、改动白名单、红绿判据、hints 都只存一份，在 `stages/<stage>/ticket.yaml`；
> `TASKS/*.md` 是给人读的叙述视图。参考解只存在于 `solutions` 分支，**绝不进 `main`**。

## 仓库结构

```
seckill-lab/
├─ track.yaml         ★Track 清单（发卷平台契约）：manifest 惰性声明 + runtime 可执行入口 + digest_pin
├─ stages/            ★工单契约：stage-01-product-query-index/ticket.yaml（机器可读，权威源）
├─ scripts/           ★build.sh（准入门的骨架构建入口）· digest-pin.sh（重算 digest_pin）
├─ .gitattributes     ★强制 LF 检出——digest_pin 对文件内容取摘要，CRLF/LF 漂移会算出不同 pin
├─ docker/            MySQL(13306)+Redis(16379) compose、my.cnf、建表/种子脚本
├─ docs/              新手开发参考 · 架构与约定 · 数据库八股地图（= track.yaml 的 bagu_map）
├─ TASKS/             工单叙述版：路线图看板 + stage-00 + stage-01（Stage 2+ 待解锁）
├─ sim/               requests.http 手动用例 + 并发模拟器说明
├─ src/main/          应用代码（controller→service→mapper→XML）+ resources(配置/Mapper/迁移)
└─ src/test/          SmokeTest(stage00) · stage01 验收 · sim 并发模拟器
```

> ★ = 发卷平台（bagu-trainer）的 Track 契约文件，见下面「本仓是发卷平台的第一个 Track」。
> 它们不影响你写代码、跑测试；学习者日常只需读 `TASKS/`，只是**事实以 `stages/*/ticket.yaml` 为准**。

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

> 这一约定在 Track 契约里的表达是 `ticket.yaml` 的 `mentor_policy.mode: mentor_then_reveal`
> （导师只讲解、不代写：`may_write: []`，与 `scope.files_to_change` 天然不相交）。

## 本仓是发卷平台的第一个 Track

[`bagu-trainer`](../bagu-trainer) 是「发卷平台」（学习者做卷子、AI 当考官/导师、平台本地阅卷）。
本仓就是它设计文档 §3 / §4.1 点名的 **第一个 Track：数据库八股**——骨架仓即本仓自身
（`track.yaml` 里 `manifest.skeleton_repo: "."`）。

契约映射（设计文档附录 B 的落地）：

| 本仓现状 | Track 契约落点 |
|---|---|
| 本仓根目录（Spring Boot 工程） | `track.yaml` → `manifest.skeleton_repo: "."` |
| `TASKS/stage-01-product-query-index.md` | `stages/stage-01-product-query-index/ticket.yaml`（**权威源**，md 是叙述视图） |
| `TASKS/stage-00-onboarding.md` | 不出 ticket（入职不改代码），理由见 [`stages/README.md`](stages/README.md) |
| `mvn test -Dgroups=stage01` | `ticket.yaml` → `grader.correctness.test_group` + `test_version` |
| `src/test/.../stage01/ProductQueryIndexAcceptanceTest.java` | `grader.correctness.canonical_test` + `track.yaml` → `runtime.tests` |
| `src/test/.../sim/SeckillLoadSimulator.java` | `track.yaml` → `runtime.sim`（Stage 3 超卖仿真用） |
| `docker/docker-compose.yml` | `runtime.compose` + `digest_pin` |
| `docs/数据库八股地图.md` | `manifest.bagu_map` + `submission.bagu_map_ref`（带 GitHub 锚点） |
| `docs/架构与约定.md` | 代码质量评审依据（设计文档 §11.2） |
| `solutions` 分支 | `mentor_policy.mode: mentor_then_reveal` |

准入命令：`paper validate-track`（paper-cli 目前只有脚手架，实际用平台侧
`platform_service.admission.validate_track()` 这道门）。**当前实测结果：`ok = True`**，
6 道硬门（schema / files_to_change / bagu_map_anchor / runtime_pinned / trust_level /
d1_role_reversal）全 PASS，`no_spoiler` 软 PASS（参考解在分支上，仓内无 `solutions/` 目录可扫）。

✅ **判别性（设计 §4.6）已实测**：同一套 canonical 测试在两个 ref 上跑出了相反的结果——

| ref | `mvn test -Dgroups=stage01` | 结论 |
|---|---|---|
| `main`（骨架） | `exit=1` · Tests run: 2, **Failures: 1, Errors: 1** | 红 —— 该红 |
| `solutions`（参考解） | `exit=0` · Tests run: 2, **Failures: 0, Errors: 0** | 绿 —— 该绿 |

这正是准入门 `discriminance` 要求的形状：测试**能区分**「没做」和「做对了」，而不是恒红或恒绿
（恒红的卷子会冤枉做对的人，恒绿的卷子会放过没做的人）。在此之前该项只能是 `skipped`
（没注入 runner），现已用真 mvn 补齐证据。

> 跑法：`git worktree add /tmp/seckill-sol solutions` 后在 worktree 里执行测试，
> **主工作树全程没被切换过**——参考解不会被检出到学习者眼前，防剧透不破。
> `solutions` 分支只在本地与 VM 之间流转，**没有推到 GitHub**（`origin` 上只有 `main`）。

---

> **状态说明（全部为 VM 实测，不是推断）** · 环境：VM 192.168.1.147（linux/amd64）· 2026-09-09
>
> - **Stage 0 骨架**：`mvn test -Dgroups=stage00` 全绿（Tests run: 3, Failures: 0, Errors: 0）。
> - **Stage 1 骨架**：红在正确的地方（Tests run: 2, Failures: 1, Errors: 1 ——
>   `BindingException: searchProducts 未绑定` + EXPLAIN `type=ALL / key=null / rows=50063 /
>   Using where; Using filesort`）。**Stage 1 参考解**：绿（见上表）。
> - **契约落地零回归**：`pom.xml` 与 `src/test` 一行未动；VM `git pull` 到本次新增的契约文件后，
>   上面那两条红/绿完全复现（`create mode 100755` 也说明脚本可执行位穿过了 GitHub 往返没丢）。
> - **`digest_pin` 跨平台可复现**：`scripts/digest-pin.sh` 在 Linux(VM) 上打出的六个 pin，与
>   Windows 上算出并写进 `track.yaml` 的**逐字相同**。这验证了 `.gitattributes` 钉 `eol=lf`
>   \+ 脚本内 CRLF 归一化这两道保险是有效的——本仓 `core.autocrlf=true`，不做归一化的话
>   同一份内容在两个平台会算出不同 pin，pin 就失去意义。四个文件类 pin 是 LF 归一化后的
>   Merkle 式树摘要；`mysql` / `redis` 两个是 VM 上 `docker image inspect` 的真实 RepoDigest。
>
> **2026-09-24 复验（Stage 1 已实现，同一判据重跑）** · 环境：VM 10.32.10.102 · JDK 17.0.20 ·
> Maven 3.8.7 · Docker 29.1.3 · `mysql:8.0`/`redis:7`，每次跑前 `docker compose down -v` 重置数据卷：
>
> | 被测 ref / 内容 | `mvn test -Dgroups=stage01` | 结论 |
> |---|---|---|
> | 干净骨架（改动前） | `exit=1` · Tests run: 2, **Failures: 1, Errors: 1** | 红 —— 仍是该红的形状 |
> | Stage 1 实现（本题改动） | `exit=0` · Tests run: 2, **Failures: 0, Errors: 0** | 绿 |
> | `solutions` 分支参考解 | `exit=0` · Tests run: 2, **Failures: 0, Errors: 0** | 绿（与实现 DDL/SQL 逐字一致） |
>
> 规范化 EXPLAIN：`type=ALL / key=NULL / rows=50070 / Using where; Using filesort`
> → `type=range / key=idx_product_search / rows=1 / Using index condition`。
> 完整证据（含索引列序隔离实验与参考解交叉验证）见 [`TASKS/evidence-stage-01/`](TASKS/evidence-stage-01/REPORT.md)。

---

## License

Copyright (c) 2026 OnlyblackTea

本题库采用 **MIT License** 开源，完整条款见 [`LICENSE`](LICENSE)。

> MIT 宽松许可，便于本仓库作为**社区贡献题库**被 fork、复用、改编，并经标准贡献工作流（脚手架 + 准入校验门）贡献进发卷平台。
