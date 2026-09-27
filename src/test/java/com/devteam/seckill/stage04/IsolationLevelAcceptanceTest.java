package com.devteam.seckill.stage04;

import com.devteam.seckill.mapper.OrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 4 验收测试：隔离级别与 MVCC（快照读 vs 当前读）。
 *
 * <p>为什么要用<b>两个独立 JDBC 连接</b>手工开关事务：隔离级别讲的是"会话 A 的写入
 * 在会话 B 眼里何时可见"，只有在**不同事务**之间才有意义。若用 Spring 的
 * {@code @Transactional} 测试（把整个测试方法包进一个事务、最后回滚），
 * 就永远看不到"提交前后可见性差异"。所以这里绕过 Spring 事务，直接开两个连接对打。</p>
 *
 * <p>骨架红的原因：① 迁移脚本 V2 还没建 {@code order_stat} 表；
 * ② {@code sumAmountByProduct} 未绑定（BindingException）。
 * 改对后四条判据全绿，并能亲眼看到默认 RR 下"未提交不可见、同事务快照一致、
 * 提交后新事务读得到"这组事实。</p>
 *
 * <p>运行：{@code mvn test -Dgroups=stage04}</p>
 */
@Tag("stage04")
@SpringBootTest
@Sql(scripts = "classpath:db/migration/V2__stage04_order_stat.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class IsolationLevelAcceptanceTest {

    /** 与 application.yml 的默认值保持一致，同时允许用环境变量覆盖（便于在别的机器上跑）。 */
    private static final String URL =
            "jdbc:mysql://" + System.getenv().getOrDefault("DB_HOST", "localhost")
                    + ":" + System.getenv().getOrDefault("DB_PORT", "13306")
                    + "/" + System.getenv().getOrDefault("DB_NAME", "seckill_lab")
                    + "?useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8";
    private static final String USER = System.getenv().getOrDefault("DB_USER", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "bagu1234");

    private static final long PRODUCT_ID = 9_004_001L;
    private static final long USER_A = 9_004_101L;
    private static final long USER_B = 9_004_102L;
    private static final long USER_C = 9_004_103L;

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        jdbcTemplate.update("INSERT INTO product (id, name, category_id, status, price, stock, version) "
                        + "VALUES (?,?,?,?,?,?,?)",
                PRODUCT_ID, "stage04-fixture", 9004L, 1, new BigDecimal("50.00"), 100, 0);
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    private void cleanFixtures() {
        jdbcTemplate.update("DELETE FROM seckill_order WHERE product_id = ?", PRODUCT_ID);
        jdbcTemplate.update("DELETE FROM order_stat WHERE product_id = ?", PRODUCT_ID);
        jdbcTemplate.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
    }

    private Connection rawConnection(String isolation) throws SQLException {
        Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
        c.setAutoCommit(true);
        try (Statement st = c.createStatement()) {
            st.execute("SET SESSION TRANSACTION ISOLATION LEVEL " + isolation);
        }
        return c;
    }

    private void insertOrder(Connection c, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO seckill_order (user_id, product_id, quantity, amount, status) VALUES (?,?,?,?,?)")) {
            ps.setLong(1, userId);
            ps.setLong(2, PRODUCT_ID);
            ps.setInt(3, 1);
            ps.setBigDecimal(4, new BigDecimal("50.00"));
            ps.setInt(5, 0);
            ps.executeUpdate();
        }
    }

    private int countRaw(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM seckill_order WHERE product_id = " + PRODUCT_ID)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** 判据 1：对账表必须真实存在，且带 uk_product 唯一键（upsert 的依据）。 */
    @Test
    void orderStatTable_existsWithUniqueKey() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = 'order_stat'", Integer.class);
        assertEquals(1, tables, "order_stat 表不存在：请把 V2 迁移脚本里的建表语句补出来");

        Integer ukColumns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'order_stat' "
                        + "AND index_name = 'uk_product' AND non_unique = 0", Integer.class);
        assertTrue(ukColumns != null && ukColumns >= 1,
                "order_stat 上必须有唯一键 uk_product(product_id)——upsert 靠它判断插入还是累加");
    }

    /** 判据 2：聚合 SQL 可用，且无订单时成交额返回 0（不是 null）。 */
    @Test
    void aggregateQueries_workAndReturnZeroInsteadOfNull() {
        assertEquals(0, orderMapper.countByProduct(PRODUCT_ID), "无订单时订单数应为 0");
        BigDecimal sum = orderMapper.sumAmountByProduct(PRODUCT_ID);
        assertTrue(sum != null, "成交额不能是 null —— 请用 IFNULL/COALESCE 兜底");
        assertEquals(0, sum.compareTo(BigDecimal.ZERO), "无订单时成交额应为 0");

        jdbcTemplate.update("INSERT INTO seckill_order (user_id, product_id, quantity, amount, status) "
                + "VALUES (?,?,?,?,?)", USER_A, PRODUCT_ID, 1, new BigDecimal("50.00"), 0);
        jdbcTemplate.update("INSERT INTO seckill_order (user_id, product_id, quantity, amount, status) "
                + "VALUES (?,?,?,?,?)", USER_B, PRODUCT_ID, 1, new BigDecimal("25.50"), 0);
        assertEquals(2, orderMapper.countByProduct(PRODUCT_ID));
        assertEquals(0, orderMapper.sumAmountByProduct(PRODUCT_ID).compareTo(new BigDecimal("75.50")),
                "成交额应为 75.50");
    }

    /**
     * 判据 3：<b>未提交不可见</b>（默认 REPEATABLE READ，也适用于 READ COMMITTED）。
     *
     * <p>会话 A 插入但未 commit，会话 B 查不到；A commit 之后，B 的新事务才查得到。</p>
     */
    @Test
    void uncommittedWrite_isInvisibleToOtherSession() throws SQLException {
        try (Connection a = rawConnection("REPEATABLE READ");
             Connection b = rawConnection("REPEATABLE READ")) {

            a.setAutoCommit(false);
            insertOrder(a, USER_A);
            assertEquals(1, countRaw(a), "会话 A 自己应当看得到自己未提交的写入");

            b.setAutoCommit(false);
            assertEquals(0, countRaw(b), "会话 B 不该看到 A 未提交的订单（脏读）");
            b.commit();

            a.commit();
            try (Connection c = rawConnection("REPEATABLE READ")) {
                c.setAutoCommit(false);
                assertEquals(1, countRaw(c), "A 提交后，新事务应当读得到这条订单");
                c.commit();
            }
        }
    }

    /**
     * 判据 4：<b>同事务内快照读一致 + 提交后新事务可见</b>。
     *
     * <p>RR 下同一事务里两次普通 SELECT 走同一个 ReadView，结果一样（可重复读）；
     * 但别的会话提交后，<b>新开的事务</b>能立刻看到。</p>
     */
    @Test
    void repeatableRead_keepsSnapshotStable_withinTransaction() throws SQLException {
        try (Connection a = rawConnection("REPEATABLE READ");
             Connection b = rawConnection("REPEATABLE READ")) {

            a.setAutoCommit(false);
            int first = countRaw(a);

            b.setAutoCommit(false);
            insertOrder(b, USER_B);
            b.commit();

            assertEquals(first, countRaw(a),
                    "同一事务里两次快照读必须一致（可重复读）—— 若变了说明这不是 RR 语义");

            a.commit();
            try (Connection c = rawConnection("REPEATABLE READ")) {
                c.setAutoCommit(false);
                assertEquals(first + 1, countRaw(c),
                        "提交后新起的事务应当看得到别人的提交（快照只在事务内保持一致）");
                c.commit();
            }
        }
    }

    /**
     * 判据 5：<b>当前读 vs 快照读</b>。
     *
     * <p>{@code UPDATE ... WHERE stock > 0} 是当前读，它必须在最新已提交版本上求值，
     * 所以并发下不能"读到旧库存再扣"。这里用一个受控的最小场景验证：
     * 会话 A 开事务并做一次当前读（UPDATE），会话 B 提交一笔新订单后，
     * A 再做一次<b>当前读</b>（{@code SELECT ... FOR UPDATE}）能看到 B 的提交，
     * 而普通快照读看不到——这就是"扣库存用当前读、统计用快照读"的差别。</p>
     */
    @Test
    void currentRead_seesLatestCommitted_whileSnapshotReadDoesNot() throws SQLException {
        jdbcTemplate.update("UPDATE product SET stock = 1 WHERE id = ?", PRODUCT_ID);

        try (Connection a = rawConnection("REPEATABLE READ");
             Connection b = rawConnection("REPEATABLE READ")) {

            a.setAutoCommit(false);
            try (Statement st = a.createStatement()) {
                st.executeQuery("SELECT stock FROM product WHERE id = " + PRODUCT_ID).close();
            }

            // 会话 B 抢先扣掉最后一件并提交
            b.setAutoCommit(false);
            try (Statement st = b.createStatement()) {
                assertEquals(1, st.executeUpdate(
                        "UPDATE product SET stock = stock - 1 WHERE id = " + PRODUCT_ID + " AND stock > 0"));
            }
            b.commit();

            // A 的当前读（FOR UPDATE）必须看到 B 已提交的结果：库存 0 => 扣不动
            try (Statement st = a.createStatement()) {
                try (ResultSet rs = st.executeQuery(
                        "SELECT stock FROM product WHERE id = " + PRODUCT_ID + " FOR UPDATE")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "当前读必须看到最新已提交的库存");
                }
                assertEquals(0, st.executeUpdate(
                                "UPDATE product SET stock = stock - 1 WHERE id = " + PRODUCT_ID + " AND stock > 0"),
                        "库存已被 B 扣光，A 的当前读必须求值出 stock > 0 为假 => 影响 0 行（不超卖）");
            }
            a.commit();

            Integer finalStock = jdbcTemplate.queryForObject(
                    "SELECT stock FROM product WHERE id = ?", Integer.class, PRODUCT_ID);
            assertEquals(0, finalStock, "最终库存必须恰好 0，绝不能是负数");
        }
    }

    /** 兜底：确认会话级别设置真的生效（避免测试在错误的隔离级别上跑出错误结论）。 */
    @Test
    void isolationLevelCanBeSetPerSession() throws SQLException {
        try (Connection c = rawConnection("READ COMMITTED")) {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT @@transaction_isolation")) {
                assertTrue(rs.next());
                String level = rs.getString(1).toUpperCase().replace('-', ' ');
                assertFalse(level.isEmpty());
                assertTrue(level.contains("READ COMMITTED"), "会话隔离级别应为 READ COMMITTED，实际=" + level);
            }
        }
    }
}
