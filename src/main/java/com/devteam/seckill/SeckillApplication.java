package com.devteam.seckill;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 秒杀/电商下单 —— 后端服务入口。
 *
 * <p>这是一个"模拟大厂开发组维护的代码仓库"：你（新人后端）会陆续收到 TASKS/ 里的工单，
 * 亲手补全被标注 TODO 的 SQL / 事务 / 锁 / 缓存逻辑，并让对应阶段的验收测试由红变绿。</p>
 */
@SpringBootApplication
@MapperScan("com.devteam.seckill.mapper")
public class SeckillApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeckillApplication.class, args);
    }
}
