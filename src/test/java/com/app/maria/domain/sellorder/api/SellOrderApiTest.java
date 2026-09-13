package com.app.maria.domain.sellorder.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.sellorder.dto.SellOrderHistoryDTO;
import com.app.maria.domain.sellorder.dto.SellOrderSummaryDTO;
import com.app.maria.domain.sellorder.dto.request.SellOrderRequestDTO;
import com.app.maria.domain.sellorder.dto.response.SellOrderResponseDTO;
import com.app.maria.domain.sellorder.exception.SellOrderException;
import com.app.maria.domain.sellorder.exception.SellOrderNotFoundException;
import com.app.maria.domain.sellorder.service.SellOrderService;
import com.app.maria.domain.sellorder.type.SellOrderStatus;
import com.app.maria.global.config.SecurityConfig;
import com.app.maria.global.exception.UnsupportedExchangeException;
import com.app.maria.global.jwt.JwtTokenProvider;
import com.app.maria.global.response.PageResponseDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SellOrderApi.class)
@Import(SecurityConfig.class)
class SellOrderApiTest {

    @Autowired MockMvc mockMvc;

    @Autowired ObjectMapper objectMapper;

    @MockitoBean SellOrderService sellOrderService;

    @MockitoBean JwtTokenProvider jwtTokenProvider;

    private SellOrderRequestDTO.SellOrderRequestDTOBuilder validRequestBuilder() {
        return SellOrderRequestDTO.builder()
                .accountId(1L)
                .foreignProductId(1L)
                .sellQty(new BigDecimal("10"));
    }

