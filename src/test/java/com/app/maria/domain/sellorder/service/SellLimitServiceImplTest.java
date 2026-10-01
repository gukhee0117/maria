package com.app.maria.domain.sellorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.app.maria.domain.sellorder.mapper.SellLimitMapper;
import com.app.maria.global.client.mydata.MydataClient;
import com.app.maria.global.error.AppException;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SellLimitServiceImplTest {

    @Mock SellLimitMapper sellLimitMapper;

    @Mock MydataClient mydataClient;

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
        when(mydataClient.getExternalSellTotal("ci-hash-1")).thenReturn(new BigDecimal("10000000"));

        assertThat(sellLimitService.isWithinSellLimit(100L, new BigDecimal("15000000"))).isTrue();
    }

    @Test
    @DisplayName("확정산+미확정+외부순매수+이번주문금액 합이 한도를 넘으면 false를 반환한다")
    void isWithinSellLimitReturnsFalseWhenTotalExceedsLimit() {
        stubAccount(100L, "50000000", "20000000", "ci-hash-1");
        when(mydataClient.getExternalSellTotal("ci-hash-1")).thenReturn(new BigDecimal("10000000"));

        assertThat(sellLimitService.isWithinSellLimit(100L, new BigDecimal("20000001"))).isFalse();
    }

    @Test
    @DisplayName("합계가 한도와 정확히 같으면(경계값) 초과가 아니므로 true를 반환한다")
    void isWithinSellLimitReturnsTrueWhenTotalExactlyEqualsLimit() {
        stubAccount(100L, "50000000", "20000000", "ci-hash-1");
        when(mydataClient.getExternalSellTotal("ci-hash-1")).thenReturn(new BigDecimal("10000000"));

        assertThat(sellLimitService.isWithinSellLimit(100L, new BigDecimal("20000000"))).isTrue();
    }

    @Test
    @DisplayName("계좌 로컬 한도 판정은 한도 사용액+이번 주문금액만 정확히 합산하고, 외부 순매수는 포함하지 않는다")
    void isWithinSellLimitComparesOnlyLocalSumAgainstAccountLimit() {
        stubAccount(100L, "3500", "3500", "ci-hash-1");
        when(mydataClient.getExternalSellTotal("ci-hash-1")).thenReturn(new BigDecimal("40000000"));

        assertThat(sellLimitService.isWithinSellLimit(100L, BigDecimal.ZERO)).isTrue();
        assertThat(sellLimitService.isWithinSellLimit(100L, new BigDecimal("1"))).isFalse();
    }

    @Test
    @DisplayName("계좌 로컬 한도 이내여도 한도 사용액+외부순매수+이번 주문금액 합이 5천만원 하드캡을 넘으면 거부한다")
    void isWithinSellLimitReturnsFalseWhenGlobalCapExceededEvenWithinAccountLimit() {
        stubAccount(100L, "50000000", "0", "ci-hash-1");
        when(mydataClient.getExternalSellTotal("ci-hash-1")).thenReturn(new BigDecimal("40000000"));

        assertThat(sellLimitService.isWithinSellLimit(100L, new BigDecimal("10000000"))).isTrue();
        assertThat(sellLimitService.isWithinSellLimit(100L, new BigDecimal("10000001"))).isFalse();
    }

    @Test
    @DisplayName("다른 증권사에서 이미 판 금액이 있어도, 이 계좌 로컬 한도와 5천만원 하드캡을 둘 다 넘지 않으면 승인한다 (버그 이슈 재현 시나리오)")
    void isWithinSellLimitApprovesWhenBothLocalAndGlobalLimitsAreRespectedDespiteExternalUsage() {
        stubAccount(100L, "30000000", "0", "ci-hash-1");
        when(mydataClient.getExternalSellTotal("ci-hash-1")).thenReturn(new BigDecimal("15000000"));

        assertThat(sellLimitService.isWithinSellLimit(100L, new BigDecimal("25000000"))).isTrue();
    }

    @Test
    @DisplayName("RIA 확정산이 0이어도 미확정(EXECUTED) 매도주문 합계만으로 한도초과를 잡아낸다")
    void isWithinSellLimitCatchesExcessFromPendingSellOrdersAloneWhenFinalizedSumIsZero() {
        stubAccount(100L, "50000000", "40000000", "ci-hash-1");
        when(mydataClient.getExternalSellTotal("ci-hash-1")).thenReturn(BigDecimal.ZERO);

        assertThat(sellLimitService.isWithinSellLimit(100L, new BigDecimal("10000000"))).isTrue();
        assertThat(sellLimitService.isWithinSellLimit(100L, new BigDecimal("10000001"))).isFalse();
    }

    @Test
    @DisplayName("계좌 한도 정보를 찾을 수 없으면 예외를 던지고 이후 조회/myData는 하지 않는다")
    void isWithinSellLimitThrowsWhenAccountLimitNotFound() {
        when(sellLimitMapper.selectAccountLimitForUpdate(100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sellLimitService.isWithinSellLimit(100L, new BigDecimal("1000")))
                .isInstanceOf(AppException.class)
                .hasMessage("계좌 한도 정보를 찾을 수 없습니다.");

        verify(sellLimitMapper, never()).sumUsedAmount(any());
        verifyNoInteractions(mydataClient);
    }

    @Test
    @DisplayName("ci_hash를 찾을 수 없으면 예외를 던지고 myData는 조회하지 않는다")
    void isWithinSellLimitThrowsWhenCiHashNotFound() {
        when(sellLimitMapper.selectAccountLimitForUpdate(100L))
                .thenReturn(Optional.of(new BigDecimal("50000000")));
        when(sellLimitMapper.sumUsedAmount(100L)).thenReturn(new BigDecimal("0"));
        when(sellLimitMapper.selectCiHashByAccountId(100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sellLimitService.isWithinSellLimit(100L, new BigDecimal("1000")))
                .isInstanceOf(AppException.class)
                .hasMessage("고객 정보를 확인할 수 없습니다.");

        verifyNoInteractions(mydataClient);
    }
}
