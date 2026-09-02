package com.devteam.seckill.service;

import com.devteam.seckill.domain.Product;
import com.devteam.seckill.mapper.ProductMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/** 商品查询服务。Stage 0 已实现基础查询；search 委托给 Stage 1 待补的 Mapper 方法。 */
@Service
public class ProductService {

    private final ProductMapper productMapper;

    public ProductService(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

    public Product getProduct(Long id) {
        return productMapper.selectById(id);
    }

    public List<Product> listProducts() {
        return productMapper.selectAll();
    }

    /**
     * Stage 1：分类 + 上架状态 + 价格区间搜索，按价格升序，取前 limit 条。
     * 在 Mapper XML 的 searchProducts 被你实现之前，这里会抛 MyBatis BindingException。
     */
    public List<Product> search(Long categoryId, Integer status,
                                BigDecimal minPrice, BigDecimal maxPrice, int limit) {
        return productMapper.searchProducts(categoryId, status, minPrice, maxPrice, limit);
    }
}
