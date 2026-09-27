package com.devteam.seckill.stage02;

import com.devteam.seckill.domain.Product;
import com.devteam.seckill.domain.dto.SeckillResult;
import com.devteam.seckill.mapper.OrderMapper;
import com.devteam.seckill.mapper.ProductMapper;
import com.devteam.seckill.service.SeckillService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 2 验收测试：下单事务的原子性（"扣库存 + 建订单"必须同生共死）。
 *
 * <h2>怎么把"没有事务"这件事变成可断言的红</h2>
 * <p>关键在于<b>故障注入</b>：表 {@code seckill_order} 上有唯一键
 * {@code uk_user_product (user_id, product_id)}——如果<b>扣库存发生在建订单之前</b>，
 * 那么"同一用户对同一商品下第二单"就会在 INSERT 那一步炸掉。此刻：
 * <ul>
 *   <li>两个动作<b>不在同一事务</b>（骨架）：库存已经 -1 并已提交，订单却没插进去
 *       → 库存变少、订单不见 → 用例失败。这就是线上"库存扣了订单没建"的真身；</li>
 *   <li>两个动作<b>在同一事务</b>（正确实现）：异常让整个事务回滚，库存回到原值 → 通过。</li>
 * </ul>
 * 判据因此完全落在可观测事实上，不依赖任何人为打桩。</p>
 *
 * <p>运行：{@code mvn test -Dgroups=stage02} —— 骨架为红，实现后为绿。</p>
 */
@Tag("stage02")
@SpringBootTest
class SeckillTransactionAcceptanceTest {

    /** 专用 fixture 商品 id（避开种子数据 id=1..3，避免与其它 stage 互相污染）。 */
    private static final long PRODUCT_ID = 9_002_001L;
    private static final long USER_A = 9_002_101L;
    private static final long USER_B = 9_002_102L;
    /** 回滚用例用到的"诱饵商品"：预置一条占位订单，用来逼出唯一键冲突。 */
    private static final long DECOY_PRODUCT_ID = 9_002_002L;
    private static final long USER_DUPLICATE = 9_002_103L;

    @Autowired
    private SeckillService seckillService;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        insertProduct(PRODUCT_ID, "stage02-fixture", 5);
        insertProduct(DECOY_PRODUCT_ID, "stage02-decoy", 1);
        jdbcTemplate.update("INSERT INTO `user` (id, username, password_hash) VALUES (?,?,?)", USER_A, "stage02-a", "x");
        jdbcTemplate.update("INSERT INTO `user` (id, username, password_hash) VALUES (?,?,?)", USER_B, "stage02-b", "x");
        jdbcTemplate.update("INSERT INTO `user` (id, username, password_hash) VALUES (?,?,?)",
                USER_DUPLICATE, "stage02-dup", "x");
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    private void insertProduct(long id, String name, int stock) {
        jdbcTemplate.update("INSERT INTO product (id, name, category_id, status, price, stock, version) "
                + "VALUES (?,?,?,?,?,?,?)", id, name, 9002L, 1, new BigDecimal("100.00"), stock, 0);
    }

    private void cleanFixtures() {
        jdbcTemplate.update("DELETE FROM seckill_order WHERE product_id IN (?,?)", PRODUCT_ID, DECOY_PRODUCT_ID);
        jdbcTemplate.update("DELETE FROM product WHERE id IN (?,?)", PRODUCT_ID, DECOY_PRODUCT_ID);
        jdbcTemplate.update("DELETE FROM `user` WHERE id IN (?,?,?)", USER_A, USER_B, USER_DUPLICATE);
    }

