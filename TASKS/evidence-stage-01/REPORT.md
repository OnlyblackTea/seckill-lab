# Stage 1 验收证据（真机实跑）

> 工单：`db-stage-01` · `stages/stage-01-product-query-index/ticket.yaml`
> 验收命令：`mvn test -Dgroups=stage01`
> 执行环境：VM `10.32.10.102`（Ubuntu / linux-amd64）· OpenJDK `17.0.20` · Maven `3.8.7` ·
> Docker `29.1.3` · `mysql:8.0` + `redis:7`（`docker/docker-compose.yml`，端口 13306 / 16379）
> 跑法：干净骨架先跑一次（红），打上实现后再跑一次（绿）；每次跑前 `docker compose down -v` + `up -d --wait` 重置数据卷，
> 种子 `SELECT COUNT(*) FROM product` = **50003**。

本目录所有 `.log` / `.txt` 都是从那次真跑里原样取回的产物，没有手工编辑。

## 一、红 → 绿（判据，非推断）

| 阶段 | 命令 | 实测结果 | 产物 |
|---|---|---|---|
| 骨架（未改） | `mvn -B -Dgroups=stage01 test` | `exit=1` · **Tests run: 2, Failures: 1, Errors: 1** | `mvn-red.log` |
| 实现后 | `mvn -B -Dgroups=stage01 test` | `exit=0` · **Tests run: 2, Failures: 0, Errors: 0** · BUILD SUCCESS | `mvn-green-final.log` |
| `solutions` 分支参考解 | `mvn -B -Dgroups=stage01 test` | `exit=0` · **Tests run: 2, Failures: 0, Errors: 0** | `mvn-reffix.log` |

红得「在该红的地方」，两条都和 `ticket.yaml` 的 `grader.correctness.expect` 逐字对上：

```
ProductQueryIndexAcceptanceTest.searchReturnsCorrectOrderedResults:87 » Binding
    Invalid bound statement (not found): com.devteam.seckill.mapper.ProductMapper.searchProducts
ProductQueryIndexAcceptanceTest.explainUsesIndex --
    EXPLAIN 的 key 为 NULL，说明未命中任何索引（全表扫描）
```

即：① `searchProducts` 未绑定 → 正确性用例 **error**；② 没建索引 → EXPLAIN 用例 **failURE**。
不是「整份脚本全是注释」导致的那种假红。

## 二、EXPLAIN 前后对比（答辩三件套的第 3 件）

规范化语句（与验收测试 `CANONICAL_EXPLAIN_SQL` 逐字一致）：

```sql
EXPLAIN SELECT id, name, category_id, status, price, stock FROM product
 WHERE category_id = 10 AND status = 1 AND price BETWEEN 50 AND 200
 ORDER BY price ASC LIMIT 20;
```

**建索引前**（`explain-before.txt`，product 表只有 PRIMARY）：

| type | possible_keys | key | key_len | ref | rows | Extra |
|---|---|---|---|---|---|---|
| **ALL** | NULL | **NULL** | NULL | NULL | 50070 | **Using where; Using filesort** |

**建索引后**（`explain-after.txt`，`idx_product_search (category_id, status, price)`）：

| type | possible_keys | key | key_len | ref | rows | Extra |
|---|---|---|---|---|---|---|
| **range** | idx_product_search | **idx_product_search** | 14 | NULL | 1 | **Using index condition** |

- `type` 从 `ALL`（全表扫描）降为 `range`；`key` 从 `NULL` 变成 `idx_product_search`；
  估算扫描行数 `50070 → 1`；`Using filesort` 消失（排序被索引顺序吸收）。
- `key_len=14` = `category_id(8) + status(4) + price(2 可空... )` 的可用前缀长度，
  证明确实是**三列都参与**了这个索引，而不是只用到最左一列。

## 三、索引列序为什么是 `(category_id, status, price)`

（`explain-*.txt` 是隔离实验：删掉本索引、只留一个对照索引后重跑同一条 EXPLAIN。）

| 索引 | type | 估算 rows | Extra | 结论 |
|---|---|---|---|---|
| `(category_id, status, price)` | range | 1 | Using index condition | ✅ 等值列在前，范围+排序列在后 |
| `(category_id, price, status)` | range | 1 | Using index condition | 能命中，但 `status` 落在范围列之后，退化成回表过滤（区间也更宽） |
| `(price, category_id, status)` | range | **25037** | Using index condition | ❌ `price` 在最左时区间几乎是全表（5 万行的一半），等值条件用不上 |

可见「等值列在前、范围/排序列在后」不是教条：列序错了 `rows` 估算差 2.5 万倍。
覆盖索引可行性也验证过（`STATUS2.txt`）：把 `id,name,stock` 也并进索引后 Extra 变成 `Using index`，
说明**可以**做到不回表；但本工单只要求命中索引，5 万行规模下不值得为覆盖再撑宽索引。

## 四、与 `solutions` 分支参考解的一致性

`solutions`（`9081746`）与 `stage-01-product-query-index-sol`（`1487dbb`）两个 ref 的参考解，
在索引 DDL 与 searchProducts SQL 上**与本实现逐字相同**：

```sql
ALTER TABLE product ADD INDEX idx_product_search (category_id, status, price)
```

```xml
<select id="searchProducts" resultMap="productMap">
    SELECT <include refid="baseColumns"/>
    FROM product
    WHERE category_id = #{categoryId}
      AND status      = #{status}
      AND price BETWEEN #{minPrice} AND #{maxPrice}
    ORDER BY price ASC
    LIMIT #{limit}
</select>
```

参考解用同一套索引跑出同样的 `range / idx_product_search / rows=1 / Using index condition`（`mvn-reffix.log`，
`STATUS3.txt` 里有 diff：剩下差异只有注释文字与行尾，SQL 与 DDL 无差）。

## 五、产物清单

| 文件 | 是什么 |
|---|---|
| `STATUS.txt` | 主跑的机读汇总（环境、种子数、SHOW INDEX、三次 mvn 结果、全部 EXPLAIN） |
| `STATUS2.txt` | 隔离实验 + 种子分布 + 覆盖索引变体 |
| `STATUS3.txt` | 参考解交叉验证（ref、DDL、diff 行数、重跑结果） |
| `mvn-red.log` / `mvn-green-final.log` / `mvn-reffix.log` | 三次 Maven 真跑日志 |
| `explain-before.txt` / `explain-after.txt` | 规范化 EXPLAIN 前后对比 |
| `explain-wrong-order.txt` / `explain-price-first.txt` | 列序对照实验 |
| `verify.sh` / `verify2.sh` / `verify3.sh` | VM 上跑的三个驱动脚本（可复现） |

复现方式（在有 docker + JDK17 + Maven 的 linux 机器上）：

```bash
cd docker && docker compose down -v && docker compose up -d --wait   # 等 healthy + 种子灌完
cd .. && mvn -B -Dgroups=stage01 test                                # 红
# 换上下载的 ProductMapper.xml 与 V1__stage01_product_search_index.sql
mvn -B -Dgroups=stage01 test                                         # 绿
```
