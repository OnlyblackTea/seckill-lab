package com.devteam.seckill.controller;

import com.devteam.seckill.domain.SeckillOrder;
import com.devteam.seckill.domain.dto.ApiResponse;
import com.devteam.seckill.service.SeckillService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 秒杀下单接口。Stage 0 返回 501（未实现）；并发模拟器(sim)会打这个接口。 */
@RestController
@RequestMapping("/seckill")
public class SeckillController {

    private final SeckillService seckillService;

    public SeckillController(SeckillService seckillService) {
        this.seckillService = seckillService;
    }

    /** POST /seckill?userId=&productId= */
    @PostMapping
    public ResponseEntity<ApiResponse<SeckillOrder>> seckill(
            @RequestParam Long userId,
            @RequestParam Long productId) {
        try {
            SeckillOrder order = seckillService.seckill(userId, productId);
            return ResponseEntity.ok(ApiResponse.ok(order));
        } catch (UnsupportedOperationException e) {
            return ResponseEntity.status(501).body(ApiResponse.error(501, e.getMessage()));
        }
    }
}
