package com.app.maria.domain.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.service.AccountTransactionalService;
import com.app.maria.domain.sellorder.dto.SellOrderDTO;
import com.app.maria.domain.settlement.component.ProvisionalExchangeCalculator;
import com.app.maria.domain.settlement.component.SettlementBusinessDayCalculator;
import com.app.maria.domain.settlement.dto.KrwExchangeDTO;
import com.app.maria.domain.settlement.exception.ProvisionalException;
import com.app.maria.domain.settlement.mapper.KrwExchangeMapper;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProvisionalExchangeServiceImplTest {
    private static final Long ACCOUNT_ID = 1L;
    private static final Long ORDER_ID = 10L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 10, 10, 0);
    private static final LocalDateTime FINAL_AT = LocalDateTime.of(2026, 8, 12, 0, 0);
    private static final BigDecimal PROVISIONAL_AMOUNT = BigDecimal.valueOf(267_300L);

    @Mock private ProvisionalExchangeCalculator provisionalExchangeCalculator;
    @Mock private SettlementBusinessDayCalculator settlementBusinessDayCalculator;
    @Mock private KrwExchangeMapper krwExchangeMapper;
    @Mock private BusinessClockService businessClockService;
    @Mock private AccountTransactionalService accountTransactionalService;
    @InjectMocks private ProvisionalExchangeServiceImpl service;

    @Test
    void createsProvisionalExchangeAndIncreasesAccountBalance() {
        SellOrderDTO order = executedOrder();
        when(provisionalExchangeCalculator.calculate(order.getSellQty(), order.getBasePrice()))
                .thenReturn(PROVISIONAL_AMOUNT);
        when(businessClockService.now()).thenReturn(NOW);
        when(settlementBusinessDayCalculator.calculateFinalAt(NOW)).thenReturn(FINAL_AT);
        when(krwExchangeMapper.insertProvisional(any(KrwExchangeDTO.class))).thenReturn(1);

        service.createProvisionalExchange(order);

        ArgumentCaptor<KrwExchangeDTO> exchangeCaptor =
                ArgumentCaptor.forClass(KrwExchangeDTO.class);
        verify(krwExchangeMapper).insertProvisional(exchangeCaptor.capture());
        assertThat(exchangeCaptor.getValue())
                .extracting(
                        KrwExchangeDTO::getAccountId,
                        KrwExchangeDTO::getOrderId,
                        KrwExchangeDTO::getProvisionalAmount,
                        KrwExchangeDTO::getProvisionalAt,
                        KrwExchangeDTO::getFinalAt)
                .containsExactly(ACCOUNT_ID, ORDER_ID, PROVISIONAL_AMOUNT, NOW, FINAL_AT);

        ArgumentCaptor<AccountDTO> accountCaptor = ArgumentCaptor.forClass(AccountDTO.class);
        verify(accountTransactionalService).updateAmount(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(accountCaptor.getValue().getAmount()).isEqualByComparingTo(PROVISIONAL_AMOUNT);
    }

    @Test
    void throwsWhenProvisionalExchangeInsertFails() {
        SellOrderDTO order = executedOrder();
        when(provisionalExchangeCalculator.calculate(order.getSellQty(), order.getBasePrice()))
                .thenReturn(PROVISIONAL_AMOUNT);
        when(businessClockService.now()).thenReturn(NOW);
        when(settlementBusinessDayCalculator.calculateFinalAt(NOW)).thenReturn(FINAL_AT);
        when(krwExchangeMapper.insertProvisional(any(KrwExchangeDTO.class))).thenReturn(0);

        assertThatThrownBy(() -> service.createProvisionalExchange(order))
                .isInstanceOf(ProvisionalException.class)
                .hasMessage("가환전 저장 실패");

        verify(accountTransactionalService, never()).updateAmount(any(AccountDTO.class));
    }

    private SellOrderDTO executedOrder() {
        return SellOrderDTO.builder()
                .orderId(ORDER_ID)
                .accountId(ACCOUNT_ID)
                .sellQty(BigDecimal.TEN)
                .basePrice(BigDecimal.valueOf(27_000L))
                .build();
    }
}
