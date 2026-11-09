package com.app.maria.domain.domestic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.domestic.dto.DomesticProductDTO;
import com.app.maria.domain.domestic.dto.DomesticTradeHistoryDTO;
import com.app.maria.domain.domestic.dto.response.CustomerDomesticTradeResponseDTO;
import com.app.maria.domain.domestic.mapper.DomesticProductMapper;
import com.app.maria.global.client.domestictrade.DomesticTradeClient;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.response.PageResponseDTO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomerDomesticTradeServiceImplTest {

    @Mock AccountMapper accountMapper;
    @Mock DomesticProductMapper domesticProductMapper;
    @Mock DomesticTradeClient domesticTradeClient;
    @InjectMocks CustomerDomesticTradeServiceImpl service;

    private void stubIdentity() {
        when(accountMapper.selectByCustomerId(7L))
                .thenReturn(Optional.of(AccountDTO.builder().accountId(100L).build()));
        when(accountMapper.selectCiHashByCustomerId(7L)).thenReturn(Optional.of("ci-7"));
    }

    private static DomesticTradeHistoryDTO trade(
            String type, String code, String qty, String price) {
        return DomesticTradeHistoryDTO.builder()
                .tradeType(type)
                .stockCode(code)
                .qty(new BigDecimal(qty))
                .price(new BigDecimal(price))
                .executedAt(LocalDateTime.of(2026, 11, 3, 10, 0))
                .build();
    }

    private static DomesticProductDTO product(String ticker, String name) {
        DomesticProductDTO p = new DomesticProductDTO();
        p.setTicker(ticker);
        p.setName(name);
        return p;
    }

    @Test
    @DisplayName("체결에 종목명과 금액(수량×체결가)을 붙이고 마스터에 없는 종목은 이름이 null이다")
    void addsNameAndAmount() {
        stubIdentity();
        when(domesticTradeClient.findByCiHash("ci-7"))
                .thenReturn(
                        List.of(
                                trade("BUY", "005930", "10", "70000"),
                                trade("SELL", "999999", "3", "1000.5")));
        when(domesticProductMapper.selectByTickers(Set.of("005930", "999999")))
                .thenReturn(List.of(product("005930", "삼성전자")));

        PageResponseDTO<CustomerDomesticTradeResponseDTO> result = service.getTrades(7L, 0, 20);

        assertThat(result.getTotalCount()).isEqualTo(2);
        CustomerDomesticTradeResponseDTO first = result.getContent().get(0);
        assertThat(first.getName()).isEqualTo("삼성전자");
        assertThat(first.getTradeType()).isEqualTo("BUY");
        assertThat(first.getAmount()).isEqualByComparingTo("700000");
        CustomerDomesticTradeResponseDTO second = result.getContent().get(1);
        assertThat(second.getName()).isNull();
        assertThat(second.getAmount()).isEqualByComparingTo("3001.5");
    }

    @Test
    @DisplayName("증권사 순서(최신순)를 유지한 채 page·size로 자르고 totalCount는 전체 건수다")
    void pagesInMemoryKeepingOrder() {
        stubIdentity();
        when(domesticTradeClient.findByCiHash("ci-7"))
                .thenReturn(
                        List.of(
                                trade("BUY", "A", "1", "1"),
                                trade("BUY", "B", "1", "1"),
                                trade("BUY", "C", "1", "1")));
        when(domesticProductMapper.selectByTickers(Set.of("B"))).thenReturn(List.of());

        PageResponseDTO<CustomerDomesticTradeResponseDTO> result = service.getTrades(7L, 1, 1);

        assertThat(result.getContent())
                .extracting(CustomerDomesticTradeResponseDTO::getStockCode)
                .containsExactly("B");
        assertThat(result.getTotalCount()).isEqualTo(3);
        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("범위를 벗어난 page는 빈 페이지이고 종목 조회도 하지 않는다")
    void pageBeyondEndIsEmpty() {
        stubIdentity();
        when(domesticTradeClient.findByCiHash("ci-7"))
                .thenReturn(List.of(trade("BUY", "A", "1", "1")));

        PageResponseDTO<CustomerDomesticTradeResponseDTO> result =
                service.getTrades(7L, Integer.MAX_VALUE, 100);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalCount()).isEqualTo(1);
        verify(domesticProductMapper, never()).selectByTickers(anySet());
    }

    @Test
    @DisplayName("증권사 조회 실패는 빈 목록이 아니라 그대로 예외다")
    void clientFailurePropagates() {
        stubIdentity();
        when(domesticTradeClient.findByCiHash("ci-7"))
                .thenThrow(new AppException(ErrorType.DOMESTIC_TRADE_API_UNAVAILABLE));

        assertThatThrownBy(() -> service.getTrades(7L, 0, 20))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.DOMESTIC_TRADE_API_UNAVAILABLE.getMessage());
    }

    @Test
    @DisplayName("RIA 계좌가 없으면 ACCOUNT_NOT_FOUND이고 증권사를 부르지 않는다")
    void rejectsCustomerWithoutAccount() {
        when(accountMapper.selectByCustomerId(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTrades(7L, 0, 20))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ACCOUNT_NOT_FOUND.getMessage());
        verifyNoInteractions(domesticTradeClient);
    }

    @Test
    @DisplayName("수량이나 체결가가 없는 행이 있어도 페이지 전체가 실패하지 않고 그 행의 amount만 null이다")
    void nullQtyOrPriceGivesNullAmount() {
        stubIdentity();
        DomesticTradeHistoryDTO broken =
                DomesticTradeHistoryDTO.builder()
                        .tradeType("BUY")
                        .stockCode("005930")
                        .executedAt(LocalDateTime.of(2026, 11, 3, 10, 0))
                        .build();
        when(domesticTradeClient.findByCiHash("ci-7"))
                .thenReturn(List.of(broken, trade("BUY", "005930", "2", "100")));
        when(domesticProductMapper.selectByTickers(Set.of("005930"))).thenReturn(List.of());

        List<CustomerDomesticTradeResponseDTO> content = service.getTrades(7L, 0, 20).getContent();

        assertThat(content.get(0).getAmount()).isNull();
        assertThat(content.get(1).getAmount()).isEqualByComparingTo("200");
    }
}
