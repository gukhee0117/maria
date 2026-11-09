package com.app.maria.domain.account.service;

import static com.app.maria.domain.account.type.CustomerConsentType.MYDATA_PROVISION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MydataConsentServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 9, 10, 0);

    @Mock private AccountMapper accountMapper;
    @Mock private BusinessClockService businessClockService;
    @InjectMocks private MydataConsentServiceImpl service;

    @Test
    void 이력이_없으면_동의하지_않은_것이다() {
        when(accountMapper.selectLatestConsent(1L, MYDATA_PROVISION)).thenReturn(Optional.empty());
        assertThat(service.isAgreed(1L)).isFalse();
        assertThatThrownBy(() -> service.requireAgreed(1L))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_MYDATA_CONSENT_REQUIRED));
    }

    @Test
    void 철회한_고객은_동의하지_않은_것이다() {
        when(accountMapper.selectLatestConsent(1L, MYDATA_PROVISION))
                .thenReturn(Optional.of(false));
        assertThatThrownBy(() -> service.requireAgreed(1L)).isInstanceOf(AppException.class);
    }

    @Test
    void 동의한_고객은_통과한다() {
        when(accountMapper.selectLatestConsent(1L, MYDATA_PROVISION)).thenReturn(Optional.of(true));
        service.requireAgreed(1L);
    }

    @Test
    void 값이_바뀔_때만_이력을_남긴다() {
        when(businessClockService.now()).thenReturn(NOW);
        when(accountMapper.selectLatestConsent(1L, MYDATA_PROVISION)).thenReturn(Optional.of(true));
        service.record(1L, true);
        verify(accountMapper, never()).insertConsent(1L, MYDATA_PROVISION, true, NOW);

        service.record(1L, false);
        verify(accountMapper).insertConsent(1L, MYDATA_PROVISION, false, NOW);
    }

    @Test
    void 이력이_없으면_동의를_처음_기록한다() {
        when(businessClockService.now()).thenReturn(NOW);
        when(accountMapper.selectLatestConsent(1L, MYDATA_PROVISION)).thenReturn(Optional.empty());
        service.record(1L, true);
        verify(accountMapper).insertConsent(1L, MYDATA_PROVISION, true, NOW);
    }
}