    /**
     * 用例 1：正常下单 —— 返回订单 id、库存 -1、订单数 +1。
     *
     * <p>骨架红在这里：{@code seckill()} 还没实现（抛 {@code UnsupportedOperationException}）。</p>
     */
    @Test
    void successfulSeckill_deductsStockAndCreatesOrder() {
        assertEquals(5, productMapper.selectStock(PRODUCT_ID), "前置：fixture 库存应为 5");

        SeckillResult result = seckillService.seckill(USER_A, PRODUCT_ID);

        assertTrue(result.success(), "正常下单应当成功，实际 reason=" + result.reason());
        assertNotNull(result.orderId(), "必须返回订单 id —— 检查 insertOrder 是否配了 useGeneratedKeys/keyProperty");

        Product after = productMapper.findById(PRODUCT_ID);
        assertEquals(4, after.getStock(), "库存应当恰好 -1");
        assertEquals(4, result.remainingStock(), "返回的剩余库存应与库里一致");
        assertEquals(1, orderMapper.countByProduct(PRODUCT_ID), "应当恰好有一条订单");
        assertNotNull(orderMapper.findById(result.orderId()), "按返回的 id 应当能查到订单");
    }

    /**
     * 用例 2（核心）：<b>回滚证明</b> —— 建订单失败时，已扣的库存必须被回滚。
     *
     * <p>做法：先用一个"已存在订单"的用户去下一单（唯一键 {@code uk_user_product} 会让 INSERT 失败）。
     * 正确实现下整个事务回滚 → 库存必须原封不动；骨架（无事务）下库存会被扣掉一件却查不到对应订单。</p>
     */
    @Test
    void rollback_onOrderInsertFailure_restoresStock() {
        // 诱饵：先给 USER_DUPLICATE 在 PRODUCT_ID 上预置一条订单（模拟"这单已经存在"）
        jdbcTemplate.update("INSERT INTO seckill_order (user_id, product_id, quantity, amount, status) "
                + "VALUES (?,?,?,?,?)", USER_DUPLICATE, PRODUCT_ID, 1, new BigDecimal("100.00"), 0);

        Integer stockBefore = productMapper.selectStock(PRODUCT_ID);
        int ordersBefore = orderMapper.countByProduct(PRODUCT_ID);
        assertEquals(5, stockBefore, "前置：库存应为 5");

        // 同一 user + 同一 product 再下一单 → INSERT 撞唯一键
        boolean failed;
        try {
            SeckillResult r = seckillService.seckill(USER_DUPLICATE, PRODUCT_ID);
            failed = !r.success();
        } catch (Throwable t) {
            failed = true;
        }
        assertTrue(failed, "重复下单必须失败（唯一键 uk_user_product 兜底）");

        assertEquals(stockBefore, productMapper.selectStock(PRODUCT_ID),
                "建订单失败后库存必须回到 " + stockBefore + " —— 若少了一件，说明「扣库存」没有和「建订单」"
                        + "在同一事务里，没有回滚（线上表现就是：库存扣了、订单没建）");
        assertEquals(ordersBefore, orderMapper.countByProduct(PRODUCT_ID), "订单数不应变化");
    }

    /** 用例 3：库存不足时不能扣成负数，也不留下半成品订单。 */
    @Test
    void soldOut_doesNotGoNegative_andLeavesNoOrder() {
        for (int i = 0; i < 5; i++) {
            SeckillResult r = seckillService.seckill(USER_B + i, PRODUCT_ID);
            assertTrue(r.success(), "第 " + (i + 1) + " 单应当成功，实际 reason=" + r.reason());
        }
        assertEquals(0, productMapper.selectStock(PRODUCT_ID), "卖完 5 件后库存应为 0");

        SeckillResult soldOut = seckillService.seckill(USER_B + 99, PRODUCT_ID);
        assertTrue(!soldOut.success(), "库存为 0 时应当返回失败（售罄），而不是扣成负数");
        assertNull(soldOut.orderId(), "售罄时不应返回订单 id");
        assertTrue(!"DUPLICATE".equals(String.valueOf(soldOut.reason()).split(":")[0]),
                "售罄不应被误判为重复下单，实际 reason=" + soldOut.reason());

        assertEquals(0, productMapper.selectStock(PRODUCT_ID), "库存不能变成负数");
        assertEquals(5, orderMapper.countByProduct(PRODUCT_ID), "订单数应当恰好等于初始库存 5");
    }
}
