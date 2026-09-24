package com.devteam.seckill.mapper;

import com.devteam.seckill.domain.Product;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品数据访问接口。SQL 全部写在 resources/mapper/ProductMapper.xml 里（手写 SQL）。
 */
public interface ProductMapper {

    /** Stage 0：主键查询（已实现，冒烟测试依赖它）。 */
    Product selectById(@Param("id") Long id);

    /** Stage 0：查询全部（已实现，仅演示用；大表下会很慢——这正是 Stage 1 的动机）。 */
    List<Product> selectAll();

    /**
     * Stage 1（你来实现）：按分类 + 上架状态 + 价格区间搜索，并按价格升序，返回前 limit 条。
     * 对应 XML 里的 {@code <select id="searchProducts">} 目前是 TODO 占位（未绑定），
     * 调用它会抛 BindingException —— 这就是 Stage 1 验收测试"初始红"的原因之一。
     */
    List<Product> searchProducts(@Param("categoryId") Long categoryId,
                                 @Param("status") Integer status,
                                 @Param("minPrice") BigDecimal minPrice,
                                 @Param("maxPrice") BigDecimal maxPrice,
                                 @Param("limit") int limit);

    /** 按主键查询（等价于 selectById，供业务层按语义选用）。 */
    Product findById(@Param("id") Long id);

    /** 查询库存（Stage 2/3/4 都要用它观察"扣减是否真的发生/回滚了"）。 */
    Integer selectStock(@Param("id") Long id);

    /**
     * Stage 2/3（你来实现）：原子扣减一件库存。
     *
     * <p>SQL 形状（XML 里是 TODO 占位，未绑定）：
     * {@code UPDATE product SET stock = stock - 1 WHERE id = #{id} AND stock > 0}。</p>
     *
     * <p><b>为什么判断和扣减必须写在同一条 UPDATE 里</b>：InnoDB 执行 UPDATE 时对该行加排他锁，
     * 并在<b>最新已提交版本</b>上重新求值 WHERE（当前读）。所以并发请求会被行锁串行化，
     * 第二个请求看到的是扣减后的库存——这正是"不超卖"的根因。
     * 反例：先 {@code SELECT} 拿到 stock=10、在应用里判断 > 0、再 UPDATE，
     * 两个线程都可能在各自的读里看到 10，于是各扣一次（丢失更新 → 超卖）。</p>
     *
     * @return 受影响行数：1 = 扣减成功，0 = 库存不足（此时应视为售罄）
     */
    int decrementStock(@Param("id") Long id);

    /**
     * 骨架里现存的"先读后写"式扣减（<b>反面教材，Stage 3 要改掉它</b>）。
     *
     * <p>语义上它只负责"把库存写成指定值"，但这正是超卖的根源：
     * 调用方只能先 {@link #selectStock(Long)} 读、在应用里判断、再调本方法写回，
     * 两个并发请求之间就出现了"读-判断-写"窗口 —— 丢失更新。</p>
     *
     * @return 受影响行数（通常总是 1：它没有 {@code WHERE stock > 0} 这样的守卫条件）
     */
    int updateStock(@Param("id") Long id, @Param("stock") Integer stock);

    /** 乐观锁扣减（可选路线）：带上 version 做 CAS，返回 0 表示版本已被别人改过，需要重试。 */
    int decrementStockOptimistic(@Param("id") Long id, @Param("version") Integer version);
}
