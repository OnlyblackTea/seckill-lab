# Stage 1 · 工单：商品搜索查询慢

> 状态：🟥 已派发（验收测试初始为红）· 考点：索引 / 最左前缀 / 覆盖索引 / EXPLAIN / 索引失效

## 一、线上问题（工单背景）

**来自：商品组 · 优先级：P1**

> 运营反馈：商品列表页的「按分类 + 上架状态 + 价格区间」搜索越来越慢。
> `product` 表现在有 5 万多行，每次搜索都卡。DBA 看了一眼说：**「你们这查询走的是全表扫描，
> 一个合适的索引都没有。」** 请你排查并优化，用 EXPLAIN 证明索引真的被用上了。

这不是编的——本项目的 `product` 表**故意只建了主键，没有任何二级索引**，
所以带 `WHERE category_id / status / price` 的查询必然全表扫描。

## 二、你要做什么

正好改**两个文件**：

### 文件 1：实现搜索 SQL

`src/main/resources/mapper/ProductMapper.xml` 里的 `searchProducts` 现在是**注释掉的占位**，
所以接口方法未绑定，调用会抛 `BindingException`。取消注释并补全，语义要求：

```
WHERE category_id = #{categoryId}
  AND status      = #{status}
  AND price BETWEEN #{minPrice} AND #{maxPrice}
ORDER BY price ASC
LIMIT #{limit}
```

返回列用 `resultMap="productMap"`（至少含 id,name,category_id,status,price,stock）。
Mapper 接口 `ProductMapper.searchProducts(...)` 已经写好，参数名对得上，你只需写 XML。

### 文件 2：加合适的索引

`src/main/resources/db/migration/V1__stage01_product_search_index.sql` 现在除了末尾一条
无副作用的 `SELECT 1;` 占位（防止 `@Sql` 因「整份脚本全是注释 = 空脚本」而报错）外全是注释，
**不建任何索引**。你要把里面的「幂等加索引」模板取消注释，并把 `(col_a, col_b, col_c)`
换成你决定的真实列。**索引名必须叫 `idx_product_search`**（测试按这个名字判断幂等）。

## 三、关键思考（不给答案，自己想）

设计联合索引的列顺序时，问自己：

1. WHERE 里哪些条件是**等值**（`=`），哪些是**范围**（`BETWEEN`）？
2. `ORDER BY` 用的是哪一列？
3. **最左前缀原则**下，等值列、范围列、排序列在联合索引里应该怎么排？
   （提示：范围列之后的索引列会失效——想想这对 `ORDER BY` 意味着什么。）
4. 能不能做成**覆盖索引**，让查询完全不回表？`Extra` 里看到 `Using index` 就是。

想清楚这几点，你的索引顺序自然就定了。这是面试最爱问的「联合索引怎么设计」。

## 四、验收标准（测试怎么判你过没过）

跑：

```bash
mvn test -Dgroups=stage01
```

验收测试 `ProductQueryIndexAcceptanceTest` 会：

1. **正确性**：插入 5 条 fixture（category=999901），其中 3 条应命中
   （status=1 且 price∈[5,25]），断言返回恰好 3 条且按 price 升序（5.00 → 10.00 → 20.00）。
   - 排除项：price=30 超上限、status=0 下架。
2. **命中索引**：对一条规范化 EXPLAIN 语句断言 `type != ALL` 且 `key` 非空。

**初始为红的两个原因**（都对应你上面的两个文件）：
- `searchProducts` 未绑定 → BindingException → 正确性测试失败；
- 没建索引 → EXPLAIN `type=ALL, key=NULL` → 索引测试失败。

两个断言都过 = 工单完成 = 测试变绿。

## 五、Hints（卡住再看，别直接看答案）

- **H1**：`@Sql` 会在每次测试方法前自动执行 V1 脚本，所以你改完 SQL 文件直接跑测试即可，
  不用手动去数据库建索引。脚本是幂等的，重复跑不会报 `Duplicate key name`。
- **H2**：EXPLAIN 断言用的规范化语句查询形状**和你写的 searchProducts 一致**
  （等值 category_id + status，范围 price，按 price 升序，LIMIT）。所以你为 searchProducts
  建的索引，一定能服务这条 EXPLAIN——前提是列顺序设计对了。
- **H3**：想手动看 EXPLAIN？
  ```bash
  docker compose -f docker/docker-compose.yml exec mysql mysql -uroot -pbagu1234 seckill_lab \
    -e "EXPLAIN SELECT id,name,category_id,status,price,stock FROM product \
        WHERE category_id=10 AND status=1 AND price BETWEEN 50 AND 200 ORDER BY price ASC LIMIT 20;"
  ```
  建索引前后各跑一次，对比 `type` / `key` / `rows` / `Extra` 的变化，这就是你 review 时要给我看的证据。
- **H4**：如果 EXPLAIN 里 `key=idx_product_search` 但 `Extra` 出现 `Using filesort`，
  说明你的列顺序没能同时满足过滤和排序——回到「关键思考」第 3 点重排列顺序。

## 六、做完之后

把以下三样贴给导师 review：
1. 你改后的 `searchProducts` SQL；
2. 你选的索引列顺序 + 一句话解释为什么这么排；
3. 建索引**前后**两次 EXPLAIN 的输出对比。

review 通过 → 导师带你过一遍「索引 & 执行计划」八股（见 `docs/数据库八股地图.md` Stage 1）
→ 解锁 Stage 2（下单事务）。

> 参考解在 `solutions` 分支，但**强烈建议先自己做完再看**。你面试时要能讲出自己的思路，
> 而不是背一个标准答案。
