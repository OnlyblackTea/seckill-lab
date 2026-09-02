# Stage 0 · 入职：拉起环境与骨架

> 状态：⬜ 未开始 · 这张工单**不需要写业务代码**，目标是把开发环境跑起来、读懂仓库结构、
> 亲眼看到骨架冒烟测试是绿的。这是你后面所有工单的地基。

## 背景

欢迎加入秒杀组。我是你的导师。在你接第一张真正的开发工单（Stage 1）之前，
先确认你的开发环境能跑起来、能连上数据库、能跑通测试。大厂新人第一天也是干这个。

## 你要做什么（Checklist）

### 1. 启动基础设施（MySQL + Redis）

```bash
cd docker
docker compose up -d
docker compose ps            # 等 mysql / redis 都变成 healthy
```

> ⚠️ 端口说明：本项目 MySQL 映射到宿主机 **13306**、Redis 映射到 **16379**，
> 故意避开默认端口，因为运行 VM 上 **5432 被别的项目占用**。别去动 5432。

### 2. 确认种子数据就绪

```bash
docker compose exec mysql mysql -uroot -pbagu1234 \
  -e "USE seckill_lab; SELECT COUNT(*) FROM product;"
# 期望：50003（3 条手工种子 + 50000 条批量种子，批量灌数据首次要等十几秒）
```

如果 count 不对或报表不存在，说明 init 脚本没跑完或被中断：

```bash
docker compose down -v && docker compose up -d   # 一键重置，重新初始化
```

### 3. 编译并启动应用

```bash
# 回到仓库根目录
mvn -q -DskipTests compile      # 首次会下载依赖，耐心等
mvn -q spring-boot:run          # 启动应用，监听 8080
```

另开一个终端验证接口：

```bash
curl localhost:8080/products/1
# 期望返回：{"code":0,"message":"ok","data":{"id":1,"name":"iPhone 秒杀专场",...,"stock":10,...}}
```

### 4. 跑骨架冒烟测试（应为绿）

```bash
mvn test -Dgroups=stage00
# 期望：BUILD SUCCESS，SmokeTest 全绿
```

冒烟测试做了三件事：Spring 上下文能启动、`GET /products/1` 返回 200 且 body 正确、
`GET /products/999999999`（不存在的商品）返回 404。

### 5. 读懂仓库结构

对照 `docs/架构与约定.md` 的分层图，把这四个文件从头读一遍，理解一次请求怎么流动：

- `controller/ProductController.java`
- `service/ProductService.java`
- `mapper/ProductMapper.java` + `resources/mapper/ProductMapper.xml`
- `resources/application.yml`（看连接参数怎么配的）

### 6. 玩一下手动请求

用 VS Code / IDEA 打开 `sim/requests.http`，逐个点 `Send Request`，
观察查商品、搜索、下单（此时返回 501，因为还没实现）的响应。

## 验收标准

- [ ] `docker compose ps` 显示 mysql、redis 均 healthy
- [ ] `SELECT COUNT(*) FROM product` 返回 50003
- [ ] `curl localhost:8080/products/1` 返回正确 JSON
- [ ] `mvn test -Dgroups=stage00` 全绿
- [ ] 你能口头说清楚「一次 GET /products/1 请求经过哪几层」

## 遇到问题

- **连不上 MySQL**：检查 `docker compose ps` 是否 healthy、端口是否 13306、密码是否 `bagu1234`。
- **应用起不来报端口占用**：改 `SERVER_PORT` 环境变量，或 `docker compose down` 清掉残留容器。
- **测试报连接失败**：确认 MySQL 容器在跑，且你没用 H2（本项目测试连真 MySQL，见 `docs/新手开发参考.md` §5）。
- 还不行 → 把完整报错贴给导师。

## 下一步

全部勾完 → 打开 `stage-01-product-query-index.md`，接你人生第一张开发工单。
