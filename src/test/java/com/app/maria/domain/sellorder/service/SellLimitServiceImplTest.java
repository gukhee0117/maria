package com.app.maria.domain.sellorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.type.Status;
import com.app.maria.domain.sellorder.dto.response.SellLimitStatusDTO;
import com.app.maria.domain.sellorder.mapper.SellLimitMapper;
import com.app.maria.global.client.mydata.MydataClient;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class SellLimitServiceImplTest {

    @Mock SellLimitMapper sellLimitMapper;

    @Mock MydataClient mydataClient;

    @Mock AccountMapper accountMapper;

    @InjectMocks SellLimitServiceImpl sellLimitService;

    private void stubAccount(Long accountId, String limitAmount, String usedAmount, String ciHash) {
        when(sellLimitMapper.selectAccountLimitForUpdate(accountId))
                .thenReturn(Optional.of(new BigDecimal(limitAmount)));
        when(sellLimitMapper.sumUsedAmount(accountId)).thenReturn(new BigDecimal(usedAmount));
        when(sellLimitMapper.selectCiHashByAccountId(accountId)).thenReturn(Optional.of(ciHash));
    }

    @Test
    @DisplayName("RIA 확정산 + 미확정 매도주문 + myData 외부 순매수 + 이번 주문금액 합이 한도 이내면 true를 반환한다")
    void isWithinSellLimitReturnsTrueWhenTotalIsWithinLimit() {
        stubAccount(100L, "50000000", "20000000", "ci-hash-1");
        when(mydataClient.getExternalSellTotal(any(), any()))
                .thenReturn(new BigDecimal("10000000"));

        assertThat(sellLimitService.isWithinSellLimit(100L, new BigDecimal("15000000"))).isTrue();
    }

    @Test
    @DisplayName(
            "호출 순서: selectCiHash → HTTP → selectAccountLimitForUpdate(락) → sumUsedAmount — 이 순서가 깨지면 테스트가 실패한다")
    void isWithinSellLimitCallsInExpectedOrder() {
        stubAccount(100L, "50000000", "0", "ci-hash-1");
        when(mydataClient.getExternalSellTotal(any(), any())).thenReturn(BigDecimal.ZERO);

        sellLimitService.isWithinSellLimit(100L, BigDecimal.ZERO);

        var order = inOrder(sellLimitMapper, mydataClient);
        order.verify(sellLimitMapper).selectCiHashByAccountId(100L);
        order.verify(mydataClient).getExternalSellTotal(any(), any());
        order.verify(sellLimitMapper).selectAccountLimitForUpdate(100L);
        order.verify(sellLimitMapper).sumUsedAmount(100L);
    }

    @Test
    @DisplayName("확정산+미확정+외부순매수+이번주문금액 합이 한도를 넘으면 false를 반환한다")
    void isWithinSellLimitReturnsFalseWhenTotalExceedsLimit() {
        stubAccount(100L, "50000000", "20000000", "ci-hash-1");
        when(mydataClient.getExternalSellTotal(any(), any()))
                .thenReturn(new BigDecimal("10000000"));

        assertThat(sellLimitService.isWithinSellLimit(100L, new BigDecimal("20000001"))).isFalse();
    }

    @Test
    @DisplayName("합계가 한도와 정확히 같으면(경계값) 초과가 아니므로 true를 반환한다")
    void isWithinSellLimitReturnsTrueWhenTotalExactlyEqualsLimit() {
        stubAccount(100L, "50000000", "20000000", "ci-hash-1");
        when(mydataClient.getExternalSellTotal(any(), any()))
                .thenReturn(new BigDecimal("10000000"));

        assertThat(sellLimitService.isWithinSellLimit(100L, new BigDecimal("20000000"))).isTrue();
    }

    @Test
    @DisplayName("계좌 로컬 한도 판정은 한도 사용액+이번 주문금액만 정확히 합산하고, 외부 순매수는 포함하지 않는다")
    void isWithinSellLimitComparesOnlyLocalSumAgainstAccountLimit() {
        stubAccount(100L, "3500", "3500", "ci-hash-1");
        when(mydataClient.getExternalSellTotal(any(), any()))
                .thenReturn(new BigDecimal("40000000"));

        assertThat(sellLimitService.isWithinSellLimit(100L, BigDecimal.ZERO)).isTrue();
        assertThat(sellLimitService.isWithinSellLimit(100L, new BigDecimal("1"))).isFalse();
    }

    @Test
    @DisplayName("계좌 로컬 한도 이내여도 한도 사용액+외부순매수+이번 주문금액 합이 5천만원 하드캡을 넘으면 거부한다")
    void isWithinSellLimitReturnsFalseWhenGlobalCapExceededEvenWithinAccountLimit() {
        stubAccount(100L, "50000000", "0", "ci-hash-1");
        when(mydataClient.getExternalSellTotal(any(), any()))
                .thenReturn(new BigDecimal("40000000"));

        assertThat(sellLimitService.isWithinSellLimit(100L, new BigDecimal("10000000"))).isTrue();
        assertThat(sellLimitService.isWithinSellLimit(100L, new BigDecimal("10000001"))).isFalse();
    }

    @Test
    @DisplayName("다른 증권사에서 이미 판 금액이 있어도, 이 계좌 로컬 한도와 5천만원 하드캡을 둘 다 넘지 않으면 승인한다 (버그 이슈 재현 시나리오)")
    void isWithinSellLimitApprovesWhenBothLocalAndGlobalLimitsAreRespectedDespiteExternalUsage() {
        stubAccount(100L, "30000000", "0", "ci-hash-1");
        when(mydataClient.getExternalSellTotal(any(), any()))
                .thenReturn(new BigDecimal("15000000"));

        assertThat(sellLimitService.isWithinSellLimit(100L, new BigDecimal("25000000"))).isTrue();
    }

    @Test
    @DisplayName("RIA 확정산이 0이어도 미확정(EXECUTED) 매도주문 합계만으로 한도초과를 잡아낸다")
    void isWithinSellLimitCatchesExcessFromPendingSellOrdersAloneWhenFinalizedSumIsZero() {
        stubAccount(100L, "50000000", "40000000", "ci-hash-1");
        when(mydataClient.getExternalSellTotal(any(), any())).thenReturn(BigDecimal.ZERO);

        assertThat(sellLimitService.isWithinSellLimit(100L, new BigDecimal("10000000"))).isTrue();
        assertThat(sellLimitService.isWithinSellLimit(100L, new BigDecimal("10000001"))).isFalse();
    }

    @Test
    @DisplayName("계좌 한도 정보를 찾을 수 없으면 예외를 던지고 이후 sumUsedAmount는 호출하지 않는다")
    void isWithinSellLimitThrowsWhenAccountLimitNotFound() {
        // ciHash 조회 → myData HTTP → 행 락 순서이므로 account-not-found 전에 myData는 이미 호출됨
        when(sellLimitMapper.selectCiHashByAccountId(100L)).thenReturn(Optional.of("ci-hash-1"));
        when(mydataClient.getExternalSellTotal(any(), any())).thenReturn(BigDecimal.ZERO);
        when(sellLimitMapper.selectAccountLimitForUpdate(100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sellLimitService.isWithinSellLimit(100L, new BigDecimal("1000")))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.SELL_ORDER_ACCOUNT_NOT_FOUND.getMessage());

        verify(sellLimitMapper, never()).sumUsedAmount(any());
    }

    @Test
    @DisplayName("ci_hash를 찾을 수 없으면 예외를 던지고 myData는 조회하지 않는다")
    void isWithinSellLimitThrowsWhenCiHashNotFound() {
        when(sellLimitMapper.selectCiHashByAccountId(100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sellLimitService.isWithinSellLimit(100L, new BigDecimal("1000")))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.SELL_ORDER_CUSTOMER_NOT_FOUND.getMessage());

        verifyNoInteractions(mydataClient);
    }

    private static AccountDTO account(Status status, String limitAmount) {
        return AccountDTO.builder()
                .accountId(100L)
                .customerId(7L)
                .status(status)
                .limitAmount(new BigDecimal(limitAmount))
                .build();
    }

    @Test
    @DisplayName("고객 매도 한도: 설정한도가 더 빡빡하면 설정한도 − 누적이 남은 한도다")
    void statusRemainingIsLimitMinusUsedWhenAccountLimitBinds() {
        when(accountMapper.selectByCustomerId(7L))
                .thenReturn(Optional.of(account(Status.OPENED, "30000000")));
        when(sellLimitMapper.selectCiHashByAccountId(100L)).thenReturn(Optional.of("ci"));
        when(sellLimitMapper.sumUsedAmount(100L)).thenReturn(new BigDecimal("12000000"));
        when(mydataClient.getExternalSellTotal(any(), any())).thenReturn(BigDecimal.ZERO);

        SellLimitStatusDTO status = sellLimitService.getStatusByCustomerId(7L);

        assertThat(status.limitAmount()).isEqualByComparingTo("30000000");
        assertThat(status.usedAmount()).isEqualByComparingTo("12000000");
        assertThat(status.remainingLimit()).isEqualByComparingTo("18000000");
    }

    @Test
    @DisplayName("고객 매도 한도: 타사 누적 매도 때문에 5천만 상한이 더 빡빡하면 그 값이 남은 한도다")
    void statusRemainingIsCappedByGlobalLimitWhenExternalSellIsLarge() {
        when(accountMapper.selectByCustomerId(7L))
                .thenReturn(Optional.of(account(Status.OPENED, "50000000")));
        when(sellLimitMapper.selectCiHashByAccountId(100L)).thenReturn(Optional.of("ci"));
        when(sellLimitMapper.sumUsedAmount(100L)).thenReturn(new BigDecimal("10000000"));
        when(mydataClient.getExternalSellTotal(any(), any()))
                .thenReturn(new BigDecimal("35000000"));

        assertThat(sellLimitService.getStatusByCustomerId(7L).remainingLimit())
                .isEqualByComparingTo("5000000");
    }

    @Test
    @DisplayName("고객 매도 한도: 이미 넘었으면 남은 한도는 음수가 아니라 0이다")
    void statusRemainingIsNeverNegative() {
        when(accountMapper.selectByCustomerId(7L))
                .thenReturn(Optional.of(account(Status.OPENED, "50000000")));
        when(sellLimitMapper.selectCiHashByAccountId(100L)).thenReturn(Optional.of("ci"));
        when(sellLimitMapper.sumUsedAmount(100L)).thenReturn(new BigDecimal("40000000"));
        when(mydataClient.getExternalSellTotal(any(), any()))
                .thenReturn(new BigDecimal("20000000"));

        assertThat(sellLimitService.getStatusByCustomerId(7L).remainingLimit())
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("고객 매도 한도: OPENED가 아닌 계좌는 매도 주문과 같은 예외이고 myData를 부르지 않는다")
    void statusRejectsAccountThatCannotTrade() {
        when(accountMapper.selectByCustomerId(7L))
                .thenReturn(Optional.of(account(Status.CLOSED, "50000000")));

        assertThatThrownBy(() -> sellLimitService.getStatusByCustomerId(7L))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ACCOUNT_STATE_NOT_ALLOWED.getMessage());

        verifyNoInteractions(mydataClient);
    }

    @Test
    @DisplayName("고객 매도 한도: RIA 계좌가 없으면 ACCOUNT_NOT_FOUND다")
    void statusRejectsCustomerWithoutAccount() {
        when(accountMapper.selectByCustomerId(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sellLimitService.getStatusByCustomerId(7L))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ACCOUNT_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("한도 조회 경로는 myData HTTP 호출 동안 DB 커넥션을 쥐지 않도록 트랜잭션 밖(NOT_SUPPORTED)이다")
    void limitQueriesRunOutsideTransaction() throws Exception {
        for (var method :
                new java.lang.reflect.Method[] {
                    SellLimitServiceImpl.class.getMethod("getRemainingLimit", Long.class),
                    SellLimitServiceImpl.class.getMethod("getStatusByCustomerId", Long.class)
                }) {
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as(method.getName()).isNotNull();
            assertThat(tx.propagation()).as(method.getName()).isEqualTo(Propagation.NOT_SUPPORTED);
        }
    }
}
