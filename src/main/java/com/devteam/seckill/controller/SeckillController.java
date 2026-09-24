package com.devteam.seckill.controller;

import com.devteam.seckill.domain.dto.ApiResponse;
import com.devteam.seckill.domain.dto.SeckillResult;
import com.devteam.seckill.service.SeckillService;
import com.devteam.seckill.service.SoldOutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 秒杀下单接口。
 *
 * <p>HTTP 语义（见 docs/架构与约定.md 第 5 节）：
 * <ul>
 *   <li>200：下单成功（body 里带订单 id 与剩余库存）</li>
 *   <li>409：重复下单（同一用户对同一商品只能下一单，唯一键 uk_user_product 兜底）</li>
 *   <li>410：售罄</li>
 *   <li>404：商品不存在</li>
 *   <li>501：功能未实现（Stage 2/3 未做时，service 抛 UnsupportedOperationException）</li>
 * </ul>
 * 并发模拟器（sim）正是靠这些状态码来数"成功多少 / 售罄多少"的。</p>
 */
@RestController
@RequestMapping("/seckill")
public class SeckillController {

    private final SeckillService seckillService;

    public SeckillController(SeckillService seckillService) {
        this.seckillService = seckillService;
    }

    /** POST /seckill?userId=&productId= */
    @PostMapping
    public ResponseEntity<ApiResponse<SeckillResult>> seckill(
            @RequestParam Long userId,
            @RequestParam Long productId) {
        try {
            SeckillResult result = seckillService.seckill(userId, productId);
            if (result.success()) {
                return ResponseEntity.ok(ApiResponse.ok(result));
            }
            String reason = String.valueOf(result.reason());
            if (reason.startsWith("DUPLICATE")) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiResponse.error(409, reason));
            }
            if (reason.startsWith("SOLD_OUT")) {
                return ResponseEntity.status(HttpStatus.GONE)
                        .body(ApiResponse.error(410, reason));
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(400, reason));
        } catch (SoldOutException e) {
            // 兜底：万一实现里用异常表达售罄，也要映射成 410 而不是 500
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(ApiResponse.error(410, e.getMessage()));
        } catch (UnsupportedOperationException e) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body(ApiResponse.error(501, e.getMessage()));
        }
    }
}
