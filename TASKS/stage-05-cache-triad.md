# Stage 5 · 工单：热点商品打挂 DB

> 状态：⬜ 未开始（骨架红）· 前置工单：Stage 4（隔离级别 & MVCC，`db-stage-04`）·
> 考点清单见 ticket.yaml 的 `brief.concepts`
>
> 预期红绿（真机证据跑完后按 Stage 1 的格式补进 `TASKS/evidence-stage-05/REPORT.md`）：
> `mvn -B -Dgroups=stage05 test` 一开始应为 `exit=1`，且红在这两处——
> ① 缓存读写的判据没过（key / TTL / 空值缓存没落全）；
> ② 加载器不做任何并发协调 → 20 个并发线程 = 20 次 DB 加载。你补完两个文件后应为 `exit=0`。
>
> **本工单的权威契约**：[`stages/stage-05-cache-triad/ticket.yaml`](../stages/stage-05-cache-triad/ticket.yaml)
> 版本锚点：工单 id `db-stage-05` · `schema_version=1` · `ticket_version=1` · `test_version=1`
>
> 本文是给人读的**叙述视图**（线上问题的故事 + 关键思考引导）。**改动面、红绿判据、hints 只在
> ticket.yaml 里存一份**，本文不复述——两份清单并存迟早悄悄漂移。冲突时以 ticket.yaml 为准。

## 一、线上问题（工单背景）

**来自：秒杀组 · 优先级：P0**

> 秒杀开场那一刻，值班同学看着监控直冒冷汗：**同一个热点商品**被几十万次查询砸到 MySQL 上，
> DB 连接池瞬间被打满（`Connection is not available, request timed out`）、MySQL CPU 飙到 100%，
> 紧接着商品详情、下单、支付回调**一起**超时——一个热点商品，打挂了整条链路。
> 请你引入 Redis 缓存，把这类问题的三件套分别挡住，并给出可复核的实测证据。

这不是编的——本项目的骨架里 **Redis 是起着的**（容器 `seckill_redis`，宿主端口 **16379** 映射到
容器 **6379**，见 `docker/docker-compose.yml`），`config/RedisConfig.java` 也把
`RedisTemplate<String, Object>` 配好了（key 走 String 序列化、value 走 JSON 序列化，
就是为了让你能直接用 `redis-cli` 看懂里面存了什么）。但商品查询链路上**没有人在用它**：
每次查询都直接落到 DB。热点商品被查多少次，`product` 表就被打多少次。

还有一句话得先说在前面：**「加缓存」本身不解决问题，加错的缓存只是把事故往后推。**
缓存没建好会出现三种各有名字的故障——穿透、击穿、雪崩。它们看上去都是「DB 被打挂了」，
根因和修法却完全不同，而面试官最爱的就是让你把这三个词分开讲。

## 二、你要做什么

一句话：正好改**两个文件**——在 `ProductService` 里实现带缓存的商品查询（含空值缓存与 TTL），
再把 `ProductCacheLoader` 从「直查 DB」改成「只有一个线程重建、其余等待复用」的协调器。

权威定义在 ticket.yaml 的两处，动手前先读：

| 要知道什么 | 去 ticket.yaml 哪里看 |
|---|---|
| 改哪两个文件（也就是你的改动白名单，改别的算越界） | `scope.files_to_change` |
| 这两个文件现在是什么状态、要补成什么语义<br>（key 形状、TTL、空值缓存、互斥锁、计数器口径） | `brief.problem` |

两个文件的分工：

- `src/main/java/com/devteam/seckill/service/ProductService.java` —— 对外的商品查询入口。
  骨架里 `Product getProductCached(Long id)` 已经定好签名（值类型、缓存 key 前缀、
  空值哨兵常量与 TTL 常量都摆在类里），但**读缓存 / 写回 / 设 TTL / 失效这条链路
  要你自己补全**。语义：**先查 Redis**（key 前缀 `product:`，冒号后拼商品 id），
  命中直接返回；未命中才加载并**写回缓存 + 设 TTL**（建议基准 60–300 秒）。顺带实现
  `evictProductCache(Long id)`——删掉某个 id 的缓存，商品变更/下架后靠它失效
  （面试里这就是 Cache Aside 的「删缓存」那一步）。