    @Test
    @DisplayName("한도 이내로 체결되면 201과 EXECUTED 상태, 체결 메시지를 반환한다")
    @WithMockUser(roles = "SETTLEMENT")
    void placeSellOrderReturns201WithExecutedStatusAndMessageWhenWithinLimit() throws Exception {
        SellOrderRequestDTO request = validRequestBuilder().build();

        SellOrderResponseDTO response =
                SellOrderResponseDTO.builder()
                        .orderId(100L)
                        .inboundDetailId(1L)
                        .accountId(1L)
                        .foreignProductId(1L)
                        .sellQty(new BigDecimal("10"))
                        .status(SellOrderStatus.EXECUTED)
                        .build();

        when(sellOrderService.placeSellOrder(any(), any())).thenReturn(List.of(response));

        mockMvc.perform(
                        post("/api/admin/sell-orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("매도 주문이 체결되었습니다."))
                .andExpect(jsonPath("$.data[0].orderId").value(100))
                .andExpect(jsonPath("$.data[0].status").value("EXECUTED"));
    }

    @Test
    @DisplayName("매도 수량이 여러 입고 lot에 걸쳐 FIFO로 체결되면 체결된 lot 수만큼 응답 목록이 반환된다")
    @WithMockUser(roles = "SETTLEMENT")
    void placeSellOrderReturnsOneEntryPerLotWhenFilledAcrossMultipleLots() throws Exception {
        SellOrderRequestDTO request = validRequestBuilder().build();

        SellOrderResponseDTO firstLot =
                SellOrderResponseDTO.builder()
                        .orderId(100L)
                        .inboundDetailId(1L)
                        .accountId(1L)
                        .foreignProductId(1L)
                        .sellQty(new BigDecimal("6"))
                        .status(SellOrderStatus.EXECUTED)
                        .build();
        SellOrderResponseDTO secondLot =
                SellOrderResponseDTO.builder()
                        .orderId(101L)
                        .inboundDetailId(2L)
                        .accountId(1L)
                        .foreignProductId(1L)
                        .sellQty(new BigDecimal("4"))
                        .status(SellOrderStatus.EXECUTED)
                        .build();

        when(sellOrderService.placeSellOrder(any(), any()))
                .thenReturn(List.of(firstLot, secondLot));

        mockMvc.perform(
                        post("/api/admin/sell-orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("매도 주문이 체결되었습니다."))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].inboundDetailId").value(1))
                .andExpect(jsonPath("$.data[1].inboundDetailId").value(2));
    }

    @Test
    @DisplayName("한도 초과로 거부되면 201과 REJECTED 상태, 거부 메시지를 반환하고 lot과 연결되지 않은 단건 응답을 준다")
    @WithMockUser(roles = "SETTLEMENT")
    void placeSellOrderReturns201WithRejectedStatusAndMessageWhenLimitExceeded() throws Exception {
        SellOrderRequestDTO request = validRequestBuilder().build();

        SellOrderResponseDTO response =
                SellOrderResponseDTO.builder()
                        .orderId(101L)
                        .inboundDetailId(null)
                        .accountId(1L)
                        .foreignProductId(1L)
                        .sellQty(new BigDecimal("10"))
                        .status(SellOrderStatus.REJECTED)
                        .build();

        when(sellOrderService.placeSellOrder(any(), any())).thenReturn(List.of(response));

        mockMvc.perform(
                        post("/api/admin/sell-orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("매도 한도 초과로 거부되었습니다."))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].orderId").value(101))
                .andExpect(jsonPath("$.data[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.data[0].inboundDetailId").doesNotExist());
    }

    @Test
    @DisplayName("매도 주문 접수 시 서비스에서 예외가 발생하면 400을 반환한다")
    @WithMockUser(roles = "SETTLEMENT")
    void placeSellOrderReturns400WhenServiceThrowsException() throws Exception {
        SellOrderRequestDTO request = validRequestBuilder().build();

        when(sellOrderService.placeSellOrder(any(), any()))
                .thenThrow(new SellOrderException("한도를 초과했습니다."));

        mockMvc.perform(
                        post("/api/admin/sell-orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("한도를 초과했습니다."));
    }

    @Test
    @DisplayName(
            "매도 주문 접수 시 지원하지 않는 거래소면 GlobalExceptionHandler가 전용 핸들러로 502를 반환한다 (부모 KisPriceNotFoundException 핸들러로 새는지 실제 스프링 디스패치로 검증)")
    @WithMockUser(roles = "SETTLEMENT")
    void placeSellOrderReturns502WhenExchangeUnsupported() throws Exception {
        SellOrderRequestDTO request = validRequestBuilder().build();

        when(sellOrderService.placeSellOrder(any(), any()))
                .thenThrow(new UnsupportedExchangeException("지원하지 않는 거래소입니다."));

        mockMvc.perform(
                        post("/api/admin/sell-orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("지원하지 않는 거래소입니다."));
    }

    @Test
    @DisplayName("매도 주문 접수 시 수량이 0이면 검증 실패로 400을 반환하고 서비스는 호출되지 않는다")
    @WithMockUser(roles = "SETTLEMENT")
    void placeSellOrderReturns400WhenSellQtyIsZero() throws Exception {
        SellOrderRequestDTO request = validRequestBuilder().sellQty(BigDecimal.ZERO).build();

        mockMvc.perform(
                        post("/api/admin/sell-orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("매도 수량은 0보다 커야 합니다."));

        verify(sellOrderService, never()).placeSellOrder(any(), any());
    }

    @Test
    @DisplayName("매도 주문 접수 시 수량이 없으면 검증 실패로 400을 반환하고 서비스는 호출되지 않는다")
    @WithMockUser(roles = "SETTLEMENT")
    void placeSellOrderReturns400WhenSellQtyIsMissing() throws Exception {
        SellOrderRequestDTO request = validRequestBuilder().sellQty(null).build();

        mockMvc.perform(
                        post("/api/admin/sell-orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("매도 수량을 입력하세요."));

        verify(sellOrderService, never()).placeSellOrder(any(), any());
    }

    @Test
    @DisplayName("매도 주문 접수 시 수량이 음수면 검증 실패로 400을 반환하고 서비스는 호출되지 않는다")
    @WithMockUser(roles = "SETTLEMENT")
    void placeSellOrderReturns400WhenSellQtyIsNegative() throws Exception {
        SellOrderRequestDTO request = validRequestBuilder().sellQty(new BigDecimal("-5")).build();

        mockMvc.perform(
                        post("/api/admin/sell-orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("매도 수량은 0보다 커야 합니다."));

        verify(sellOrderService, never()).placeSellOrder(any(), any());
    }

    @Test
    @DisplayName("매도 주문 접수 시 계좌 ID가 없으면 검증 실패로 400을 반환하고 서비스는 호출되지 않는다")
    @WithMockUser(roles = "SETTLEMENT")
    void placeSellOrderReturns400WhenAccountIdMissing() throws Exception {
        SellOrderRequestDTO request = validRequestBuilder().accountId(null).build();

        mockMvc.perform(
                        post("/api/admin/sell-orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("계좌 ID를 입력하세요."));

        verify(sellOrderService, never()).placeSellOrder(any(), any());
    }

    @Test
    @DisplayName("매도 주문 접수 시 종목 ID가 없으면 검증 실패로 400을 반환하고 서비스는 호출되지 않는다")
    @WithMockUser(roles = "SETTLEMENT")
    void placeSellOrderReturns400WhenForeignProductIdMissing() throws Exception {
        SellOrderRequestDTO request = validRequestBuilder().foreignProductId(null).build();

        mockMvc.perform(
                        post("/api/admin/sell-orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("종목 ID를 입력하세요."));

        verify(sellOrderService, never()).placeSellOrder(any(), any());
    }

    @Test
    @DisplayName("매도 주문 접수 시 인증되지 않은 요청이면 401을 반환하고 서비스는 호출되지 않는다")
    void placeSellOrderReturns401WhenNotAuthenticated() throws Exception {
        SellOrderRequestDTO request = validRequestBuilder().build();

        mockMvc.perform(
                        post("/api/admin/sell-orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        verify(sellOrderService, never()).placeSellOrder(any(), any());
    }

    @Test
    @DisplayName("매도 주문 접수 시 SETTLEMENT/ADMIN이 아니면 403을 반환하고 서비스는 호출되지 않는다")
    @WithMockUser(roles = "VIEWER")
    void placeSellOrderReturns403WhenCallerIsNotSettlementOrAdmin() throws Exception {
        SellOrderRequestDTO request = validRequestBuilder().build();

        mockMvc.perform(
                        post("/api/admin/sell-orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verify(sellOrderService, never()).placeSellOrder(any(), any());
    }

    @Test
    @DisplayName("매도 주문 조회 시 존재하면 200과 결과를 반환한다")
    @WithMockUser(roles = "SETTLEMENT")
    void getSellOrderReturns200WithResultWhenExists() throws Exception {
        SellOrderResponseDTO response =
                SellOrderResponseDTO.builder()
                        .orderId(100L)
                        .status(SellOrderStatus.EXECUTED)
                        .build();

        when(sellOrderService.getSellOrder(100L)).thenReturn(response);

        mockMvc.perform(get("/api/admin/sell-orders/{orderId}", 100L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(100));
    }

    @Test
    @DisplayName("매도 주문 조회 시 존재하지 않으면 404를 반환한다")
    @WithMockUser(roles = "SETTLEMENT")
    void getSellOrderReturns404WhenNotFound() throws Exception {
        when(sellOrderService.getSellOrder(999L))
                .thenThrow(new SellOrderNotFoundException("매도 주문 조회 실패"));

        mockMvc.perform(get("/api/admin/sell-orders/{orderId}", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("매도 주문 조회 실패"));
    }

    @Test
    @DisplayName("계좌ID로 매도 주문 목록을 조회하면 200과 목록을 반환한다")
    @WithMockUser(roles = "VIEWER")
    void getAllSellOrdersReturns200WithListForAccount() throws Exception {
        SellOrderResponseDTO response =
                SellOrderResponseDTO.builder()
                        .orderId(100L)
                        .inboundDetailId(1L)
                        .status(SellOrderStatus.EXECUTED)
                        .build();

        when(sellOrderService.getSellOrderByAccount(1L)).thenReturn(List.of(response));

        mockMvc.perform(get("/api/admin/sell-orders").param("accountId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].orderId").value(100))
                .andExpect(jsonPath("$.data[0].status").value("EXECUTED"));
    }

    @Test
    @DisplayName("계좌에 매도 주문이 없으면 빈 목록을 반환한다")
    @WithMockUser(roles = "VIEWER")
    void getAllSellOrdersReturns200WithEmptyListWhenNoOrders() throws Exception {
        when(sellOrderService.getSellOrderByAccount(999L)).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/sell-orders").param("accountId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("계좌별 매도 주문 목록 조회 시 인증되지 않은 요청이면 401을 반환한다")
    void getAllSellOrdersReturns401WhenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/sell-orders").param("accountId", "1"))
                .andExpect(status().isUnauthorized());

        verify(sellOrderService, never()).getSellOrderByAccount(any());
    }

    @Test
    @DisplayName("전체 매도·환전 내역을 조회하면 200과 목록/건수를 반환한다")
    @WithMockUser(roles = "VIEWER")
    void getSellOrderHistoryReturns200WithPageContent() throws Exception {
        SellOrderHistoryDTO history =
                SellOrderHistoryDTO.builder()
                        .accountNo("1000000001")
                        .customerName("홍길동")
                        .ticker("AAPL")
                        .name("Apple Inc.")
                        .status("EXECUTED")
                        .build();
        PageResponseDTO<SellOrderHistoryDTO> page = PageResponseDTO.of(List.of(history), 1, 0, 20);
        when(sellOrderService.getSellOrderHistory(null, null, null, null, 0, 20)).thenReturn(page);

        mockMvc.perform(get("/api/admin/sell-orders/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("매도 · 환전 내역 조회 성공"))
                .andExpect(jsonPath("$.data.content[0].accountNo").value("1000000001"))
                .andExpect(jsonPath("$.data.content[0].customerName").value("홍길동"))
                .andExpect(jsonPath("$.data.totalCount").value(1));
    }

    @Test
    @DisplayName("keyword/상태/날짜범위/페이지 파라미터를 그대로 서비스에 전달한다")
    @WithMockUser(roles = "VIEWER")
    void getSellOrderHistoryPassesQueryParamsToService() throws Exception {
        when(sellOrderService.getSellOrderHistory(
                        "1234567890",
                        SellOrderStatus.EXECUTED,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 10),
                        2,
                        10))
                .thenReturn(PageResponseDTO.of(List.of(), 0, 2, 10));

        mockMvc.perform(
                        get("/api/admin/sell-orders/history")
                                .param("keyword", "1234567890")
                                .param("status", "EXECUTED")
                                .param("startDate", "2026-08-01")
                                .param("endDate", "2026-08-10")
                                .param("page", "2")
                                .param("size", "10"))
                .andExpect(status().isOk());

        verify(sellOrderService)
                .getSellOrderHistory(
                        "1234567890",
                        SellOrderStatus.EXECUTED,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 10),
                        2,
                        10);
    }

    @Test
    @DisplayName("매도·환전 내역이 없으면 빈 목록을 반환한다")
    @WithMockUser(roles = "VIEWER")
    void getSellOrderHistoryReturns200WithEmptyContentWhenNoOrdersExist() throws Exception {
        when(sellOrderService.getSellOrderHistory(null, null, null, null, 0, 20))
                .thenReturn(PageResponseDTO.of(List.of(), 0, 0, 20));

        mockMvc.perform(get("/api/admin/sell-orders/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalCount").value(0));
    }

    @Test
    @DisplayName("page가 음수면 검증 실패로 400을 반환하고 서비스는 호출되지 않는다")
    @WithMockUser(roles = "VIEWER")
    void getSellOrderHistoryReturns400WhenPageIsNegative() throws Exception {
        mockMvc.perform(get("/api/admin/sell-orders/history").param("page", "-1"))
                .andExpect(status().isBadRequest());

        verify(sellOrderService, never())
                .getSellOrderHistory(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("size가 0 이하면 검증 실패로 400을 반환하고 서비스는 호출되지 않는다")
    @WithMockUser(roles = "VIEWER")
    void getSellOrderHistoryReturns400WhenSizeIsNotPositive() throws Exception {
        mockMvc.perform(get("/api/admin/sell-orders/history").param("size", "0"))
                .andExpect(status().isBadRequest());

        verify(sellOrderService, never())
                .getSellOrderHistory(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("전체 매도·환전 내역 조회 시 인증되지 않은 요청이면 401을 반환한다")
    void getSellOrderHistoryReturns401WhenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/sell-orders/history")).andExpect(status().isUnauthorized());

        verify(sellOrderService, never())
                .getSellOrderHistory(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("요약 조회는 오늘 값과 전일 대비 증감률을 반환한다")
    @WithMockUser(roles = "VIEWER")
    void getSellOrderSummaryReturns200WithTodayValuesAndChangeRates() throws Exception {
        SellOrderSummaryDTO summary =
                SellOrderSummaryDTO.builder()
                        .todaySellAmount(new BigDecimal("1100000"))
                        .todaySellAmountChangeRate(new BigDecimal("10.0"))
                        .todayExecutedCount(5)
                        .todayExecutedCountChangeRate(new BigDecimal("-50.0"))
                        .pendingProvisionalAmount(new BigDecimal("2900000"))
                        .todayFinalizedAmount(new BigDecimal("110000"))
                        .build();
        when(sellOrderService.getSellOrderSummary()).thenReturn(summary);

        mockMvc.perform(get("/api/admin/sell-orders/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.todaySellAmount").value(1100000))
                .andExpect(jsonPath("$.data.todaySellAmountChangeRate").value(10.0))
                .andExpect(jsonPath("$.data.todayExecutedCount").value(5))
                .andExpect(jsonPath("$.data.todayExecutedCountChangeRate").value(-50.0))
                .andExpect(jsonPath("$.data.pendingProvisionalAmount").value(2900000))
                .andExpect(jsonPath("$.data.todayFinalizedAmount").value(110000));
    }

    @Test
    @DisplayName("요약 조회 시 인증되지 않은 요청이면 401을 반환한다")
    void getSellOrderSummaryReturns401WhenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/sell-orders/summary")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ADMIN이 매도 주문을 하면 403을 반환한다")
    @WithMockUser(roles = "ADMIN")
    void placeSellOrderReturns403WhenCallerIsAdmin() throws Exception {
        SellOrderRequestDTO request = validRequestBuilder().build();

        mockMvc.perform(
                        post("/api/admin/sell-orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verify(sellOrderService, never()).placeSellOrder(any(), any());
    }

    @Test
    @DisplayName("SETTLEMENT이 매도 주문을 하면 성공한다")
    @WithMockUser(username = "1", roles = "SETTLEMENT")
    void placeSellOrderReturns201WhenCallerIsSettlement() throws Exception {
        SellOrderResponseDTO response =
                SellOrderResponseDTO.builder()
                        .orderId(100L)
                        .status(SellOrderStatus.EXECUTED)
                        .build();
        when(sellOrderService.placeSellOrder(any(), any())).thenReturn(List.of(response));

        mockMvc.perform(
                        post("/api/admin/sell-orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(validRequestBuilder().build())))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("VIEWER가 매도 이력을 조회하면 성공한다")
    @WithMockUser(roles = "VIEWER")
    void getSellOrderHistoryReturns200WhenCallerIsViewer() throws Exception {
        when(sellOrderService.getSellOrderHistory(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(PageResponseDTO.of(List.of(), 0, 0, 20));

        mockMvc.perform(get("/api/admin/sell-orders/history"))
                .andExpect(status().isOk());
    }
}
