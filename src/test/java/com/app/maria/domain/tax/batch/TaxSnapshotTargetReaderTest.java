package com.app.maria.domain.tax.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.type.BenefitType;
import com.app.maria.domain.tax.dto.ExternalBuyDTO;
import com.app.maria.domain.tax.dto.SellLotDTO;
import com.app.maria.domain.tax.dto.TaxSnapshotTargetDTO;
import com.app.maria.domain.tax.mapper.TaxMapper;
import com.app.maria.global.config.properties.RiaTaxProperties;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TaxSnapshotTargetReaderTest {

    private static final int TAX_YEAR = 2026;
    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 8, 13, 2, 0);
    private static final int PAGE_SIZE = 200;

    @Mock private AccountMapper accountMapper;
    @Mock private TaxMapper taxMapper;
    @Mock private RiaTaxProperties riaTaxProperties;

    private TaxSnapshotTargetReader reader;

    @BeforeEach
    void setUp() {
        reader = new TaxSnapshotTargetReader(accountMapper, taxMapper, riaTaxProperties);
        ReflectionTestUtils.setField(reader, "calculatedAt", CALCULATED_AT);
        lenient().when(riaTaxProperties.getYear()).thenReturn(TAX_YEAR);
        reader.open(new ExecutionContext());
    }

    private AccountDTO account(long id, BenefitType benefit) {
        return AccountDTO.builder().accountId(id).benefit(benefit).build();
    }

    @Test
    @DisplayName("각 계좌는 자기 lot·외부거래만 받고, 없으면 빈 목록을 받는다")
    void read_계좌별로_정확히_grouping된다() {
        when(accountMapper.selectOpenedAccountsAfter(0L, PAGE_SIZE))
                .thenReturn(
                        List.of(
                                account(10L, BenefitType.POSSIBLE),
                                account(20L, BenefitType.POSSIBLE)));
        SellLotDTO lotForA =
                SellLotDTO.builder().accountId(10L).finalAt(LocalDate.of(2026, 3, 10)).build();
        when(taxMapper.selectFinalizedLotsByAccountIdsAndYear(
                        List.of(10L, 20L), TAX_YEAR, CALCULATED_AT))
                .thenReturn(List.of(lotForA));
        ExternalBuyDTO externalForB =
                ExternalBuyDTO.builder()
                        .accountId(20L)
                        .tradeDate(LocalDate.of(2026, 6, 15))
                        .build();
        when(taxMapper.selectExternalBuysByAccountIdsAndYear(
                        List.of(10L, 20L), TAX_YEAR, CALCULATED_AT))
                .thenReturn(List.of(externalForB));

        TaxSnapshotTargetDTO targetA = reader.read();
        TaxSnapshotTargetDTO targetB = reader.read();

        assertThat(targetA.getAccount().getAccountId()).isEqualTo(10L);
        assertThat(targetA.getSellLots()).containsExactly(lotForA);
        assertThat(targetA.getExternalTrades()).isEmpty();

        assertThat(targetB.getAccount().getAccountId()).isEqualTo(20L);
        assertThat(targetB.getSellLots()).isEmpty();
        assertThat(targetB.getExternalTrades()).containsExactly(externalForB);
    }

    @Test
    @DisplayName("한 페이지를 다 읽으면 방금 소비한 마지막 계좌 id로 다음 페이지를 요청한다")
    void read_다음페이지는_직전페이지_마지막id로_요청한다() {
        when(accountMapper.selectOpenedAccountsAfter(0L, PAGE_SIZE))
                .thenReturn(
                        List.of(
                                account(10L, BenefitType.POSSIBLE),
                                account(20L, BenefitType.POSSIBLE)));
        when(taxMapper.selectFinalizedLotsByAccountIdsAndYear(
                        List.of(10L, 20L), TAX_YEAR, CALCULATED_AT))
                .thenReturn(List.of());
        when(taxMapper.selectExternalBuysByAccountIdsAndYear(
                        List.of(10L, 20L), TAX_YEAR, CALCULATED_AT))
                .thenReturn(List.of());
        when(accountMapper.selectOpenedAccountsAfter(20L, PAGE_SIZE))
                .thenReturn(List.of(account(30L, BenefitType.POSSIBLE)));
        when(taxMapper.selectFinalizedLotsByAccountIdsAndYear(
                        List.of(30L), TAX_YEAR, CALCULATED_AT))
                .thenReturn(List.of());
        when(taxMapper.selectExternalBuysByAccountIdsAndYear(List.of(30L), TAX_YEAR, CALCULATED_AT))
                .thenReturn(List.of());
        when(accountMapper.selectOpenedAccountsAfter(30L, PAGE_SIZE)).thenReturn(List.of());

        TaxSnapshotTargetDTO first = reader.read();
        TaxSnapshotTargetDTO second = reader.read();
        TaxSnapshotTargetDTO third = reader.read();
        TaxSnapshotTargetDTO fourth = reader.read();

        assertThat(first.getAccount().getAccountId()).isEqualTo(10L);
        assertThat(second.getAccount().getAccountId()).isEqualTo(20L);
        assertThat(third.getAccount().getAccountId()).isEqualTo(30L);
        assertThat(fourth).isNull();
        verify(accountMapper).selectOpenedAccountsAfter(eq(0L), eq(PAGE_SIZE));
        verify(accountMapper).selectOpenedAccountsAfter(eq(20L), eq(PAGE_SIZE));
        verify(accountMapper).selectOpenedAccountsAfter(eq(30L), eq(PAGE_SIZE));
    }

    @Test
    @DisplayName("계좌가 없으면 즉시 null을 반환하고 lot/외부거래는 조회하지 않는다")
    void read_계좌없으면_null_조회안함() {
        when(accountMapper.selectOpenedAccountsAfter(0L, PAGE_SIZE)).thenReturn(List.of());

        assertThat(reader.read()).isNull();

        verify(taxMapper, never())
                .selectFinalizedLotsByAccountIdsAndYear(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("open()은 ExecutionContext에 저장된 커서부터 재개한다")
    void open_저장된_커서부터_재개() {
        TaxSnapshotTargetReader restarted =
                new TaxSnapshotTargetReader(accountMapper, taxMapper, riaTaxProperties);
        ReflectionTestUtils.setField(restarted, "calculatedAt", CALCULATED_AT);
        ExecutionContext context = new ExecutionContext();
        context.putLong("tax.snapshot.lastAccountId", 500L);
        restarted.open(context);

        when(accountMapper.selectOpenedAccountsAfter(500L, PAGE_SIZE))
                .thenReturn(List.of(account(600L, BenefitType.POSSIBLE)));
        when(taxMapper.selectFinalizedLotsByAccountIdsAndYear(
                        List.of(600L), TAX_YEAR, CALCULATED_AT))
                .thenReturn(List.of());
        when(taxMapper.selectExternalBuysByAccountIdsAndYear(
                        List.of(600L), TAX_YEAR, CALCULATED_AT))
                .thenReturn(List.of());

        TaxSnapshotTargetDTO target = restarted.read();

        assertThat(target.getAccount().getAccountId()).isEqualTo(600L);
        verify(accountMapper, never()).selectOpenedAccountsAfter(eq(0L), eq(PAGE_SIZE));
    }

    @Test
    @DisplayName("update()는 버퍼에 남은 계좌가 아니라 실제로 소비한 마지막 계좌를 기록한다")
    void update_소비한_계좌까지만_커서를_전진시킨다() {
        when(accountMapper.selectOpenedAccountsAfter(0L, PAGE_SIZE))
                .thenReturn(
                        List.of(
                                account(10L, BenefitType.POSSIBLE),
                                account(20L, BenefitType.POSSIBLE)));
        when(taxMapper.selectFinalizedLotsByAccountIdsAndYear(
                        List.of(10L, 20L), TAX_YEAR, CALCULATED_AT))
                .thenReturn(List.of());
        when(taxMapper.selectExternalBuysByAccountIdsAndYear(
                        List.of(10L, 20L), TAX_YEAR, CALCULATED_AT))
                .thenReturn(List.of());

        TaxSnapshotTargetDTO onlyFirst = reader.read();
        assertThat(onlyFirst.getAccount().getAccountId()).isEqualTo(10L);

        ExecutionContext context = new ExecutionContext();
        reader.update(context);

        assertThat(context.getLong("tax.snapshot.lastAccountId")).isEqualTo(10L);
    }

    @Test
    @DisplayName("과세연도는 페이지마다 새로 계산하지 않고 RiaTaxProperties에서 한 번씩 읽는다")
    void read_과세연도는_설정값을_그대로_사용() {
        when(accountMapper.selectOpenedAccountsAfter(0L, PAGE_SIZE))
                .thenReturn(List.of(account(10L, BenefitType.POSSIBLE)));
        when(taxMapper.selectFinalizedLotsByAccountIdsAndYear(
                        List.of(10L), TAX_YEAR, CALCULATED_AT))
                .thenReturn(List.of());
        when(taxMapper.selectExternalBuysByAccountIdsAndYear(List.of(10L), TAX_YEAR, CALCULATED_AT))
                .thenReturn(List.of());

        reader.read();

        verify(riaTaxProperties, times(1)).getYear();
    }
}
