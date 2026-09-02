package com.devteam.seckill.stage01;

import com.devteam.seckill.domain.Product;
import com.devteam.seckill.mapper.ProductMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Stage 1 验收测试：商品搜索查询的正确性 + 命中索引。
 *
 * <p>初始状态应为【红】，因为：
 * <ol>
 *   <li>{@code ProductMapper.xml} 里的 {@code searchProducts} 还是 TODO 占位（未绑定 SQL），
 *       调用会抛 BindingException → {@link #searchReturnsCorrectOrderedResults()} 失败；</li>
 *   <li>{@code V1__stage01_product_search_index.sql} 尚未创建任何索引，
 *       EXPLAIN 会走全表扫描 type=ALL、key=NULL → {@link #explainUsesIndex()} 失败。</li>
 * </ol>
 *
 * <p>你（新人后端）需要改的正好是这两个文件，让本测试变【绿】。
 * 运行：{@code mvn test -Dgroups=stage01}
 */
@Tag("stage01")
@SpringBootTest
@Sql(scripts = "classpath:db/migration/V1__stage01_product_search_index.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProductQueryIndexAcceptanceTest {

    /** 本测试专用的 category_id，避免污染种子数据，也方便 @BeforeEach 精确清理。 */
    private static final long TEST_CATEGORY = 999901L;

    /**
     * 规范化的 EXPLAIN 语句：其查询形状必须与你写在 ProductMapper.xml 里的 searchProducts 一致
     * （等值 category_id + status，范围 price，按 price 升序，LIMIT），
     * 这样为 searchProducts 建立的索引才能同时服务这条 EXPLAIN。
     */
    private static final String CANONICAL_EXPLAIN_SQL =
            "EXPLAIN SELECT id, name, category_id, status, price, stock FROM product "
                    + "WHERE category_id = 10 AND status = 1 AND price BETWEEN 50 AND 200 "
                    + "ORDER BY price ASC LIMIT 20";

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetFixtures() {
        jdbcTemplate.update("DELETE FROM product WHERE category_id = ?", TEST_CATEGORY);
        // 命中：status=1，price 在 [5,25]
        insert("fixture-5", 1, new BigDecimal("5.00"));
        insert("fixture-10", 1, new BigDecimal("10.00"));
        insert("fixture-20", 1, new BigDecimal("20.00"));
        // 不命中：price=30 超过 maxPrice=25
        insert("fixture-30", 1, new BigDecimal("30.00"));
        // 不命中：status=0（下架）
        insert("fixture-off", 0, new BigDecimal("15.00"));
    }

    private void insert(String name, int status, BigDecimal price) {
        jdbcTemplate.update(
                "INSERT INTO product (name, category_id, status, price, stock, version) VALUES (?,?,?,?,100,0)",
                name, TEST_CATEGORY, status, price);
    }

    @Test
    @Order(1)
    void searchReturnsCorrectOrderedResults() {
        List<Product> results = productMapper.searchProducts(
                TEST_CATEGORY, 1, new BigDecimal("5"), new BigDecimal("25"), 10);

        assertNotNull(results, "searchProducts 返回 null，检查 ProductMapper.xml 是否已实现该语句");
        assertEquals(3, results.size(),
                "应只命中 status=1 且 price∈[5,25] 的 3 条，实际=" + results.size());
        // 断言按 price 升序
        assertEquals(0, results.get(0).getPrice().compareTo(new BigDecimal("5.00")));
        assertEquals(0, results.get(1).getPrice().compareTo(new BigDecimal("10.00")));
        assertEquals(0, results.get(2).getPrice().compareTo(new BigDecimal("20.00")));
    }

    @Test
    @Order(2)
    void explainUsesIndex() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(CANONICAL_EXPLAIN_SQL);
        assertFalse(rows.isEmpty(), "EXPLAIN 未返回任何行");

        Map<String, Object> row = rows.get(0);
        Object type = row.get("type");
        Object key = row.get("key");
        System.out.println("==== Stage1 EXPLAIN ====");
        System.out.println("type          = " + type);
        System.out.println("key           = " + key);
        System.out.println("possible_keys = " + row.get("possible_keys"));
        System.out.println("rows          = " + row.get("rows"));
        System.out.println("Extra         = " + row.get("Extra"));
        System.out.println("========================");

        assertNotNull(key, "EXPLAIN 的 key 为 NULL，说明未命中任何索引（全表扫描）");
        assertFalse(String.valueOf(key).isEmpty(), "EXPLAIN 的 key 为空字符串，未命中索引");
        assertNotNull(type, "EXPLAIN 的 type 为 NULL");
        assertNotEquals("ALL", String.valueOf(type),
                "EXPLAIN type=ALL 表示全表扫描，请为 searchProducts 的查询条件建立合适索引");
    }
}
