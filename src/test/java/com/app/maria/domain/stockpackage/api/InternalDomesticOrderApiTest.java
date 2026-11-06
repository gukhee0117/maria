package com.app.maria.domain.stockpackage.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.domestic.type.DomesticOrderSide;
import com.app.maria.domain.stockpackage.dto.response.GeneratorOrderResponseDTO;
import com.app.maria.domain.stockpackage.service.GeneratorOrderService;
import com.app.maria.global.config.InternalSecurityConfig;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InternalDomesticOrderApi.class)
@Import(InternalSecurityConfig.class)
@TestPropertySource(properties = "custom.internal.secret=test-internal-secret")
class InternalDomesticOrderApiTest {
    private static final String PATH = "/api/internal/domestic-orders";
    private static final String BODY =
            "{\"ciHash\":\""
                    + "a".repeat(64)
                    + "\",\"side\":\"BUY\",\"idempotencyKey\":\"GEN-1-1\"}";

    @Autowired MockMvc mockMvc;
    @MockitoBean GeneratorOrderService service;

    @Test
    void 헤더가_없으면_401이고_서비스를_부르지_않는다() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void 비밀이_다르면_401이고_서비스를_부르지_않는다() throws Exception {
        mockMvc.perform(
                        post(PATH)
                                .header("X-Internal-Secret", "wrong")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void 비밀이_맞으면_처리하고_결과를_data로_준다() throws Exception {
        when(service.order(anyString(), any(), anyString()))
                .thenReturn(
                        new GeneratorOrderResponseDTO(
                                "EXECUTED",
                                null,
                                11L,
                                "005930",
                                BigDecimal.TEN,
                                new BigDecimal("100000"),
                                new BigDecimal("-1000040"),
                                LocalDateTime.of(2026, 11, 18, 10, 0)));
        mockMvc.perform(
                        post(PATH)
                                .header("X-Internal-Secret", "test-internal-secret")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXECUTED"))
                .andExpect(jsonPath("$.data.domesticOrderId").value(11))
                .andExpect(jsonPath("$.data.ticker").value("005930"))
                .andExpect(jsonPath("$.data.qty").value(10))
                .andExpect(jsonPath("$.data.price").value(100000));
        verify(service).order("a".repeat(64), DomesticOrderSide.BUY, "GEN-1-1");
    }

    @Test
    void 잘못된_본문은_400() throws Exception {
        mockMvc.perform(
                        post(PATH)
                                .header("X-Internal-Secret", "test-internal-secret")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"ciHash\":\"short\",\"side\":\"BUY\",\"idempotencyKey\":\"k\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
