package com.devteam.seckill.sim;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 并发秒杀请求模拟器（仿真真实前端高并发打接口）。
 *
 * <p>用 100 个线程 + CountDownLatch 起跑门，尽可能在同一瞬间向
 * {@code POST /seckill?userId=&productId=1} 发起请求，用来复现/验证超卖现象。
 *
 * <p>Stage 0/1 阶段下单逻辑尚未实现，接口返回 501，本模拟器只会统计到 notImplemented，
 * 不会断言业务正确性——它的真正战场是 Stage 3（超卖与锁）。
 *
 * <p>运行（默认 Surefire 排除了 sim 分组，需要显式打开并清空排除项）：
 * <pre>
 *   mvn test -Dgroups=sim -Dtest.excludedGroups=
 * </pre>
 */
@Tag("sim")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeckillLoadSimulator {

    private static final int THREADS = 100;
    private static final long PRODUCT_ID = 1L;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void fireConcurrentSeckillRequests() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger notImplemented = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        for (int i = 1; i <= THREADS; i++) {
            final long userId = i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    String url = "/seckill?userId=" + userId + "&productId=" + PRODUCT_ID;
                    ResponseEntity<String> resp = restTemplate.postForEntity(url, null, String.class);
                    int status = resp.getStatusCode().value();
                    if (status == 200) {
                        ok.incrementAndGet();
                    } else if (status == 501) {
                        notImplemented.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                    }
                } catch (Exception e) {
                    other.incrementAndGet();
                }
            });
        }

        // 所有线程就绪后同时放行，制造瞬时并发
        startGate.countDown();
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);

        System.out.println("==== 并发秒杀模拟结果 ====");
        System.out.println("并发线程数        = " + THREADS);
        System.out.println("成功下单 (200)    = " + ok.get());
        System.out.println("未实现 (501)      = " + notImplemented.get());
        System.out.println("其它/异常         = " + other.get());
        System.out.println("==========================");

        assertEquals(THREADS, ok.get() + notImplemented.get() + other.get(),
                "所有请求都应被统计到，总数应等于并发线程数");
    }
}
