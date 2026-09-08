# stages/ —— 工单（ticket）契约目录

每个**已发布**的 stage 一个子目录，里面放该 stage 的 `ticket.yaml`：

```
stages/
└─ stage-01-product-query-index/
   └─ ticket.yaml      机器可读的权威工单契约（bagu-trainer content/schema/ticket.schema.json）
```

发卷平台按 `stages/*/ticket.yaml` 发现工单（`platform_service/admission.py` 就是这么 glob 的），
所以**目录名必须与 `track.yaml` 的 `manifest.stages` 条目一致**。

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
