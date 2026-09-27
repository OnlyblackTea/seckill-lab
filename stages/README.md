# stages/ —— 工单（ticket）契约目录

每个**已发布**的 stage 一个子目录，里面放该 stage 的 `ticket.yaml`：

```
stages/
└─ stage-01-product-query-index/
   └─ ticket.yaml      机器可读的权威工单契约（bagu-trainer content/schema/ticket.schema.json）
```

发卷平台按 `stages/*/ticket.yaml` 发现工单（`platform_service/admission.py` 就是这么 glob 的），
所以**目录名必须与 `track.yaml` 的 `manifest.stages` 条目一致**。

## 分支结构（发卷平台真正依赖的东西）

目录名不只是归档习惯——**它就是 git 分支名**。平台的 Forge 在 provision 时会：

1. `base_ref = origin/<目录名>`：该阶段的**起始代码**（学员这一关的基线）；
2. `head_ref = origin/<目录名>-sol`：该阶段的**参考解 ref**（桩 Forge 用它当"学员已改对"的另一端，
   好让 diff 有两端可比）。

两个 ref 缺一个都会在 `POST /instances/{id}/provision` 阶段报 `workspace_ref_missing`
（`platform_service/forge.py::must_have_ref`），错误信息里会列出仓库现有的 ref。

### 阶段分支是**一条链**，不是六个兄弟（2026-09-27 起）

```
main ── 89780f9 完整骨架（全阶段测试；Stage 1 已交付，其余未实现）
  │
stage-01-product-query-index      = 骨架（Stage 1 未实现）+ 截至 Stage 1 的测试
  │
stage-02-order-transaction        = stage-01 + **本阶段测试**
  │
stage-03-oversell-and-lock        = stage-02 + **本阶段测试**
  │
…                                 （stage-04 / 05 / 06 同理，逐级叠上去）
```

**每个阶段分支只比上一级多了"本阶段的验收测试"。** 没有实现代码、没有参考解、也没有后续阶段的
测试。`-sol` 是**旁支**：`stage-NN-<name>-sol` 的父亲就是同阶段的 base，内容 = base + 截至该阶段的
参考实现（所以它在该阶段的判据上应当**绿**，而 base 应当**红**）。

| 分支 | 内容 | 在它上面跑 |
|---|---|---|
| `stage-NN-<name>` | 骨架（Stage 1..NN 都**未实现**）+ 截至本阶段的验收测试 | 应当**红**（学员还没动手） |
| `stage-NN-<name>-sol` | 上面那份 + 截至本阶段的参考实现（后续阶段仍是骨架） | 应当**绿** |

**为什么是链**（两条都是真踩出来的）：

1. **合并要"只加不删"。** 学员那条 `dev` 是跨阶段累积的：他做完 Stage 1、再进 Stage 2，做的是
   `git merge origin/stage-02-order-transaction`。旧结构里六条分支都是从 `main` 分出去的**兄弟**，
   而学员那条线（从 `stage-01` 长出来）**删掉了**后续阶段的测试文件；git 把"你这边删了、那边没动"
   判成删除 ⇒ **合并完 Stage 2 的验收测试根本没进来**，`mvn -Dgroups=stage02` 一条用例都跑不到，
   红在一个跟代码无关的地方。链式之后，合并的基点是上一阶段分支，"新增本阶段测试"是一次**新增**，
   不会丢。
2. **基线里不能夹带上阶段的官方答案。** 旧结构里 `stage-02`..`stage-06` 都是从 `main` 分出去的，
   而 `main` 上 Stage 1 已经交付 ⇒ 学员一进 Stage 2 就能在工作区里读到 Stage 1 的**参考实现**
   （`V1__stage01_product_search_index.sql` 里那个索引）。链式结构让每一级都保持在"本级及以前
   都未实现"，顺手把这个泄露堵掉。

真正的防剧透边界因此比从前更清楚：**阶段分支里没有任何实现**，唯一的答案在 `-sol` 分支上
（平台只在判据判别性校验与"过关后对照"时用它），而 `solutions` 仍是全阶段总参考。

`main` 是完整骨架（含全部阶段测试）用于整体体检；`solutions` 是**全阶段**参考解，
**只作总参考**，不作为任何 stage 的 base/head——一次 clone 就把全部答案给出去不符合 §8 的防剧透模型。

