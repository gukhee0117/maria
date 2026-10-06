package com.app.maria.domain.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.type.BenefitType;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountBenefitChangeTest {

    private static final Long ACCOUNT_ID = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 12, 1, 0);
    private static final String REASON = "테스트 사유";

    @Mock AccountMapper accountMapper;

    @Mock AccountLogService accountLogService;

    @InjectMocks AccountTransactionalServiceImpl accountTransactionalService;

    private void stubAccount(BenefitType current) {
        AccountDTO account = new AccountDTO();
        account.setAccountId(ACCOUNT_ID);
        account.setBenefit(current);
        when(accountMapper.selectByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(account));
    }

    private void stubUpdateSucceeds(BenefitType from, BenefitType to) {
        when(accountMapper.updateBenefit(ACCOUNT_ID, to, from)).thenReturn(1);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        "POSSIBLE, REDUCED",
        "REDUCED, POSSIBLE",
        "POSSIBLE, IMPOSSIBLE",
        "REDUCED, IMPOSSIBLE"
    })
    @DisplayName("허용된 전이는 상태를 바꾸고 이력을 남긴다")
    void 허용된_전이(BenefitType from, BenefitType to) {
        stubAccount(from);
        stubUpdateSucceeds(from, to);

        assertThat(accountTransactionalService.changeBenefit(ACCOUNT_ID, to, REASON, NOW)).isTrue();

        verify(accountMapper).updateBenefit(ACCOUNT_ID, to, from);
        verify(accountLogService).recordBenefitChange(any(), eq(from), eq(NOW), eq(REASON));
    }

    @Test
    @DisplayName("POSSIBLE과 REDUCED는 왕복할 수 있다")
    void 왕복_허용() {
        stubAccount(BenefitType.REDUCED);
        stubUpdateSucceeds(BenefitType.REDUCED, BenefitType.POSSIBLE);

        assertThat(
                        accountTransactionalService.changeBenefit(
                                ACCOUNT_ID, BenefitType.POSSIBLE, "외부 순매수가 상계되어 복구", NOW))
                .isTrue();
    }

    @Test
    @DisplayName("이력에 이전 상태와 새 상태가 그대로 담긴다")
    void 이력_내용() {
        stubAccount(BenefitType.POSSIBLE);
        stubUpdateSucceeds(BenefitType.POSSIBLE, BenefitType.REDUCED);

        accountTransactionalService.changeBenefit(ACCOUNT_ID, BenefitType.REDUCED, REASON, NOW);

        ArgumentCaptor<AccountDTO> captor = ArgumentCaptor.forClass(AccountDTO.class);
        verify(accountLogService)
                .recordBenefitChange(
                        captor.capture(), eq(BenefitType.POSSIBLE), eq(NOW), eq(REASON));
        // recordBenefitChange가 account.getBenefit()을 새 상태로 읽으므로 갱신된 값이 넘어가야 한다
        assertThat(captor.getValue().getBenefit()).isEqualTo(BenefitType.REDUCED);
        assertThat(captor.getValue().getAccountId()).isEqualTo(ACCOUNT_ID);
    }

    @ParameterizedTest(name = "IMPOSSIBLE → {0}")
    @EnumSource(
            value = BenefitType.class,
            names = {"POSSIBLE", "REDUCED"})
    @DisplayName("IMPOSSIBLE에서는 복구되지 않는다")
    void 배제_복구불가(BenefitType to) {
        stubAccount(BenefitType.IMPOSSIBLE);

        assertThat(accountTransactionalService.changeBenefit(ACCOUNT_ID, to, REASON, NOW))
                .isFalse();

        verify(accountMapper, never()).updateBenefit(anyLong(), any(), any());
        verifyNoInteractions(accountLogService);
    }

    @ParameterizedTest(name = "{0} → {0}")
    @EnumSource(BenefitType.class)
    @DisplayName("같은 상태로 바꾸면 아무 일도 하지 않는다")
    void 같은_상태(BenefitType status) {
        stubAccount(status);

        assertThat(accountTransactionalService.changeBenefit(ACCOUNT_ID, status, REASON, NOW))
                .isFalse();

        verify(accountMapper, never()).updateBenefit(anyLong(), any(), any());
        verifyNoInteractions(accountLogService);
    }

    @Test
    @DisplayName("혜택 상태가 없는(null) 계좌도 전이할 수 있다")
    void 초기상태_null() {
        stubAccount(null);
        when(accountMapper.updateBenefit(ACCOUNT_ID, BenefitType.REDUCED, null)).thenReturn(1);

        assertThat(
                        accountTransactionalService.changeBenefit(
                                ACCOUNT_ID, BenefitType.REDUCED, REASON, NOW))
                .isTrue();

        verify(accountLogService).recordBenefitChange(any(), isNull(), eq(NOW), eq(REASON));
    }

    @Test
    @DisplayName("조회와 변경 사이에 상태가 바뀌었으면 이력을 남기지 않는다")
    void 경합_0행() {
        stubAccount(BenefitType.POSSIBLE);
        when(accountMapper.updateBenefit(ACCOUNT_ID, BenefitType.REDUCED, BenefitType.POSSIBLE))
                .thenReturn(0);

        assertThat(
                        accountTransactionalService.changeBenefit(
                                ACCOUNT_ID, BenefitType.REDUCED, REASON, NOW))
                .isFalse();

        verifyNoInteractions(accountLogService);
    }

    @Test
    @DisplayName("계좌가 없으면 예외를 던지고 아무것도 바꾸지 않는다")
    void 계좌없음() {
        when(accountMapper.selectByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                accountTransactionalService.changeBenefit(
                                        ACCOUNT_ID, BenefitType.REDUCED, REASON, NOW))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ACCOUNT_NOT_FOUND.getMessage());

        verify(accountMapper, never()).updateBenefit(anyLong(), any(), any());
        verifyNoInteractions(accountLogService);
    }
}
