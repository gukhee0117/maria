package com.app.maria.domain.sellorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.sellorder.dto.SellOrderDTO;
import com.app.maria.domain.sellorder.dto.request.CustomerSellOrderRequestDTO;
import com.app.maria.domain.sellorder.dto.request.SellOrderRequestDTO;
import com.app.maria.domain.sellorder.dto.response.CustomerSellOrderResponseDTO;
import com.app.maria.domain.sellorder.dto.response.CustomerSellOrderResultResponseDTO;
import com.app.maria.domain.sellorder.dto.response.SellOrderResponseDTO;
import com.app.maria.domain.sellorder.mapper.CustomerSellOrderMapper;
import com.app.maria.domain.sellorder.type.SellOrderStatus;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.response.PageResponseDTO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomerSellOrderServiceImplTest {

    @Mock AccountMapper accountMapper;
    @Mock CustomerSellOrderMapper customerSellOrderMapper;
    @Mock SellOrderService sellOrderService;
    @InjectMocks CustomerSellOrderServiceImpl service;

    private void stubAccount() {
        when(accountMapper.selectByCustomerId(7L))
                .thenReturn(Optional.of(AccountDTO.builder().accountId(100L).build()));
    }

    @Test
    @DisplayName("본인 계좌의 매도 내역을 페이지 정보와 함께 준다")
    void returnsPageOfOwnAccount() {
        stubAccount();
        CustomerSellOrderResponseDTO row = new CustomerSellOrderResponseDTO();
        when(customerSellOrderMapper.selectByAccountId(100L, 40L, 20)).thenReturn(List.of(row));
        when(customerSellOrderMapper.countByAccountId(100L)).thenReturn(41);

        PageResponseDTO<CustomerSellOrderResponseDTO> result = service.getSellOrders(7L, 2, 20);

        assertThat(result.getContent()).containsExactly(row);
        assertThat(result.getTotalCount()).isEqualTo(41);
        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("size는 1~100으로, page는 0 이상으로 보정한다")
    void clampsPageAndSize() {
        stubAccount();
        when(customerSellOrderMapper.selectByAccountId(100L, 0L, 100)).thenReturn(List.of());
        when(customerSellOrderMapper.selectByAccountId(100L, 0L, 1)).thenReturn(List.of());

        assertThat(service.getSellOrders(7L, -5, 100000).getSize()).isEqualTo(100);
        assertThat(service.getSellOrders(7L, -5, 0).getSize()).isEqualTo(1);
        verify(customerSellOrderMapper).selectByAccountId(100L, 0L, 100);
        verify(customerSellOrderMapper).selectByAccountId(100L, 0L, 1);
    }

    @Test
    @DisplayName("RIA 계좌가 없으면 ACCOUNT_NOT_FOUND다")
    void rejectsCustomerWithoutAccount() {
        when(accountMapper.selectByCustomerId(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSellOrders(7L, 0, 20))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ACCOUNT_NOT_FOUND.getMessage());
        verifyNoInteractions(customerSellOrderMapper);
    }

    @Test
    @DisplayName("page가 아주 커도 offset이 int 오버플로로 음수가 되지 않는다")
    void offsetDoesNotOverflow() {
        stubAccount();
        when(customerSellOrderMapper.selectByAccountId(100L, (long) Integer.MAX_VALUE * 100, 100))
                .thenReturn(List.of());

        assertThat(service.getSellOrders(7L, Integer.MAX_VALUE, 100).getContent()).isEmpty();
        verify(customerSellOrderMapper)
                .selectByAccountId(100L, (long) Integer.MAX_VALUE * 100, 100);
    }

    private static SellOrderResponseDTO placed(
            long orderId, long lotId, String qty, String price, SellOrderStatus status) {
        SellOrderDTO dto =
                SellOrderDTO.builder()
                        .orderId(orderId)
                        .inboundDetailId(lotId == 0 ? null : lotId)
                        .accountId(100L)
                        .foreignProductId(10L)
                        .sellQty(new BigDecimal(qty))
                        .basePrice(new BigDecimal(price))
                        .status(status)
                        .processedAt(LocalDateTime.of(2026, 11, 4, 10, 0))
                        .build();
        return new SellOrderResponseDTO(dto);
    }

    private static CustomerSellOrderRequestDTO request(String qty) {
        CustomerSellOrderRequestDTO request = new CustomerSellOrderRequestDTO();
        request.setForeignProductId(10L);
        request.setSellQty(new BigDecimal(qty));
        return request;
    }

    @Test
    @DisplayName("신청: 본인 계좌 ID로 기존 매도 로직을 부르고(관리자 행위자 없음) lot별 체결을 합계와 함께 돌려준다")
    void placeOrderDelegatesWithOwnAccountAndSumsLots() {
        when(accountMapper.selectByCustomerId(7L))
                .thenReturn(Optional.of(AccountDTO.builder().accountId(100L).build()));
        when(sellOrderService.placeSellOrder(any(), any()))
                .thenReturn(
                        List.of(
                                placed(1L, 501L, "3", "100.3333", SellOrderStatus.EXECUTED),
                                placed(2L, 502L, "2", "100.3333", SellOrderStatus.EXECUTED)));

        CustomerSellOrderResultResponseDTO result = service.placeOrder(7L, request("5"));

        ArgumentCaptor<SellOrderRequestDTO> captor =
                ArgumentCaptor.forClass(SellOrderRequestDTO.class);
        verify(sellOrderService).placeSellOrder(isNull(), captor.capture());
        assertThat(captor.getValue().getAccountId()).isEqualTo(100L);
        assertThat(captor.getValue().getForeignProductId()).isEqualTo(10L);
        assertThat(captor.getValue().getSellQty()).isEqualByComparingTo("5");
        assertThat(result.getStatus()).isEqualTo(SellOrderStatus.EXECUTED);
        assertThat(result.getOrders()).hasSize(2);
        assertThat(result.getTotalQty()).isEqualByComparingTo("5");
        // 3 × 100.3333 = 300.9999 → 301.00, 2 × 100.3333 = 200.6666 → 200.67
        assertThat(result.getOrders().get(0).getOrderAmount()).isEqualByComparingTo("301.00");
        assertThat(result.getTotalAmount()).isEqualByComparingTo("501.67");
    }

    @Test
    @DisplayName("한도 초과로 거부되면 REJECTED 한 건을 돌려준다")
    void placeOrderReportsRejection() {
        when(accountMapper.selectByCustomerId(7L))
                .thenReturn(Optional.of(AccountDTO.builder().accountId(100L).build()));
        when(sellOrderService.placeSellOrder(any(), any()))
                .thenReturn(List.of(placed(9L, 0L, "5", "100", SellOrderStatus.REJECTED)));

        CustomerSellOrderResultResponseDTO result = service.placeOrder(7L, request("5"));

        assertThat(result.getStatus()).isEqualTo(SellOrderStatus.REJECTED);
        assertThat(result.getOrders())
                .singleElement()
                .satisfies(o -> assertThat(o.getInboundDetailId()).isNull());
    }

    @Test
    @DisplayName("신청: RIA 계좌가 없으면 ACCOUNT_NOT_FOUND이고 매도 로직을 부르지 않는다")
    void placeOrderRejectsCustomerWithoutAccount() {
        when(accountMapper.selectByCustomerId(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.placeOrder(7L, request("5")))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ACCOUNT_NOT_FOUND.getMessage());
        verifyNoInteractions(sellOrderService);
    }
}