`stage-00-onboarding` 不出 ticket，因此**不需要分支**（它只上 `manifest.stages` 看板）。

### 学员怎么过关（平台会把这句递到眼前）

```bash
# 第一次（Stage 1）：平台 provision 出来的 dev 就是从 stage-01 开出来的，直接用
# 之后每一关：先把本阶段基线合进你自己那条 dev
git merge origin/stage-02-order-transaction     # 只加不删，不会和你写的实现打架
# 写代码 → 提交 → push dev → 回平台「提交」
```

这次合并**只加不删**（相对分岔点只有"新增本阶段测试"这一件事），所以它可以照抄、不需要解冲突。
不做的唯一后果是判据不在工作区里——平台现在会直接说这句话，并给出这条命令
（`platform_api/grading_docker.py::_missing_canonical_test`）。

> **给 Track 作者的契约**：新增一个阶段时，从**上一个阶段的分支**切出新分支，只把该阶段的验收测试
> 放进去，别把实现或后续阶段的测试带进来；`-sol` 从同阶段 base 切出，放截至该阶段的实现。
> `git log --oneline` 应当能一眼看出"阶段 N 比 N-1 多了哪个测试文件"。

## ticket.yaml 与 TASKS/*.md 的关系

| | 是什么 | 给谁看 |
|---|---|---|
| `stages/stage-NN/ticket.yaml` | **权威契约**：身份/版本锚点、题面、改动面、红绿判据、hints、答辩要求、导师权限、解锁依赖 | 平台（AI 经理发卷、验收、控解锁） |
| `TASKS/stage-NN.md` | 叙述视图：线上问题的故事、「关键思考」引导题、怎么做完之后的流程 | 学习者（paper-cli 落地前的唯一渲染） |

**冲突时以 ticket.yaml 为准。** 事实（文件清单、判据、hints）只在 yaml 里存一份，md 里改成指针，
避免两边各写一份然后悄悄漂移。

### 题面怎么写：规范在平台仓，本仓只放指针

上面的表说的是"哪个字段归谁"；"这些字段里的字该怎么写"由**平台仓定、正文只存一份**：

> **`bagu-trainer` 的 `docs/工单写作规范.md`。**

本仓是示例 Track，**不复制**那份规范——复制出来的第二份迟早和它漂移。要点是
**自然段承载叙事，标题承载语义层级，列表只表达真正的并列关系，组件承载交互状态**；
那份文档还给出 `brief.problem` 的结构模板、`grader.correctness.expect` 的写法、
hints 的阶梯写法（**每条 hints 都要有 `title`**，页面上显示成「提示 N：标题」）和发布前自检清单。

两份文档的分工别搞混：这份管**契约怎么填**（分支结构、字段、准入门），那份管**内容怎么写**。

## 为什么 `stage-00-onboarding` 没有 ticket.yaml

Stage 0 是**环境入职**（拉起 docker、跑通冒烟测试、读懂分层），不是考核工单。两条硬约束卡死了它：

1. `ticket.schema.json` 的 `scope.files_to_change` 是 `minItems: 1`，而 Stage 0 **不改任何文件**
   （它的任务书第一句就写着「这张工单不需要写业务代码」）；
2. 准入门 §4.6 的**判别性**要求 canonical 测试「骨架上红、参考解上绿」，而 Stage 0 的判据
   `SmokeTest`（`@Tag stage00`）在骨架上**天生就是绿的**——它是环境体检，不是红→绿的考核。

所以 `db-stage-01` 是解锁 DAG 的**根节点**（`progression.prerequisites: []`，发卷即 available），
Stage 0 只作为看板上的一个 stage 名保留在 `track.yaml` 的 `manifest.stages` 里，
其任务书继续是 `TASKS/stage-00-onboarding.md`。

> **给 bagu-trainer 上游的一条反馈**：当前 ticket schema 无法表达「不改代码的入职/体检 ticket」
> （`files_to_change` 强制非空 + 判别性门要求骨架红）。若将来想让 Stage 0 这类 onboarding
> 也进平台状态机，需要给 schema 加一个 `kind: onboarding | assessment` 之类的区分，
> 并让判别性门对 onboarding 反转判据（骨架**绿**才算合格）。本仓暂不自行扩展 schema。
