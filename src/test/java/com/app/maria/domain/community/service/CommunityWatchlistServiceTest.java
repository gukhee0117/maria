package com.app.maria.domain.community.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.community.mapper.CommunityWatchlistMapper;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommunityWatchlistServiceTest {
    CommunityWatchlistMapper mapper = mock(CommunityWatchlistMapper.class);
    CommunityTickerService tickers = mock(CommunityTickerService.class);
    BusinessClockService clock = mock(BusinessClockService.class);
    CommunityWatchlistService service = new CommunityWatchlistServiceImpl(mapper, tickers, clock);

    @org.junit.jupiter.api.BeforeEach
    void customerExists() {
        when(mapper.lockCustomer(52L)).thenReturn(52L);
        when(mapper.findTickers(52L)).thenReturn(List.of());
    }

    @Test
    void fullWatchlistRejectsNewTickerButAllowsIdempotentDuplicate() {
        var full =
                java.util.stream.IntStream.range(0, 100)
                        .mapToObj(i -> String.format("%06d", i))
                        .toList();
        when(mapper.findTickers(52L)).thenReturn(full);
        assertThatThrownBy(() -> service.add(52L, "005930"))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.COMMUNITY_WATCHLIST_LIMIT_EXCEEDED));
        verify(mapper, never()).add(any(), any(), any());
        service.add(52L, "000001");
        verify(mapper).add(eq(52L), eq("000001"), any());
    }

    @Test
    void listIsCustomerScoped() {
        when(mapper.findTickers(52L)).thenReturn(List.of("005930"));
        assertThat(service.list(52L)).containsExactly("005930");
        verify(mapper).findTickers(52L);
    }

    @Test
    void addUsesValidatedTickerAndBusinessClock() {
        var now = LocalDateTime.of(2026, 11, 2, 0, 0);
        when(clock.now()).thenReturn(now);
        service.add(52L, "AAPL");
        var order = inOrder(mapper, tickers, clock);
        order.verify(mapper).lockCustomer(52L);
        order.verify(tickers).requireExists("AAPL");
        order.verify(mapper).findTickers(52L);
        order.verify(clock).now();
        order.verify(mapper).add(52L, "AAPL", now);
    }

    @Test
    void invalidOrUnknownNeverWrites() {
        for (String t : new String[] {null, "", "' OR 1=1", "aapl", "A".repeat(21)})
            assertThatThrownBy(() -> service.add(52L, t)).isInstanceOf(AppException.class);
        doThrow(new AppException(ErrorType.COMMUNITY_TICKER_NOT_FOUND))
                .when(tickers)
                .requireExists("NOPE");
        assertThatThrownBy(() -> service.add(52L, "NOPE")).isInstanceOf(AppException.class);
        verify(mapper, never()).add(any(), any(), any());
        verifyNoInteractions(clock);
    }

    @Test
    void deletedProductCanStillBeRemovedRepeatedly() {
        service.remove(52L, "AAPL");
        service.remove(52L, "AAPL");
        verify(mapper, times(2)).remove(52L, "AAPL");
        verifyNoInteractions(tickers, clock);
    }
}
