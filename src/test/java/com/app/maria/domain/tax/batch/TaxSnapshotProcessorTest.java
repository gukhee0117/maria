package com.app.maria.domain.tax.batch;

import static com.app.maria.domain.tax.fixture.TaxFixtures.allSeedRules;
import static com.app.maria.domain.tax.fixture.TaxFixtures.externalBuy;
import static com.app.maria.domain.tax.fixture.TaxFixtures.lot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.type.BenefitType;
import com.app.maria.domain.tax.dto.TaxSnapshotDTO;
import com.app.maria.domain.tax.dto.TaxSnapshotTargetDTO;
import com.app.maria.domain.tax.mapper.TaxMapper;
import com.app.maria.domain.tax.service.TaxCalculator;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TaxSnapshotProcessorTest {

    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 8, 13, 2, 0);

    @Mock private TaxMapper taxMapper;
    @Spy private TaxCalculator taxCalculator = new TaxCalculator();

    private TaxSnapshotProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new TaxSnapshotProcessor(taxCalculator, taxMapper);
        ReflectionTestUtils.setField(processor, "calculatedAt", CALCULATED_AT);
        when(taxMapper.selectTaxRules()).thenReturn(allSeedRules());
        processor.loadTaxRules(null);
    }

    private TaxSnapshotTargetDTO target(long accountId, BenefitType benefit) {
        AccountDTO account = AccountDTO.builder().accountId(accountId).benefit(benefit).build();
        return TaxSnapshotTargetDTO.of(
                account,
                List.of(lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100")),
                List.of(externalBuy(LocalDate.of(2026, 6, 15), "10000000")));
    }

    @Test
    @DisplayName("계좌·계산결과·기준시각을 그대로 스냅샷으로 옮긴다")
    void process_정상계좌_계산결과_매핑() {
        TaxSnapshotDTO snapshot = processor.process(target(1L, BenefitType.POSSIBLE));

        assertThat(snapshot.getAccountId()).isEqualTo(1L);
        assertThat(snapshot.getCalculatedAt()).isEqualTo(CALCULATED_AT);
        assertThat(snapshot.getWeightedSell()).isEqualByComparingTo("30000000");
        assertThat(snapshot.getWeightedGain()).isEqualByComparingTo("20000000");
        assertThat(snapshot.getOriginalGainAmount()).isEqualByComparingTo("20000000");
        assertThat(snapshot.getWeightedExternalAmount()).isEqualByComparingTo("8000000");
        assertThat(snapshot.getAdjustRatio()).isEqualByComparingTo("0.7333");
        assertThat(snapshot.getFinalDeduction()).isEqualByComparingTo("14666000.00");
        assertThat(snapshot.getFinalTax()).isEqualByComparingTo("623480.00");
    }

    @Test
    @DisplayName("IMPOSSIBLE 계좌는 매도 사실은 남기되 공제·세액감면은 0으로 계산한다")
    void process_혜택배제_계좌() {
        TaxSnapshotDTO snapshot = processor.process(target(1L, BenefitType.IMPOSSIBLE));

        assertThat(snapshot.getWeightedGain()).isEqualByComparingTo("20000000");
        assertThat(snapshot.getAdjustRatio()).isEqualByComparingTo("0");
        assertThat(snapshot.getFinalDeduction()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("benefit이 null(미분류)이어도 예외 없이 배제 아님으로 계산한다")
    void process_benefit_null이어도_NPE없이_배제아님() {
        TaxSnapshotDTO snapshot = processor.process(target(1L, null));

        assertThat(snapshot.getAdjustRatio()).isNotEqualByComparingTo("0");
    }

    @Test
    @DisplayName("tax_rule은 Step 시작 시 한 번만 읽고, 계좌마다 다시 조회하지 않는다")
    void process_taxRule은_계좌마다_재조회하지_않는다() {
        processor.process(target(1L, BenefitType.POSSIBLE));
        processor.process(target(2L, BenefitType.POSSIBLE));
        processor.process(target(3L, BenefitType.POSSIBLE));

        verify(taxMapper, times(1)).selectTaxRules();
    }
}
