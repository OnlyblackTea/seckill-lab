package com.devteam.seckill.controller;

import com.devteam.seckill.domain.Product;
import com.devteam.seckill.domain.dto.ApiResponse;
import com.devteam.seckill.service.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/** 商品查询接口。 */
@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    /** GET /products/{id} —— Stage 0 冒烟测试依赖此接口。 */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Product>> getById(@PathVariable Long id) {
        Product p = productService.getProduct(id);
        if (p == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "商品不存在: " + id));
        }
        return ResponseEntity.ok(ApiResponse.ok(p));
    }

    /** GET /products —— 列出全部（大表下慢，Stage 1 用它对比优化前后）。 */
    @GetMapping
    public ResponseEntity<ApiResponse<List<Product>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(productService.listProducts()));
    }

    /**
     * GET /products/search —— Stage 1 的搜索接口。
     * 在你实现 Mapper 的 searchProducts 之前会返回 500（BindingException），这是预期的"未完成"状态。
     */
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<Product>>> search(
            @RequestParam Long categoryId,
            @RequestParam(defaultValue = "1") Integer status,
            @RequestParam BigDecimal minPrice,
            @RequestParam BigDecimal maxPrice,
            @RequestParam(defaultValue = "20") int limit) {
        List<Product> result = productService.search(categoryId, status, minPrice, maxPrice, limit);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
