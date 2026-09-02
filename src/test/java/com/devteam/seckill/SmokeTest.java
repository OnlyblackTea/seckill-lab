package com.devteam.seckill;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 0 冒烟测试：骨架健康 + 基础查询可用。
 * 前置：docker compose up -d 且 MySQL healthy（product 表已有 id=1 的种子）。
 * 运行：mvn test -Dgroups=stage00
 * 预期：绿（骨架交付即应通过）。
 */
@Tag("stage00")
@SpringBootTest
@AutoConfigureMockMvc
class SmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads() {
        // Spring 上下文能启动，说明 DataSource / MyBatis / Web 层装配正常
    }

    @Test
    void getProductById_returns200AndCorrectBody() throws Exception {
        mockMvc.perform(get("/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.name").isNotEmpty());
    }

    @Test
    void getMissingProduct_returns404() throws Exception {
        mockMvc.perform(get("/products/999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
