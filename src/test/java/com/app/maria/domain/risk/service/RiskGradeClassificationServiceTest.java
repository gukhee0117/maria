package com.app.maria.domain.risk.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.risk.RiskTestSupport;
import com.app.maria.domain.risk.dto.RiskProductDTO;
import com.app.maria.domain.risk.dto.StockRiskGradeDTO;
import com.app.maria.domain.risk.mapper.StockRiskGradeMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskGradeClassificationServiceTest {
    @Test
    void listGradesUseStoredStockAndFundRowsWithoutLiveCalls() throws Exception {
        var mapper = mock(StockRiskGradeMapper.class);
        var live = mock(CurrentStockRiskService.class);
        when(mapper.sessionUtcOffsetSeconds()).thenReturn(32400);
        when(mapper.findAll())
                .thenReturn(
                        List.of(
                                row(1L, "005930", 2, "2026-10-29"),
                                row(2L, "069500", 5, "2026-10-29"),
                                row(3L, "000660", 1, "2026-10-29")));
        var service = service(mapper, live);

        assertThat(service.findUsableGrades(List.of(1L, 2L)))
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(1L, 2, 2L, 5));
        assertThat(service.findUsableGrades()).hasSize(3);
        verifyNoInteractions(live);
    }

    @Test
    void expiredStoredStockGradeIsNotUsedAndNoLiveFallback() throws Exception {
        var mapper = mock(StockRiskGradeMapper.class);
        var live = mock(CurrentStockRiskService.class);
        when(mapper.sessionUtcOffsetSeconds()).thenReturn(32400);
        when(mapper.findAll()).thenReturn(List.of(row(1L, "005930", 2, "2026-10-23")));

        assertThat(service(mapper, live).findUsableGrades(List.of(1L))).isEmpty();
        verifyNoInteractions(live);
    }

    @Test
    void confirmedGradesRecheckStocksLiveAndKeepStoredFundGrades() throws Exception {
        var mapper = mock(StockRiskGradeMapper.class);
        var live = mock(CurrentStockRiskService.class);
        when(mapper.sessionUtcOffsetSeconds()).thenReturn(32400);
        when(mapper.findAll())
                .thenReturn(
                        List.of(
                                row(1L, "005930", 2, "2026-10-29"),
                                row(2L, "069500", 5, "2026-10-29")));
        when(mapper.findProductsByIds(java.util.Set.of(1L, 2L)))
                .thenReturn(
                        List.of(
                                new RiskProductDTO(1L, "005930", "Stock", Type.STOCK),
                                new RiskProductDTO(2L, "069500", "ETF", Type.FUND)));
        when(live.findCurrent("005930"))
                .thenReturn(
                        new CurrentStockRiskService.CurrentStockGrade(
                                "005930", 1, OffsetDateTime.parse("2026-10-30T17:00:00+09:00")));

        assertThat(service(mapper, live).findConfirmedGrades(List.of(1L, 2L)))
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(1L, 1, 2L, 5));
        verify(live, never()).findCurrent("069500");
    }

    @Test
    void confirmedGradeDoesNotFallBackToStoredValueWhenLiveCheckFails() throws Exception {
        var mapper = mock(StockRiskGradeMapper.class);
        var live = mock(CurrentStockRiskService.class);
        when(mapper.sessionUtcOffsetSeconds()).thenReturn(32400);
        when(mapper.findAll()).thenReturn(List.of(row(1L, "005930", 2, "2026-10-29")));
        when(mapper.findProductsByIds(java.util.Set.of(1L)))
                .thenReturn(List.of(new RiskProductDTO(1L, "005930", "Stock", Type.STOCK)));
        when(live.findCurrent("005930"))
                .thenThrow(
                        new com.app.maria.global.exception.AppException(
                                com.app.maria.global.exception.ErrorType
                                        .RISK_UPSTREAM_UNAVAILABLE));

        assertThat(service(mapper, live).findConfirmedGrades(List.of(1L))).isEmpty();
    }

    private static RiskGradeServiceImpl service(
            StockRiskGradeMapper mapper, CurrentStockRiskService live) throws Exception {
        return new RiskGradeServiceImpl(
                mapper,
                RiskTestSupport.calendar(),
                RiskTestSupport.clock("2026-10-30T17:00:00+09:00"),
                live);
    }

    private static StockRiskGradeDTO row(long id, String ticker, int grade, String priceTo) {
        var row = new StockRiskGradeDTO();
        row.setDomesticProductId(id);
        row.setTicker(ticker);
        row.setAnalysisVersion(1L);
        row.setPriceFrom(LocalDate.parse("2023-10-23"));
        row.setPriceTo(LocalDate.parse(priceTo));
        row.setRiskGrade(grade);
        row.setVar975(BigDecimal.ONE);
        row.setVolatility(BigDecimal.ONE);
        return row;
    }

    @Test
    void stockClassificationDoesNotNeedMarketMetricsOrCalendar() {
        var mapper = mock(StockRiskGradeMapper.class);
        var live = mock(CurrentStockRiskService.class);
        when(mapper.findProductByTicker("005930"))
                .thenReturn(new RiskProductDTO(1L, "005930", "Stock", Type.STOCK));
        when(live.findCurrent("005930"))
                .thenReturn(
                        new CurrentStockRiskService.CurrentStockGrade(
                                "005930", 1, OffsetDateTime.parse("2026-11-05T12:00:00+09:00")));
        var service = new RiskGradeServiceImpl(mapper, null, null, live);
        assertThat(service.findClassificationByTicker("005930"))
                .get()
                .satisfies(g -> assertThat(g.riskGrade()).isEqualTo(1));
        verify(mapper, never()).findByTicker(any());
    }
}
