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
     * 对应 XML 里的 <select id="searchProducts"> 目前是 TODO 占位（未绑定），
     * 调用它会抛 BindingException —— 这就是 Stage 1 验收测试"初始红"的原因之一。
     */
    List<Product> searchProducts(@Param("categoryId") Long categoryId,
                                 @Param("status") Integer status,
                                 @Param("minPrice") BigDecimal minPrice,
                                 @Param("maxPrice") BigDecimal maxPrice,
                                 @Param("limit") int limit);
}
