package com.app.maria.domain.stockpackage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.stockpackage.dto.response.StockPriceHistoryResponseDTO;
import com.app.maria.global.client.securities.SecuritiesMarketClient;
import com.app.maria.global.client.securities.SecuritiesMarketClient.ClosePoint;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockPriceHistoryServiceImplTest {
    private static final LocalDate BUSINESS_DAY = LocalDate.of(2026, 11, 18);

    @Mock SecuritiesMarketClient securities;
    @InjectMocks StockPriceHistoryServiceImpl service;

    @BeforeEach
    void setUp() {
        when(securities.isActive()).thenReturn(true);
        when(securities.businessDate()).thenReturn(BUSINESS_DAY);
    }

    private static ClosePoint point(String date, String close) {
        return new ClosePoint(LocalDate.parse(date), new BigDecimal(close));
    }

    @Test
    void 요청_순서대로_돌려주고_중복은_한번만_조회하며_기준일은_업무일이다() {
        when(securities.domesticHistory(List.of("005930", "000660"), BUSINESS_DAY, 30))
                .thenReturn(
                        Map.of(
                                "005930", List.of(point("2026-11-17", "71000")),
                                "000660", List.of(point("2026-11-17", "150000"))));

        List<StockPriceHistoryResponseDTO> r =
                service.getHistory(List.of("005930", "000660", "005930"), 30);

        assertThat(r)
                .extracting(StockPriceHistoryResponseDTO::getTicker)
                .containsExactly("005930", "000660");
        assertThat(r).allMatch(StockPriceHistoryResponseDTO::isAvailable);
        verify(securities).domesticHistory(List.of("005930", "000660"), BUSINESS_DAY, 30);
    }

    @Test
    void 이력이_없는_종목만_available_false이고_points는_빈_목록이다() {
        when(securities.domesticHistory(List.of("005930", "000660"), BUSINESS_DAY, 30))
                .thenReturn(Map.of("005930", List.of(point("2026-11-17", "71000"))));

        var r = service.getHistory(List.of("005930", "000660"), 30);

        assertThat(r.get(0).isAvailable()).isTrue();
        assertThat(r.get(1).isAvailable()).isFalse();
        assertThat(r.get(1).getPoints()).isEmpty();
    }

    @Test
    void 증권사가_실패하면_모두_available_false이고_예외를_던지지_않는다() {
        when(securities.domesticHistory(any(), any(), anyInt()))
                .thenThrow(new AppException(ErrorType.KIS_PRICE_NOT_FOUND, "005930"));

        var r = service.getHistory(List.of("005930", "000660"), 30);

        assertThat(r).hasSize(2).noneMatch(StockPriceHistoryResponseDTO::isAvailable);
    }

    @Test
    void 증권사_시세를_쓰지_않는_모드는_증권사를_부르지_않고_모두_available_false() {
        when(securities.isActive()).thenReturn(false);

        var r = service.getHistory(List.of("005930"), 30);

        assertThat(r).hasSize(1);
        assertThat(r.get(0).isAvailable()).isFalse();
        verify(securities, never()).domesticHistory(any(), any(), anyInt());
    }

    @Test
    void days는_1에서_60으로_맞춘다() {
        when(securities.domesticHistory(any(), any(), anyInt())).thenReturn(Map.of());

        service.getHistory(List.of("005930"), 0);
        service.getHistory(List.of("005930"), -5);
        service.getHistory(List.of("005930"), 100);
        service.getHistory(List.of("005930"), 45);

        verify(securities, org.mockito.Mockito.times(2))
                .domesticHistory(List.of("005930"), BUSINESS_DAY, 1);
        verify(securities).domesticHistory(List.of("005930"), BUSINESS_DAY, 60);
        verify(securities).domesticHistory(List.of("005930"), BUSINESS_DAY, 45);
    }

    @Test
    void 종가는_정수로_보이고_소수가_있으면_그대로다() {
        when(securities.domesticHistory(any(), any(), anyInt()))
                .thenReturn(
                        Map.of(
                                "005930",
                                List.of(
                                        point("2026-11-16", "15980.0000"),
                                        point("2026-11-17", "15980.5000"),
                                        point("2026-11-18", "100000.0000"))));

        var points = service.getHistory(List.of("005930"), 30).get(0).getPoints();

        assertThat(points.get(0).close().toPlainString()).isEqualTo("15980");
        assertThat(points.get(1).close().toPlainString()).isEqualTo("15980.5");
        // 100000.0000 → 지수 표기(1E+5)가 아니라 100000
        assertThat(points.get(2).close().toPlainString()).isEqualTo("100000");
        assertThat(points.get(2).close().toString()).isEqualTo("100000");
        assertThat(points.get(0).date()).isEqualTo(LocalDate.of(2026, 11, 16));
    }

    @Test
    void 종목이_없거나_20개를_넘거나_코드가_틀리면_400() {
        List<String> tooMany =
                IntStream.range(0, 21).mapToObj(i -> String.format("%06d", i)).toList();
        for (List<String> bad :
                List.of(
                        List.<String>of(),
                        tooMany,
                        List.of("abc"),
                        List.of("005930", "00593"),
                        List.of("00593a"))) {
            assertThatThrownBy(() -> service.getHistory(bad, 30))
                    .isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getErrorType())
                    .isEqualTo(ErrorType.STOCK_PACKAGE_QUOTE_INVALID);
        }
        assertThatThrownBy(() -> service.getHistory(null, 30)).isInstanceOf(AppException.class);
        verify(securities, never()).domesticHistory(any(), any(), eq(30));
    }

    @Test
    void 신규_ETF처럼_영문_대문자가_섞인_코드도_허용한다() {
        when(securities.domesticHistory(any(), any(), anyInt())).thenReturn(Map.of());

        assertThat(service.getHistory(List.of("0000A0"), 30)).hasSize(1);
    }

    @Test
    void 증권사_응답을_다루다_예상_밖_오류가_나도_모두_available_false이고_예외를_던지지_않는다() {
        when(securities.domesticHistory(any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("예상 밖 오류"));

        var r = service.getHistory(List.of("005930", "000660"), 30);

        assertThat(r).hasSize(2).noneMatch(StockPriceHistoryResponseDTO::isAvailable);
    }
}
