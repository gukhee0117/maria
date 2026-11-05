package com.app.maria.domain.risk.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.risk.RiskTestSupport;
import com.app.maria.domain.risk.dto.RiskProductDTO;
import com.app.maria.domain.risk.dto.StockRiskGradeDTO;
import com.app.maria.domain.risk.mapper.StockRiskGradeMapper;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskGradeClassificationServiceTest {
    @Test
    void onlyRequestedStocksAreQueried() {
        var mapper = mock(StockRiskGradeMapper.class);
        var live = mock(CurrentStockRiskService.class);
        when(mapper.findProducts())
                .thenReturn(
                        List.of(
                                new RiskProductDTO(1L, "005930", "A", Type.STOCK),
                                new RiskProductDTO(2L, "000660", "B", Type.STOCK)));
        when(live.findCurrent("005930"))
                .thenReturn(
                        new CurrentStockRiskService.CurrentStockGrade(
                                "005930", 2, OffsetDateTime.parse("2026-11-05T12:00:00+09:00")));
        var service = new RiskGradeServiceImpl(mapper, null, null, live);
        assertThat(service.findUsableGrades(List.of(1L)))
                .containsExactlyEntriesOf(java.util.Map.of(1L, 2));
        verify(live, never()).findCurrent("000660");
    }

    @Test
    void expiredFundIsExcludedWhileCurrentStockGradeRemainsAvailable() throws Exception {
        var mapper = mock(StockRiskGradeMapper.class);
        var live = mock(CurrentStockRiskService.class);
        var stock = new RiskProductDTO(1L, "005930", "Stock", Type.STOCK);
        var fund = new RiskProductDTO(2L, "069500", "ETF", Type.FUND);
        when(mapper.findProducts()).thenReturn(List.of(stock, fund));
        when(mapper.sessionUtcOffsetSeconds()).thenReturn(32400);
        var row = new StockRiskGradeDTO();
        row.setDomesticProductId(2L);
        row.setTicker("069500");
        row.setAnalysisVersion(1L);
        row.setPriceFrom(LocalDate.parse("2023-10-23"));
        row.setPriceTo(LocalDate.parse("2026-10-23"));
        row.setRiskGrade(5);
        row.setVar975(BigDecimal.ONE);
        row.setVolatility(BigDecimal.ONE);
        when(mapper.findAll()).thenReturn(List.of(row));
        when(live.findCurrent("005930"))
                .thenReturn(
                        new CurrentStockRiskService.CurrentStockGrade(
                                "005930", 2, OffsetDateTime.parse("2026-10-30T17:00:00+09:00")));
        var service =
                new RiskGradeServiceImpl(
                        mapper,
                        RiskTestSupport.calendar(),
                        RiskTestSupport.clock("2026-10-30T17:00:00+09:00"),
                        live);
        assertThat(service.findUsable()).isEmpty();
        assertThat(service.findUsableGrades()).containsExactlyEntriesOf(java.util.Map.of(1L, 2));
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

    @Test
    void failedCurrentStatusDoesNotReuseOldStoredStockGrade() {
        var mapper = mock(StockRiskGradeMapper.class);
        var live = mock(CurrentStockRiskService.class);
        when(mapper.findProducts())
                .thenReturn(List.of(new RiskProductDTO(1L, "005930", "Stock", Type.STOCK)));
        when(live.findCurrent("005930"))
                .thenThrow(new AppException(ErrorType.RISK_UPSTREAM_UNAVAILABLE));
        assertThat(new RiskGradeServiceImpl(mapper, null, null, live).findUsableGrades()).isEmpty();
        verify(mapper, never()).findAll();
    }
}