- `src/main/java/com/devteam/seckill/service/ProductCacheLoader.java` —— 骨架里的「加载器」
  现在是**不做任何并发协调的直查实现**：每次调用都先给计数器 +1、再执行 `fallback`
  （真打一次 DB），所以并发来了就并发打 DB。你要把它改成防击穿的形态（单飞 /
  single-flight）：**同一个 id 并发进来时只让一个线程真去查 DB，其余线程等它结束后复用
  同一个结果**。骨架里已经备好一个 `ConcurrentHashMap<Long, Object>` 分段锁字段，
  用不用、怎么用由你决定；用 Redis `SET NX PX` 跨进程锁也是合法解法。

「其余线程等待并复用」这句话是本工单的技术核心，也是它的验收核心。而且它有个容易漏的边界：
**查一个不存在的 id，也同样要只打一次 DB。** 因为「不存在」也是一种需要被记录下来的结果——
不记录，别人拿一串不存在的 id 反复请求，每次都穿过缓存打到 DB（穿透）；记录下来，
并发查同一个不存在的 id 时又得只让一个线程去确认（击穿）。两件事在这一处叠在一起，
`brief.problem` 里写死了这两个要求，别只做一个。

另外，骨架在 `ProductCacheLoader` 上暴露了一个**查询计数器**（`long loadCount()` /
`void resetLoadCount()`，内部是 `LongAdder`），它统计的是**真实打到 DB 的加载次数**。
验收测试就靠它来量你的效果：

```text
resetLoadCount(); 20 个线程同时查同一个不存在的 id; →  loadCount() 必须 == 1
```

这里有个容易写错的地方：计数必须留在「**真正执行 fallback 的那一次**」上。
等待复用的那 19 个线程如果也各 +1，计数就变成 20，判据直接判你失败。同理，
别绕过加载器自己去调 Mapper，也别在命中缓存时去碰计数器——那会让计数失去意义。

## 三、关键思考（不给答案，自己想）

先别写代码，先回答下面几个问题——面试里这部分比代码值钱：

1. **缓存 key 怎么设计？** 为什么用 `product:` 这样的前缀 + id，而不是「商品名称」或者
   一个裸 id？前缀对排查（批量看、批量清）有什么用？
2. **为什么写完必须设 TTL？** 一个永不过期的缓存 key，在商品改价、下架之后会发生什么？
   你会在这条链路上再用什么手段兜住它？
3. **空结果怎么缓存？** value 走的是 JSON 序列化，所以 Redis 里存的到底是什么？
   更关键的是：`GET` 一个「存了 JSON null」的 key 和 `GET` 一个「根本不存在的 key」，
   在 `redis-cli` 里长得一样——那你的代码怎么区分「没缓存」和「缓存了空」？
   这条空缓存的 TTL，该比真数据长还是短？为什么？
4. **同一个热点 key 刚过期的那一瞬间，100 个并发请求同时未命中，会发生什么？**
   要让「只有一个线程真去查 DB」，你需要什么手段？骨架给了你两条路——本地按 id 分段加锁
   （`ProductCacheLoader` 里那个 `ConcurrentHashMap`），或者 Redis 的 `SET key value NX PX ttl`
   跨进程抢锁。两者分别在什么场景下更合适？如果用 Redis 锁，为什么它必须带自动过期时间？
   为什么拿到锁之后**还要再查一次缓存**（双重检查）？没抢到锁的线程该干什么？
   还有最关键的一句：**「等待复用」的那次结果里如果包含 `null`（商品不存在），
   你怎么把它和「没等到结果」区分开？**
5. **TTL 是好事，那它会不会变成新的雪崩源头？** 如果一批热点商品在同一个时刻被预热、
   TTL 又都是同一个固定值，它们会在什么时刻一起过期？你怎么把这个时刻打散？
   （骨架的 `ProductService` 里已经写了 TTL 常量与一种抖动的做法，别照抄数值——
   先想清楚「基准多久 + 抖动多大」为什么合理，以及它和你手上这条业务链路的变更频率是否匹配。）
