package com.app.maria.domain.inbound.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.foreignproduct.type.ForeignProductType;
import com.app.maria.domain.inbound.dto.InboundListDTO;
import com.app.maria.domain.inbound.dto.InboundPageDTO;
import com.app.maria.domain.inbound.dto.response.AccountHoldingResponseDTO;
import com.app.maria.domain.inbound.dto.response.InboundAccountSummaryResponseDTO;
import com.app.maria.domain.inbound.dto.response.InboundPriorApprovalResponseDTO;
import com.app.maria.domain.inbound.dto.response.InboundResponseDTO;
import com.app.maria.domain.inbound.dto.response.InboundSummaryResponseDTO;
import com.app.maria.domain.inbound.exception.InboundNotFoundException;
import com.app.maria.domain.inbound.service.InboundService;
import com.app.maria.global.exception.GlobalExceptionHandler;
import com.app.maria.global.response.PageResponseDTO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InboundApiTest {

    private InboundService inboundService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        inboundService = mock(InboundService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new InboundApi(inboundService))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void processInboundAcceptsValidRequest() throws Exception {
        InboundResponseDTO response =
                InboundResponseDTO.builder()
                        .inboundId(10L)
                        .requestedQty(BigDecimal.valueOf(80))
                        .snapshotQty(BigDecimal.valueOf(100))
                        .currentHoldingAtRequest(BigDecimal.valueOf(90))
                        .approvedQty(BigDecimal.valueOf(80))
                        .processedAt(LocalDateTime.now())
                        .build();
        when(inboundService.processInbound(any())).thenReturn(response);

        mockMvc.perform(
                        post("/api/admin/inbounds")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {
                  "accountId": 1,
                  "foreignProductId": 1,
                  "requestedQty": 80,
                  "currentHoldingAtRequest": 90
                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("입고대상 수량 계산 성공"))
                .andExpect(jsonPath("$.data.approvedQty").value(80));

        verify(inboundService).processInbound(any());
    }

    @Test
    void processInboundRejectsMissingAccountId() throws Exception {
        mockMvc.perform(
                        post("/api/admin/inbounds")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {
                  "foreignProductId": 1,
                  "requestedQty": 80
                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("accountId는 필수입니다."));

        verify(inboundService, never()).processInbound(any());
    }

    @Test
    void processInboundRejectsMissingForeignProductId() throws Exception {
        mockMvc.perform(
                        post("/api/admin/inbounds")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {
                  "accountId": 1,
                  "requestedQty": 80
                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("foreignProductId는 필수입니다."));

        verify(inboundService, never()).processInbound(any());
    }

    @Test
    void processInboundRejectsMissingRequestedQty() throws Exception {
        mockMvc.perform(
                        post("/api/admin/inbounds")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {
                  "accountId": 1,
                  "foreignProductId": 1
                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("requestedQty는 필수입니다."));

        verify(inboundService, never()).processInbound(any());
    }

    @Test
    void processInboundAcceptsMissingCurrentHoldingAtRequest() throws Exception {
        InboundResponseDTO response =
                InboundResponseDTO.builder()
                        .inboundId(10L)
                        .requestedQty(BigDecimal.valueOf(80))
                        .snapshotQty(BigDecimal.valueOf(100))
                        .approvedQty(BigDecimal.valueOf(80))
                        .processedAt(LocalDateTime.now())
                        .build();
        when(inboundService.processInbound(any())).thenReturn(response);

        mockMvc.perform(
                        post("/api/admin/inbounds")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {
                  "accountId": 1,
                  "foreignProductId": 1,
                  "requestedQty": 80
                }
                """))
                .andExpect(status().isOk());

        verify(inboundService).processInbound(any());
    }

    @Test
    void processInboundReturnsNotFoundWhenRegistrableStockMissing() throws Exception {
        when(inboundService.processInbound(any()))
                .thenThrow(new InboundNotFoundException("등록가능 보유수량 조회 실패"));

        mockMvc.perform(
                        post("/api/admin/inbounds")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {
                  "accountId": 1,
                  "foreignProductId": 1,
                  "requestedQty": 80
                }
                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("등록가능 보유수량 조회 실패"));
    }

    @Test
    void getHoldingsReturnsAccountHoldings() throws Exception {
        AccountHoldingResponseDTO holding =
                AccountHoldingResponseDTO.builder()
                        .foreignProductId(1L)
                        .ticker("AAPL")
                        .name("Apple Inc.")
                        .market("NAS")
                        .currency("USD")
                        .type(ForeignProductType.FOREIGN_STOCK)
                        .currentQty(BigDecimal.valueOf(50))
                        .build();
        when(inboundService.getHoldings(1L)).thenReturn(List.of(holding));

        mockMvc.perform(get("/api/admin/inbounds/holdings").param("accountId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("계좌 보유종목 조회 성공"))
                .andExpect(jsonPath("$.data[0].ticker").value("AAPL"))
                .andExpect(jsonPath("$.data[0].currentQty").value(50));

        verify(inboundService).getHoldings(1L);
    }

    @Test
    void getHoldingsReturnsEmptyListWhenAccountHasNoHoldings() throws Exception {
        when(inboundService.getHoldings(1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/inbounds/holdings").param("accountId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void getHoldingsRejectsMissingAccountId() throws Exception {
        mockMvc.perform(get("/api/admin/inbounds/holdings")).andExpect(status().isBadRequest());

        verify(inboundService, never()).getHoldings(anyLong());
    }

    @Test
    void getInboundsReturnsPagedResultAsJsonWithDefaultPageAndSize() throws Exception {
        InboundListDTO item =
                InboundListDTO.builder()
                        .inboundId(1L)
                        .accountNo("1234567890")
                        .customerName("홍길동")
                        .ticker("AAPL")
                        .productName("Apple Inc.")
                        .requestedQty(BigDecimal.valueOf(100))
                        .currentHoldingAtRequest(BigDecimal.valueOf(90))
                        .snapshotQty(BigDecimal.valueOf(80))
                        .approvedQty(BigDecimal.valueOf(80))
                        .remainingQty(BigDecimal.valueOf(0))
                        .processedAt(LocalDateTime.of(2026, 3, 5, 9, 0))
                        .build();
        InboundPageDTO page =
                InboundPageDTO.builder()
                        .content(List.of(item))
                        .page(0)
                        .size(20)
                        .totalElements(1)
                        .totalPages(1)
                        .build();
        when(inboundService.getInbounds(0, 20)).thenReturn(page);

        mockMvc.perform(get("/api/admin/inbounds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("입고 이력 목록 조회 성공"))
                .andExpect(jsonPath("$.data.content[0].accountNo").value("1234567890"))
                .andExpect(jsonPath("$.data.content[0].customerName").value("홍길동"))
                .andExpect(jsonPath("$.data.content[0].ticker").value("AAPL"))
                .andExpect(jsonPath("$.data.content[0].approvedQty").value(80))
                .andExpect(jsonPath("$.data.content[0].remainingQty").value(0))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1));
    }

    @Test
    void getInboundsPassesPageAndSizeQueryParamsToService() throws Exception {
        InboundPageDTO page =
                InboundPageDTO.builder()
                        .content(List.of())
                        .page(2)
                        .size(5)
                        .totalElements(11)
                        .totalPages(3)
                        .build();
        when(inboundService.getInbounds(2, 5)).thenReturn(page);

        mockMvc.perform(get("/api/admin/inbounds").param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(3));
    }

    @Test
    void getInboundsReturnsEmptyListWhenNoInboundsExist() throws Exception {
        InboundPageDTO page =
                InboundPageDTO.builder()
                        .content(List.of())
                        .page(0)
                        .size(20)
                        .totalElements(0)
                        .totalPages(0)
                        .build();
        when(inboundService.getInbounds(0, 20)).thenReturn(page);

        mockMvc.perform(get("/api/admin/inbounds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty());
    }

    @Test
    void getSummaryReturnsTodaySummaryAsJson() throws Exception {
        InboundSummaryResponseDTO summary =
                InboundSummaryResponseDTO.builder()
                        .todayProcessedCount(3)
                        .todayRejectedCount(1)
                        .todayReducedCount(1)
                        .todayApprovedQtySum(BigDecimal.valueOf(70))
                        .build();
        when(inboundService.getSummary()).thenReturn(summary);

        mockMvc.perform(get("/api/admin/inbounds/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("입고 현황 요약 조회 성공"))
                .andExpect(jsonPath("$.data.todayProcessedCount").value(3))
                .andExpect(jsonPath("$.data.todayRejectedCount").value(1))
                .andExpect(jsonPath("$.data.todayReducedCount").value(1))
                .andExpect(jsonPath("$.data.todayApprovedQtySum").value(70));
    }

    @Test
    void getAccountsWithInboundsReturnsPagedResultAsJsonWithDefaultPageAndSize() throws Exception {
        InboundAccountSummaryResponseDTO account =
                InboundAccountSummaryResponseDTO.builder()
                        .accountId(1L)
                        .accountNo("1234567890")
                        .customerName("홍길동")
                        .inboundCount(2)
                        .lastProcessedAt(LocalDateTime.of(2026, 3, 5, 9, 0))
                        .build();
        PageResponseDTO<InboundAccountSummaryResponseDTO> page =
                PageResponseDTO.of(List.of(account), 1, 0, 20);
        when(inboundService.getAccountsWithInbounds(0, 20, null)).thenReturn(page);

        mockMvc.perform(get("/api/admin/inbounds/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("입고 이력 계좌 목록 조회 성공"))
                .andExpect(jsonPath("$.data.content[0].accountNo").value("1234567890"))
                .andExpect(jsonPath("$.data.content[0].inboundCount").value(2))
                .andExpect(jsonPath("$.data.totalCount").value(1));
    }

    @Test
    void getAccountsWithInboundsReturnsEmptyListWhenNoAccountsExist() throws Exception {
        PageResponseDTO<InboundAccountSummaryResponseDTO> page =
                PageResponseDTO.of(List.of(), 0, 0, 20);
        when(inboundService.getAccountsWithInbounds(0, 20, null)).thenReturn(page);

        mockMvc.perform(get("/api/admin/inbounds/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty());
    }

    @Test
    void getAccountsWithInboundsPassesKeywordQueryParamToService() throws Exception {
        PageResponseDTO<InboundAccountSummaryResponseDTO> page =
                PageResponseDTO.of(List.of(), 0, 0, 20);
        when(inboundService.getAccountsWithInbounds(0, 20, "홍길동")).thenReturn(page);

        mockMvc.perform(get("/api/admin/inbounds/accounts").param("keyword", "홍길동"))
                .andExpect(status().isOk());

        verify(inboundService).getAccountsWithInbounds(0, 20, "홍길동");
    }

    @Test
    void getInboundsByAccountReturnsPagedResultAsJson() throws Exception {
        InboundListDTO item =
                InboundListDTO.builder()
                        .inboundId(1L)
                        .accountId(1L)
                        .accountNo("1234567890")
                        .customerName("홍길동")
                        .ticker("AAPL")
                        .productName("Apple Inc.")
                        .requestedQty(BigDecimal.valueOf(100))
                        .approvedQty(BigDecimal.valueOf(80))
                        .remainingQty(BigDecimal.valueOf(0))
                        .processedAt(LocalDateTime.of(2026, 3, 5, 9, 0))
                        .build();
        InboundPageDTO page =
                InboundPageDTO.builder()
                        .content(List.of(item))
                        .page(0)
                        .size(20)
                        .totalElements(1)
                        .totalPages(1)
                        .build();
        when(inboundService.getInboundsByAccount(1L, 0, 20)).thenReturn(page);

        mockMvc.perform(get("/api/admin/inbounds/by-account/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("계좌별 입고 내역 조회 성공"))
                .andExpect(jsonPath("$.data.content[0].ticker").value("AAPL"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void getInboundsByAccountReturnsEmptyListWhenAccountHasNoInbounds() throws Exception {
        InboundPageDTO page =
                InboundPageDTO.builder()
                        .content(List.of())
                        .page(0)
                        .size(20)
                        .totalElements(0)
                        .totalPages(0)
                        .build();
        when(inboundService.getInboundsByAccount(1L, 0, 20)).thenReturn(page);

        mockMvc.perform(get("/api/admin/inbounds/by-account/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty());
    }

    @Test
    void getPriorApprovalsReturnsListAsJson() throws Exception {
        InboundPriorApprovalResponseDTO prior =
                InboundPriorApprovalResponseDTO.builder()
                        .inboundId(1L)
                        .requestedQty(BigDecimal.valueOf(10))
                        .approvedQty(BigDecimal.valueOf(10))
                        .processedAt(LocalDateTime.of(2026, 3, 1, 9, 0))
                        .build();
        when(inboundService.getPriorApprovals(2L)).thenReturn(List.of(prior));

        mockMvc.perform(get("/api/admin/inbounds/2/prior-approvals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("기존 승인 내역 조회 성공"))
                .andExpect(jsonPath("$.data[0].inboundId").value(1))
                .andExpect(jsonPath("$.data[0].approvedQty").value(10));
    }

    @Test
    void getPriorApprovalsReturnsEmptyListWhenNoPriorApprovalsExist() throws Exception {
        when(inboundService.getPriorApprovals(2L)).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/inbounds/2/prior-approvals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
