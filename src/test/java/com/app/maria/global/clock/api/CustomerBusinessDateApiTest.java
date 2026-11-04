package com.app.maria.global.clock.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.CustomerSecurityConfig;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CustomerBusinessDateApi.class)
@Import(CustomerSecurityConfig.class)
class CustomerBusinessDateApiTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private BusinessClockService businessClockService;

    @Test
    @DisplayName("로그인 없이도 업무일(날짜만)을 돌려준다")
    void unauthenticated_returnsBusinessDate() throws Exception {
        when(businessClockService.now()).thenReturn(LocalDateTime.of(2026, 11, 18, 15, 30));

        mockMvc.perform(get("/api/customer/business-date"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("2026-11-18"));
    }

    @Test
    @DisplayName("관리자가 업무시계를 옮기면 그 날짜가 그대로 나온다")
    void followsBusinessClock() throws Exception {
        when(businessClockService.now()).thenReturn(LocalDateTime.of(2027, 4, 1, 0, 0));

        mockMvc.perform(get("/api/customer/business-date"))
                .andExpect(jsonPath("$.data").value("2027-04-01"));
    }

    @Test
    @DisplayName("읽기 전용이다: POST는 허용되지 않는다")
    void postIsNotAllowed() throws Exception {
        mockMvc.perform(post("/api/customer/business-date")).andExpect(status().is4xxClientError());
    }
}
