package com.app.maria.domain.stockpackage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.stockpackage.dto.response.StockQuoteResponseDTO;
import com.app.maria.global.client.kis.DomesticQuote;
import com.app.maria.global.client.kis.KisDomesticPriceClient;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StockQuoteServiceImplTest {

    @Mock KisDomesticPriceClient client;
    @InjectMocks StockQuoteServiceImpl service;

    private static DomesticQuote quote(String ticker, String price) {
        return new DomesticQuote(
                ticker,
                new BigDecimal(price),
                new BigDecimal("1000"),
                new BigDecimal("1300"),
                new BigDecimal("700"));
    }

    @Test
    void 요청_순서대로_돌려주고_중복은_한번만_조회한다() {
        when(client.getQuotes(List.of("005930", "000660")))
                .thenReturn(
                        Map.of(
                                "005930", quote("005930", "98000"),
                                "000660", quote("000660", "405000")));

        List<StockQuoteResponseDTO> r = service.getQuotes(List.of("005930", "000660", "005930"));

        assertThat(r)
                .extracting(StockQuoteResponseDTO::getTicker)
                .containsExactly("005930", "000660");
        assertThat(r).allMatch(StockQuoteResponseDTO::isAvailable);
    }

    @Test
    void 시세를_못받은_종목만_available_false() {
        when(client.getQuotes(List.of("005930", "000660")))
                .thenReturn(Map.of("005930", quote("005930", "98000")));

        List<StockQuoteResponseDTO> r = service.getQuotes(List.of("005930", "000660"));

        assertThat(r.get(0).isAvailable()).isTrue();
        assertThat(r.get(1).isAvailable()).isFalse();
        assertThat(r.get(1).getPrice()).isNull();
    }

    @Test
    void 요청_전체가_실패하면_모두_available_false() {
        when(client.getQuotes(List.of("005930")))
                .thenThrow(new AppException(ErrorType.KIS_PRICE_NOT_FOUND));
        assertThat(service.getQuotes(List.of("005930")))
                .noneMatch(StockQuoteResponseDTO::isAvailable);
    }

    @Test
    void 잘못된_코드_빈목록_20개초과는_거부하고_KIS를_부르지_않는다() {
        List<String> tooMany =
                IntStream.range(0, 21).mapToObj(i -> String.format("%06d", i)).toList();
        for (List<String> bad :
                List.of(
                        List.of("12345"),
                        List.of("abc123"),
                        List.of("005930;"),
                        List.<String>of(),
                        tooMany)) {
            assertThatThrownBy(() -> service.getQuotes(bad))
                    .isInstanceOfSatisfying(
                            AppException.class,
                            e ->
                                    assertThat(e.getErrorType())
                                            .isEqualTo(ErrorType.STOCK_PACKAGE_QUOTE_INVALID));
        }
        verify(client, never()).getQuotes(any());
    }
}
