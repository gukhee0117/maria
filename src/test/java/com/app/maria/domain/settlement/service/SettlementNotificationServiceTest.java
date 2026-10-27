package com.app.maria.domain.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.settlement.notification.SettlementSseService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
class SettlementNotificationServiceTest {

    @Mock private AccountMapper accountMapper;
    @Mock private SettlementSseService settlementSseService;
    @InjectMocks private SettlementNotificationService service;

    @Test
    void subscribesOnlyTheAccountOwnedByTheLoggedInCustomer() {
        SseEmitter emitter = new SseEmitter();
        when(accountMapper.selectByCustomerId(3L))
                .thenReturn(Optional.of(AccountDTO.builder().accountId(7L).customerId(3L).build()));
        when(settlementSseService.subscribeAccount(7L)).thenReturn(emitter);

        SseEmitter result = service.subscribeCustomer(3L);

        assertThat(result).isSameAs(emitter);
        verify(accountMapper).selectByCustomerId(3L);
        verify(settlementSseService).subscribeAccount(7L);
    }

    @Test
    void rejectsSubscriptionWhenCustomerHasNoAccount() {
        when(accountMapper.selectByCustomerId(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.subscribeCustomer(3L))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.ACCOUNT_NOT_FOUND);

        verify(settlementSseService, never()).subscribeAccount(org.mockito.ArgumentMatchers.any());
    }
}