6. 最后回看 Stage 4：缓存里那个值有可能是**旧**的。你现在选的是「先更 DB 再删缓存」还是
   别的顺序？为什么是**删**缓存而不是把新值**更新**进缓存？如果删缓存失败了呢？

想清楚这几点，你的缓存层自然就定了。这是面试最爱问的「缓存三件套 + 一致性」。

## 四、怎么验收

```bash
# 先把基础设施拉起来（Redis 是 Stage 5 的必需品）
docker compose -f docker/docker-compose.yml up -d

mvn test -Dgroups=stage05
```

**一开始必须是红的**，而且必须红在该红的地方：缓存读写的判据没过（key / TTL / 空值缓存
没落全）+ 并发场景下 DB 加载次数 = 线程数（加载器没有任何并发协调）。

具体判据不在本文重复，去 ticket.yaml 看：

- `grader.correctness.expect` —— 红/绿各是什么样，四条绿色判据分别是
  ① 命中缓存不再打 DB、② 不存在的 id 也写空缓存、③ 20 并发查同一个不存在的 id
  → `loadCount == 1`、④ `evictProductCache` 之后重新打 DB；以及**哪种红是红错了地方**
  （Redis 连不上 / MySQL 连不上属于环境问题，不算「红在该红的地方」——
  先把 docker 拉起来再跑）；
- `grader.concurrency.assert` —— 并发口径上硬断言什么（`db_loads == 1 for N concurrent misses`，
  注意是**恰好** 1：0 说明你压根没加载，>1 说明单飞协调没生效）；
- `grader.performance` —— 本工单是 `null`：Stage 5 不看执行计划（那是 Stage 1 的判据），
  只看「并发下 DB 被打了几次」。

四条判据全过 = 工单完成 = 测试变绿。

## 五、Hints

卡住再看，别直接看答案：**ticket.yaml 的 `hints`**，H1 → H4 阶梯式，越往后越接近答案。
（H1 管 key 设计与「为什么必须设 TTL」，H2 管空值缓存的哨兵形态与它的 TTL 长短，
H3 给手动验证命令——`redis-cli` 看 key / `TTL` / `GET`，注意容器内是 6379、宿主机是 16379，
H4 讲透穿透 / 击穿 / 雪崩的区别、单飞协调（本地分段锁或 `SET NX PX` 互斥锁 + 双重检查）
为什么能防击穿、以及 TTL 随机抖动为什么能防雪崩——但**都不给可直接抄的完整代码**。）

平台版里每展开一条都会计入学习者画像，成为「独立性」评审维度的输入（设计 §11.2）——
所以现在也建议你先自己想，想不动了再一级级往下开。

## 六、做完之后

按 ticket.yaml `submission.template.require` 的**三件套**贴给导师 review。这就是「PR 即答辩」
（设计 §11.1，本项目的「做完之后三件套」正是它的出处）：

1. 改后的 `ProductService` / `ProductCacheLoader` 关键代码（缓存读写、空值缓存与 TTL、
   互斥锁 + 双重检查、失效方法）；
2. 一句话分别说清你怎么防住**穿透**、怎么防住**击穿**，以及你对**雪崩**做了什么；
3. 实测证据：改动前后 `loadCount()` 的对比数字（尤其是 20 并发查同一个不存在的 id 时的
   加载次数），以及 `redis-cli` 里看到的 key / `TTL` / 空值。

review 通过 → 导师带你过一遍「Redis 缓存三件套」八股（`docs/数据库八股地图.md` Stage 5，
即 ticket.yaml 的 `submission.bagu_map_ref`）→ 解锁 Stage 6（选修：死锁 & 分布式锁，
那里你会把今天这把 `SET NX PX` 锁的「释放」一步真正做对）。

> 参考解在 `solutions` 分支。ticket.yaml 的 `mentor_policy.mode: mentor_then_reveal` 说的就是这件事：
> 陪练期导师只给思路、**不代写**（D1：`may_write` 绝不与 `files_to_change` 相交，准入门硬卡），
> **过关之后**才拿参考解跟你对照「你的解法 vs 参考解」。
> 强烈建议先自己做完再看——你面试时要能讲出自己的思路，而不是背一个标准答案。
