package com.app.maria.domain.settlement.component;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.sellorder.type.SellOrderStatus;
import com.app.maria.domain.settlement.dto.KrwExchangeDTO;
import com.app.maria.domain.settlement.dto.SettlementJoinDTO;
import com.app.maria.domain.settlement.exception.SettlementAccountMismatchException;
import com.app.maria.domain.settlement.exception.SettlementAccountNotFoundException;
import com.app.maria.domain.settlement.exception.SettlementStateConflictException;
import com.app.maria.domain.settlement.mapper.KrwExchangeMapper;
import com.app.maria.domain.settlement.mapper.SettlementItemMapper;
import com.app.maria.domain.settlement.type.SettlementStatus;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettlementTransactionExecutorTest {

    @Mock private KrwExchangeMapper krwExchangeMapper;

    @Mock private SettlementItemMapper settlementItemMapper;

    @Mock private SettlementCalculator settlementCalculator;
    @Mock private BusinessClockService businessClockService;

    private SettlementTransactionExecutor executor;

    @BeforeEach
    void setUp() {
        executor =
                new SettlementTransactionExecutor(
                        krwExchangeMapper,
                        settlementItemMapper,
                        settlementCalculator,
                        businessClockService);
        lenient().when(businessClockService.now()).thenReturn(LocalDateTime.of(2026, 8, 9, 9, 0));
    }

    @Test
    void executesSettlementInExchangeAccountAndItemOrder() {
        SettlementJoinDTO target = target();
        KrwExchangeDTO exchange = exchange(SettlementStatus.PROVISIONAL);
        LocalDateTime originalFinalAt = exchange.getFinalAt();
        when(krwExchangeMapper.selectExchangeByIdForUpdate(10L)).thenReturn(Optional.of(exchange));
        when(settlementCalculator.calculateFinalAmount(
                        new BigDecimal("2700000"), new BigDecimal("1350"), new BigDecimal("1400")))
                .thenReturn(new BigDecimal("2800000.00"));
        when(krwExchangeMapper.finalizeExchange(any(KrwExchangeDTO.class))).thenReturn(1);
        when(krwExchangeMapper.selectAccountAmountForUpdate(20L))
                .thenReturn(Optional.of(new BigDecimal("100")));
        when(krwExchangeMapper.replaceAccountAmount(any(KrwExchangeDTO.class))).thenReturn(1);
        when(krwExchangeMapper.insertLeftAmount(any(KrwExchangeDTO.class))).thenReturn(1);
        when(settlementItemMapper.updateItemResult(any())).thenReturn(1);

        executor.execute(target, new BigDecimal("1400"));

        InOrder order = inOrder(krwExchangeMapper, settlementItemMapper);
        order.verify(krwExchangeMapper).selectExchangeByIdForUpdate(10L);
        order.verify(krwExchangeMapper).selectAccountAmountForUpdate(20L);
        order.verify(krwExchangeMapper).finalizeExchange(exchange);
        order.verify(krwExchangeMapper).replaceAccountAmount(exchange);
        order.verify(krwExchangeMapper).insertLeftAmount(exchange);
        order.verify(settlementItemMapper).updateItemResult(any());

        ArgumentCaptor<KrwExchangeDTO> exchangeCaptor =
                ArgumentCaptor.forClass(KrwExchangeDTO.class);
        verify(krwExchangeMapper).replaceAccountAmount(exchangeCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(exchangeCaptor.getValue().getProvisionalAmount())
                .isEqualByComparingTo("2700000");
        org.assertj.core.api.Assertions.assertThat(exchangeCaptor.getValue().getFinalAmount())
                .isEqualByComparingTo("2800000.00");
        org.assertj.core.api.Assertions.assertThat(exchangeCaptor.getValue().getFinalAt())
                .isEqualTo(originalFinalAt);
    }

    @Test
    void finalizedExchangeDoesNotIncreaseBalanceOrCreateLeftAmount() {
        SettlementJoinDTO target = target();
        KrwExchangeDTO exchange = exchange(SettlementStatus.FINALIZED);
        when(krwExchangeMapper.selectExchangeByIdForUpdate(10L)).thenReturn(Optional.of(exchange));
        when(settlementItemMapper.updateItemResult(any())).thenReturn(1);

        executor.execute(target, new BigDecimal("1400"));

        verify(settlementItemMapper).updateItemResult(any());
        verify(krwExchangeMapper, never()).finalizeExchange(any());
        verify(krwExchangeMapper, never()).selectAccountAmountForUpdate(any());
        verify(krwExchangeMapper, never()).replaceAccountAmount(any());
        verify(krwExchangeMapper, never()).insertLeftAmount(any());
    }

    @Test
    void rejectsWhenExchangeUpdateDoesNotAffectOneRow() {
        SettlementJoinDTO target = target();
        when(krwExchangeMapper.selectExchangeByIdForUpdate(10L))
                .thenReturn(Optional.of(exchange(SettlementStatus.PROVISIONAL)));
        when(krwExchangeMapper.selectAccountAmountForUpdate(20L))
                .thenReturn(Optional.of(new BigDecimal("2700000.00")));
        when(settlementCalculator.calculateFinalAmount(any(), any(), any()))
                .thenReturn(new BigDecimal("2800000.00"));
        when(krwExchangeMapper.finalizeExchange(any())).thenReturn(0);

        assertThatThrownBy(() -> executor.execute(target, new BigDecimal("1400")))
                .isInstanceOf(SettlementStateConflictException.class);

        verify(krwExchangeMapper).selectAccountAmountForUpdate(20L);
        verify(settlementItemMapper, never()).updateItemResult(any());
    }

    @Test
    void throwsAccountMismatchExceptionWhenExchangeAndTargetAccountsDiffer() {
        SettlementJoinDTO target = target();
        target.setAccountId(21L);
        when(krwExchangeMapper.selectExchangeByIdForUpdate(10L))
                .thenReturn(Optional.of(exchange(SettlementStatus.PROVISIONAL)));

        assertThatThrownBy(() -> executor.execute(target, new BigDecimal("1400")))
                .isInstanceOf(SettlementAccountMismatchException.class);
    }

    @Test
    void throwsAccountNotFoundExceptionWhenLockedAccountDoesNotExist() {
        SettlementJoinDTO target = target();
        when(krwExchangeMapper.selectExchangeByIdForUpdate(10L))
                .thenReturn(Optional.of(exchange(SettlementStatus.PROVISIONAL)));
        when(krwExchangeMapper.selectAccountAmountForUpdate(20L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> executor.execute(target, new BigDecimal("1400")))
                .isInstanceOf(SettlementAccountNotFoundException.class);
    }

    private SettlementJoinDTO target() {
        return SettlementJoinDTO.builder()
                .itemId(30L)
                .batchId(40L)
                .exchangeId(10L)
                .accountId(20L)
                .settlementFxRate(new BigDecimal("1350"))
                .sellOrderStatus(SellOrderStatus.EXECUTED)
                .build();
    }

    private KrwExchangeDTO exchange(SettlementStatus status) {
        return KrwExchangeDTO.builder()
                .exchangeId(10L)
                .accountId(20L)
                .provisionalAmount(new BigDecimal("2700000"))
                .finalAt(LocalDateTime.of(2026, 8, 11, 0, 0))
                .settlementStatus(status)
                .build();
    }
}
