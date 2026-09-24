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

1. `base_ref = origin/<目录名>`：该阶段的**起始代码**（学员拿到的骨架）；
2. `head_ref = origin/<目录名>-sol`：该阶段的**参考解 ref**（桩 Forge 用它当"学员已改对"的另一端，
   好让 diff 有两端可比）。

两个 ref 缺一个都会在 `POST /instances/{id}/provision` 阶段报 `workspace_ref_missing`
（`platform_service/forge.py::must_have_ref`），错误信息里会列出仓库现有的 ref。

因此每个**已评估**的 stage 都对应两条分支：

| 分支 | 内容 | 在它上面跑 |
|---|---|---|
| `stage-NN-<name>` | 骨架 + **截至本阶段**的验收测试（不含后续阶段的测试，否则等于剧透判据） | 应当**红**（学员还没动手） |
| `stage-NN-<name>-sol` | 上面那份 + **截至本阶段**的参考实现（后续阶段仍是骨架） | 应当**绿** |

`main` 是完整骨架（含全部阶段测试）用于整体体检；`solutions` 是**全阶段**参考解，
**只作总参考**，不作为任何 stage 的 base/head——一次 clone 就把全部答案给出去不符合 §8 的防剧透模型。

`stage-00-onboarding` 不出 ticket，因此**不需要分支**（它只上 `manifest.stages` 看板）。

## ticket.yaml 与 TASKS/*.md 的关系

| | 是什么 | 给谁看 |
|---|---|---|
| `stages/stage-NN/ticket.yaml` | **权威契约**：身份/版本锚点、题面、改动面、红绿判据、hints、答辩要求、导师权限、解锁依赖 | 平台（AI 经理发卷、验收、控解锁） |
| `TASKS/stage-NN.md` | 叙述视图：线上问题的故事、「关键思考」引导题、怎么做完之后的流程 | 学习者（paper-cli 落地前的唯一渲染） |

**冲突时以 ticket.yaml 为准。** 事实（文件清单、判据、hints）只在 yaml 里存一份，md 里改成指针，
避免两边各写一份然后悄悄漂移。

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
