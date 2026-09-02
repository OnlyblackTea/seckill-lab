# sim —— 前端请求仿真

本目录用两种方式仿真真实前端请求，无需写任何前端页面：

1. **`requests.http`** —— 手动单发请求（调试、看返回 JSON）。
2. **`SeckillLoadSimulator`**（在 `src/test/.../sim/`）—— 高并发打接口（复现超卖/击穿/雪崩）。

---

## 1. requests.http（手动）

用 VS Code 的 **REST Client** 插件，或 IntelliJ IDEA 的 `.http` 支持打开，
点每个请求上方的 `Send Request` 即可发送。适合：

- 验证单个接口的返回结构；
- Stage 1 手动打 `/products/search`，配合数据库里手动 `EXPLAIN` 看索引命中；
- Stage 2/3 手动打 `/seckill` 观察下单行为。

顶部 `@host` 变量默认 `http://localhost:8080`，应用在别处就改它。

---

## 2. 并发模拟器（SeckillLoadSimulator）

一个 tagged JUnit 集成测试，模拟「大量用户同一瞬间点秒杀按钮」：

- `@SpringBootTest(RANDOM_PORT)` 起真实应用；
- `ExecutorService(100)` + `CountDownLatch` 起跑门，让 100 个线程尽量在**同一时刻**发起
  `POST /seckill?userId=&productId=1`；
- 统计 200（成功）/ 501（未实现）/ 其它（异常）三类响应数。

### 运行

```bash
# sim 分组默认被 Surefire 排除，运行时要显式打开分组并清空排除项：
mvn test -Dgroups=sim -Dtest.excludedGroups=
```

### 各 Stage 的预期现象

| Stage | 现象 | 说明 |
|-------|------|------|
| 0 / 1 | `notImplemented=100` | 下单逻辑未实现，接口返回 501，模拟器只统计不断言业务 |
| 2 | `ok` ≈ 100，但可能有重复下单/超卖 | 事务实现了，但还没加锁 |
| **3** | **修复前：`ok > 10`（超卖！）** | 10 个库存却成功了 15 单，这就是要复现的 bug |
| **3** | **修复后：`ok == 10`，库存归 0，零超卖** | 用悲观锁/乐观锁修好后的正确结果 |

### 如何用它制造经典场景（Stage 3 主战场）

**超卖复现**：
1. 先把 `product` id=1 的 stock 设成 10；
2. 跑 `mvn test -Dgroups=sim -Dtest.excludedGroups=`；
3. 数数据库里 `seckill_order` 的行数：
   ```bash
   docker compose -f ../docker/docker-compose.yml exec mysql mysql -uroot -pbagu1234 seckill_lab \
     -e "SELECT COUNT(*) FROM seckill_order WHERE product_id=1; SELECT stock FROM product WHERE id=1;"
   ```
4. 如果 `COUNT(*) > 10` 或 `stock < 0` → **超卖复现成功**，这就是 Stage 3 要你修的。

**击穿/雪崩（Stage 5）**：把模拟器目标改成打「查商品」接口，配合缓存过期，观察 DB 压力。

---

## 3. 为什么用「集成测试」而不是 JMeter/wrk

- 零额外工具：有 JDK + Maven 就能跑，和验收测试同一套体系；
- 真实走 HTTP：`RANDOM_PORT` 起真应用，请求真的穿过 controller→service→mapper→MySQL，
  锁竞争、连接池瓶颈都是真的；
- 可断言：跑完直接查数据库断言「订单数=10、库存=0」，把「有没有超卖」变成客观的红绿信号。

> 注意：模拟器的并发强度受本机 CPU、HikariCP 连接池大小（默认 50）影响。
> 想加大压力就调 `THREADS` 或连接池，但先确保 Stage 3 的锁逻辑正确，再谈压测调参。
