package com.app.maria.domain.risk.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.risk.RiskTestSupport;
import com.app.maria.domain.risk.dto.StockRiskGradeDTO;
import com.app.maria.domain.risk.mapper.StockRiskGradeMapper;
import com.app.maria.domain.risk.type.RiskDataStatus;
import com.app.maria.global.exception.AppException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskGradeServiceTest {
    StockRiskGradeDTO row(long version) {
        var row = new StockRiskGradeDTO();
        row.setDomesticProductId(1L);
        row.setTicker("005930");
        row.setAnalysisVersion(version);
        row.setPriceFrom(LocalDate.parse("2023-10-30"));
        row.setPriceTo(LocalDate.parse("2026-10-23"));
        row.setVar975(new BigDecimal(".3125"));
        row.setVolatility(new BigDecimal(".2410"));
        row.setRiskGrade(2);
        return row;
    }

    @Test
    void migratedVersionZeroIsNeverUsedEvenIfDateIsRecent() throws Exception {
        var mapper = mock(StockRiskGradeMapper.class);
        when(mapper.sessionUtcOffsetSeconds()).thenReturn(32400);
        when(mapper.findAll()).thenReturn(List.of(row(0)));
        var service =
                new RiskGradeServiceImpl(
                        mapper,
                        RiskTestSupport.calendar(),
                        RiskTestSupport.clock("2026-10-26T17:00:00+09:00"));
        assertThat(service.findUsable()).isEmpty();
    }

    @Test
    void existingDataExpiresWithoutAnySuccessfulSync() throws Exception {
        var mapper = mock(StockRiskGradeMapper.class);
        when(mapper.sessionUtcOffsetSeconds()).thenReturn(32400);
        when(mapper.findAll()).thenReturn(List.of(row(1)));
        var cal = RiskTestSupport.calendar();
        var atThree =
                new RiskGradeServiceImpl(
                        mapper, cal, RiskTestSupport.clock("2026-10-29T17:00:00+09:00"));
        assertThat(atThree.findUsable())
                .singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo(RiskDataStatus.STALE));
        var beforeFour =
                new RiskGradeServiceImpl(
                        mapper, cal, RiskTestSupport.clock("2026-10-30T15:29:59+09:00"));
        assertThat(beforeFour.findUsable()).hasSize(1);
        var atFour =
                new RiskGradeServiceImpl(
                        mapper, cal, RiskTestSupport.clock("2026-10-30T15:30:00+09:00"));
        assertThat(atFour.findUsable()).isEmpty();
        verify(mapper, never()).updateIfNewer(any());
    }

    @Test
    void wrongSessionTimezoneFailsClosed() throws Exception {
        var mapper = mock(StockRiskGradeMapper.class);
        when(mapper.sessionUtcOffsetSeconds()).thenReturn(0);
        var service =
                new RiskGradeServiceImpl(
                        mapper,
                        RiskTestSupport.calendar(),
                        RiskTestSupport.clock("2026-10-28T17:00:00+09:00"));
        assertThatThrownBy(service::findUsable).isInstanceOf(AppException.class);
        verify(mapper, never()).findAll();
    }

    @Test
    void findUsableByTickerAppliesSameRulesAsFindUsable() throws Exception {
        var mapper = mock(StockRiskGradeMapper.class);
        when(mapper.sessionUtcOffsetSeconds()).thenReturn(32400);
        when(mapper.findByTicker("005930")).thenReturn(row(1));
        when(mapper.findByTicker("000660")).thenReturn(row(0));
        when(mapper.findByTicker("999999")).thenReturn(null);
        var cal = RiskTestSupport.calendar();
        var service =
                new RiskGradeServiceImpl(
                        mapper, cal, RiskTestSupport.clock("2026-10-26T17:00:00+09:00"));

        assertThat(service.findUsableByTicker("005930"))
                .get()
                .satisfies(r -> assertThat(r.ticker()).isEqualTo("005930"));
        assertThat(service.findUsableByTicker("000660")).isEmpty(); // version 0
        assertThat(service.findUsableByTicker("999999")).isEmpty(); // 자료 없음
        var expired =
                new RiskGradeServiceImpl(
                        mapper, cal, RiskTestSupport.clock("2026-10-30T15:30:00+09:00"));
        assertThat(expired.findUsableByTicker("005930")).isEmpty();
        verify(mapper, never()).findAll();
    }

    @Test
    void findUsableByTickerFailsClosedOnWrongTimezone() throws Exception {
        var mapper = mock(StockRiskGradeMapper.class);
        when(mapper.sessionUtcOffsetSeconds()).thenReturn(0);
        var service =
                new RiskGradeServiceImpl(
                        mapper,
                        RiskTestSupport.calendar(),
                        RiskTestSupport.clock("2026-10-28T17:00:00+09:00"));
        assertThatThrownBy(() -> service.findUsableByTicker("005930"))
                .isInstanceOf(AppException.class);
    }

    @Test
    void findUsableByTickersReturnsOnlyUsableRequestedTickersWithoutReadingAll() throws Exception {
        var mapper = mock(StockRiskGradeMapper.class);
        when(mapper.sessionUtcOffsetSeconds()).thenReturn(32400);
        when(mapper.findByTicker("005930")).thenReturn(row(1));
        when(mapper.findByTicker("000660")).thenReturn(row(0));
        when(mapper.findByTicker("999999")).thenReturn(null);
        var service =
                new RiskGradeServiceImpl(
                        mapper,
                        RiskTestSupport.calendar(),
                        RiskTestSupport.clock("2026-10-26T17:00:00+09:00"));

        var result = service.findUsableByTickers(java.util.Set.of("005930", "000660", "999999"));

        assertThat(result).containsOnlyKeys("005930"); // version 0, 자료 없음은 빠진다
        verify(mapper, never()).findAll();
    }

    @Test
    void findUsableByTickersFailsClosedOnWrongTimezone() throws Exception {
        var mapper = mock(StockRiskGradeMapper.class);
        when(mapper.sessionUtcOffsetSeconds()).thenReturn(0);
        var service =
                new RiskGradeServiceImpl(
                        mapper,
                        RiskTestSupport.calendar(),
                        RiskTestSupport.clock("2026-10-26T17:00:00+09:00"));

        assertThatThrownBy(() -> service.findUsableByTickers(java.util.Set.of("005930")))
                .isInstanceOf(AppException.class);
    }
}
