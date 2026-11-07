package com.app.maria.domain.stockpackage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.domestic.type.DomesticOrderSide;
import com.app.maria.domain.stockpackage.mapper.StockOrderMapper;
import com.app.maria.domain.stockpackage.vo.StockOrderVO;
import com.app.maria.global.client.securities.SecuritiesTradeClient;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class SecuritiesOrderRecorderTest {
    @Mock SecuritiesTradeClient client;
    @Mock StockOrderMapper mapper;
    @Mock PlatformTransactionManager txManager;

    SecuritiesOrderRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new SecuritiesOrderRecorder(client, mapper, txManager);
    }

    private static StockOrderVO order(long id) {
        return StockOrderVO.builder()
                .domesticOrderId(id)
                .side(DomesticOrderSide.BUY)
                .ticker("005930")
                .qty(BigDecimal.TEN)
                .price(new BigDecimal("100000"))
                .processedAt(LocalDateTime.of(2026, 11, 6, 10, 0))
                .build();
    }

    @Test
    void 보내기에_성공하면_표시를_남긴다() {
        assertThat(recorder.record("ci", order(1L))).isTrue();

        verify(mapper).markSecuritiesRecorded(1L);
    }

    @Test
    void 보내기에_실패하면_던지지_않고_표시도_남기지_않는다() {
        doThrow(new AppException(ErrorType.STOCK_ORDER_SECURITIES_RECORD_FAILED))
                .when(client)
                .record(any(), any(), any(), any(), any(), any(), any());

        assertThat(recorder.record("ci", order(2L))).isFalse();

        verify(mapper, never()).markSecuritiesRecorded(any());
    }

    @Test
    void 재시도는_못_보낸_주문의_ciHash로_다시_보낸다() {
        StockOrderVO pending = order(3L);
        pending.setCiHash("ci-3");
        when(mapper.selectUnrecordedCustomerOrders(50)).thenReturn(List.of(pending));

        recorder.retryUnrecorded();

        verify(client)
                .record(
                        org.mockito.ArgumentMatchers.eq("ci-3"),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.eq(3L));
        verify(mapper).markSecuritiesRecorded(3L);
    }
}
