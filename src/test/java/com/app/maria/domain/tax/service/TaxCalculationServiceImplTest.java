package com.app.maria.domain.tax.service;

import static com.app.maria.domain.tax.fixture.TaxFixtures.allSeedRules;
import static com.app.maria.domain.tax.fixture.TaxFixtures.externalBuy;
import static com.app.maria.domain.tax.fixture.TaxFixtures.lot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.exception.AccountNotFoundException;
import com.app.maria.domain.account.mapper.AccountBenefitLogMapper;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.type.BenefitType;
import com.app.maria.domain.tax.batch.TaxSnapshotJobLauncher;
import com.app.maria.domain.tax.dto.ExternalBuyDTO;
import com.app.maria.domain.tax.dto.SellLotDTO;
import com.app.maria.domain.tax.dto.TaxCalculationDTO;
import com.app.maria.domain.tax.dto.TaxRuleDTO;
import com.app.maria.domain.tax.dto.TaxSnapshotDTO;
import com.app.maria.domain.tax.dto.response.TaxCalculationPreviewResponseDTO;
import com.app.maria.domain.tax.dto.response.TaxCalculationSaveResponseDTO;
import com.app.maria.domain.tax.dto.response.TaxSnapshotResponseDTO;
import com.app.maria.domain.tax.exception.TaxCalculationAlreadyExistsException;
import com.app.maria.domain.tax.mapper.TaxMapper;
import com.app.maria.domain.tax.mapper.TaxSnapshotMapper;
import com.app.maria.domain.tax.type.TaxAuditLogReasonCode;
import com.app.maria.domain.tax.type.TaxBasisType;
import com.app.maria.global.audit.dto.AuditLogDTO;
import com.app.maria.global.audit.provider.AuditActorProvider;
import com.app.maria.global.audit.service.AuditLogService;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.properties.RiaTaxProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class TaxCalculationServiceImplTest {

    private static final Long ACCOUNT_ID = 1L;
    private static final int TAX_YEAR = 2026;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 4, 9, 0);

    @Mock TaxMapper taxMapper;

    @Mock AccountMapper accountMapper;

    @Mock AccountBenefitLogMapper accountBenefitLogMapper;

    @Mock BusinessClockService clockService;

    @Mock RiaTaxProperties riaTaxProperties;

    @Mock TaxSnapshotMapper taxSnapshotMapper;

    @Mock TaxSnapshotJobLauncher taxSnapshotJobLauncher;

    @Mock AuditLogService auditLogService;

    @Mock AuditActorProvider auditActorProvider;

    @Spy TaxCalculator taxCalculator = new TaxCalculator();

    @InjectMocks TaxCalculationServiceImpl taxCalculationService;

    @Test
    @DisplayName("조회한 lot과 규칙을 계산기에 넘겨 결과를 응답으로 감싼다")
    void 정상_계산() {
        stubAccount();
        stubTaxYearAndClock();
        when(taxMapper.findFinalizedLotsByAccountIdsAndYear(List.of(ACCOUNT_ID), TAX_YEAR, NOW))
                .thenReturn(
                        List.of(lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100")));
        when(taxMapper.findTaxRules()).thenReturn(allSeedRules());
        when(taxMapper.findExternalBuysByAccountIdsAndYear(List.of(ACCOUNT_ID), TAX_YEAR, NOW))
                .thenReturn(List.of(externalBuy(LocalDate.of(2026, 6, 15), "10000000")));

        TaxCalculationPreviewResponseDTO response = taxCalculationService.taxCalculate(ACCOUNT_ID);

        assertThat(response.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(response.getTaxCalculationResultDTO().getWeightedSell())
                .isEqualByComparingTo("30000000");
        assertThat(response.getTaxCalculationResultDTO().getWeightedGain())
                .isEqualByComparingTo("20000000");
        assertThat(response.getTaxCalculationResultDTO().getOriginalGainAmount())
                .isEqualByComparingTo("20000000");
        assertThat(response.getTaxCalculationResultDTO().getWeightedExternalAmount())
                .isEqualByComparingTo("8000000");
        assertThat(response.getTaxCalculationResultDTO().getAdjustRatio())
                .isEqualByComparingTo("0.7333");
        assertThat(response.getTaxCalculationResultDTO().getFinalDeduction())
                .isEqualByComparingTo("14666000.00");
        assertThat(response.getTaxCalculationResultDTO().getFinalTax())
                .isEqualByComparingTo("623480.00");
    }

    @Test
    @DisplayName("계좌가 없으면 예외를 던지고 이후 조회를 하지 않는다")
    void 계좌없음() {
        when(accountMapper.selectByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taxCalculationService.taxCalculate(ACCOUNT_ID))
                .isInstanceOf(AccountNotFoundException.class);

        verifyNoInteractions(taxMapper, taxCalculator);
        verify(clockService, never()).now();
    }

    @Test
    @DisplayName("과세연도와 업무 기준시각을 lot 조회 조건으로 그대로 전달한다")
    void 조회조건_전달() {
        stubAccount();
        stubTaxYearAndClock();
        when(taxMapper.findFinalizedLotsByAccountIdsAndYear(anyList(), anyInt(), any()))
                .thenReturn(List.of());
        when(taxMapper.findTaxRules()).thenReturn(allSeedRules());
        when(taxMapper.findExternalBuysByAccountIdsAndYear(anyList(), anyInt(), any()))
                .thenReturn(List.of());

        taxCalculationService.taxCalculate(ACCOUNT_ID);

        verify(taxMapper).findFinalizedLotsByAccountIdsAndYear(List.of(ACCOUNT_ID), TAX_YEAR, NOW);
        verify(taxMapper).findExternalBuysByAccountIdsAndYear(List.of(ACCOUNT_ID), TAX_YEAR, NOW);
    }

    @Test
    @DisplayName("조회 결과를 가공 없이 계산기에 전달한다")
    void 계산기_인자_전달() {
        stubAccount();
        stubTaxYearAndClock();
        List<SellLotDTO> lots =
                List.of(lot(LocalDate.of(2026, 6, 15), "10000000", "100", "1000", "40"));
        List<TaxRuleDTO> rules = allSeedRules();
        List<ExternalBuyDTO> external = List.of(externalBuy(LocalDate.of(2026, 3, 10), "5000000"));
        when(taxMapper.findFinalizedLotsByAccountIdsAndYear(List.of(ACCOUNT_ID), TAX_YEAR, NOW))
                .thenReturn(lots);
        when(taxMapper.findTaxRules()).thenReturn(rules);
        when(taxMapper.findExternalBuysByAccountIdsAndYear(List.of(ACCOUNT_ID), TAX_YEAR, NOW))
                .thenReturn(external);

        taxCalculationService.taxCalculate(ACCOUNT_ID);

        ArgumentCaptor<List<SellLotDTO>> lotCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<TaxRuleDTO>> ruleCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<ExternalBuyDTO>> externalCaptor = ArgumentCaptor.forClass(List.class);
        verify(taxCalculator)
                .calculate(
                        lotCaptor.capture(),
                        ruleCaptor.capture(),
                        externalCaptor.capture(),
                        anyBoolean());

        assertThat(lotCaptor.getValue()).isSameAs(lots);
        assertThat(ruleCaptor.getValue()).isSameAs(rules);
        assertThat(externalCaptor.getValue()).isSameAs(external);
    }

    @Test
    @DisplayName("매도 이력이 없으면 전부 0으로 응답한다")
    void 매도없음() {
        stubAccount();
        stubTaxYearAndClock();
        when(taxMapper.findFinalizedLotsByAccountIdsAndYear(List.of(ACCOUNT_ID), TAX_YEAR, NOW))
                .thenReturn(List.of());
        when(taxMapper.findTaxRules()).thenReturn(allSeedRules());
        when(taxMapper.findExternalBuysByAccountIdsAndYear(List.of(ACCOUNT_ID), TAX_YEAR, NOW))
                .thenReturn(List.of());

        TaxCalculationPreviewResponseDTO response = taxCalculationService.taxCalculate(ACCOUNT_ID);

        assertThat(response.getTaxCalculationResultDTO().getWeightedSell())
                .isEqualByComparingTo("0");
        assertThat(response.getTaxCalculationResultDTO().getWeightedGain())
                .isEqualByComparingTo("0");
        assertThat(response.getTaxCalculationResultDTO().getOriginalGainAmount())
                .isEqualByComparingTo("0");
        assertThat(response.getTaxCalculationResultDTO().getWeightedExternalAmount())
                .isEqualByComparingTo("0");
    }

    private void stubAccount() {
        stubAccount(null);
    }

    private void stubAccount(BenefitType benefit) {
        when(accountMapper.selectByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(account(benefit)));
        lenient()
                .when(accountBenefitLogMapper.selectLatestByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.empty());
    }

    private static AccountDTO account(BenefitType benefit) {
        AccountDTO account = new AccountDTO();
        account.setAccountId(ACCOUNT_ID);
        account.setBenefit(benefit);
        return account;
    }

    private void stubTaxYearAndClock() {
        when(riaTaxProperties.getYear()).thenReturn(TAX_YEAR);
        when(clockService.now()).thenReturn(NOW);
    }

    @Test
    @DisplayName("혜택 배제 계좌면 계산기에 배제 플래그를 넘긴다")
    void 혜택배제_전달() {
        stubAccount(BenefitType.IMPOSSIBLE);
        stubTaxYearAndClock();
        when(taxMapper.findFinalizedLotsByAccountIdsAndYear(List.of(ACCOUNT_ID), TAX_YEAR, NOW))
                .thenReturn(
                        List.of(lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100")));
        when(taxMapper.findTaxRules()).thenReturn(allSeedRules());
        when(taxMapper.findExternalBuysByAccountIdsAndYear(List.of(ACCOUNT_ID), TAX_YEAR, NOW))
                .thenReturn(List.of());

        TaxCalculationPreviewResponseDTO response = taxCalculationService.taxCalculate(ACCOUNT_ID);

        verify(taxCalculator).calculate(any(), any(), any(), eq(true));
        assertThat(response.getTaxCalculationResultDTO().getFinalDeduction())
                .isEqualByComparingTo("0");
        assertThat(response.getTaxCalculationResultDTO().getFinalTax())
                .isEqualByComparingTo("3850000.00");
    }

    @Test
    @DisplayName("혜택 상태가 POSSIBLE이면 배제 플래그는 false다")
    void 정상계좌는_배제아님() {
        stubAccount(BenefitType.POSSIBLE);
        stubTaxYearAndClock();
        when(taxMapper.findFinalizedLotsByAccountIdsAndYear(List.of(ACCOUNT_ID), TAX_YEAR, NOW))
                .thenReturn(
                        List.of(lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100")));
        when(taxMapper.findTaxRules()).thenReturn(allSeedRules());
        when(taxMapper.findExternalBuysByAccountIdsAndYear(List.of(ACCOUNT_ID), TAX_YEAR, NOW))
                .thenReturn(List.of());

        taxCalculationService.taxCalculate(ACCOUNT_ID);

        verify(taxCalculator).calculate(any(), any(), any(), eq(false));
    }

    @Test
    @DisplayName("혜택 상태가 없어도(null) 배제로 보지 않는다")
    void 혜택상태_null이면_배제아님() {
        stubAccount();
        stubTaxYearAndClock();
        when(taxMapper.findFinalizedLotsByAccountIdsAndYear(List.of(ACCOUNT_ID), TAX_YEAR, NOW))
                .thenReturn(
                        List.of(lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100")));
        when(taxMapper.findTaxRules()).thenReturn(allSeedRules());
        when(taxMapper.findExternalBuysByAccountIdsAndYear(List.of(ACCOUNT_ID), TAX_YEAR, NOW))
                .thenReturn(List.of());

        taxCalculationService.taxCalculate(ACCOUNT_ID);

        verify(taxCalculator).calculate(any(), any(), any(), eq(false));
    }

    private void stubGoldenCalculation() {
        stubTaxYearAndClock();
        when(taxMapper.findFinalizedLotsByAccountIdsAndYear(List.of(ACCOUNT_ID), TAX_YEAR, NOW))
                .thenReturn(
                        List.of(
                                lot(LocalDate.of(2026, 3, 10), "30000000", "100", "1000", "100"),
                                lot(LocalDate.of(2026, 6, 15), "10000000", "100", "1000", "40"),
                                lot(LocalDate.of(2026, 9, 20), "10000000", "100", "1000", "40")));
        when(taxMapper.findTaxRules()).thenReturn(allSeedRules());
        when(taxMapper.findExternalBuysByAccountIdsAndYear(List.of(ACCOUNT_ID), TAX_YEAR, NOW))
                .thenReturn(
                        List.of(
                                externalBuy(LocalDate.of(2026, 6, 15), "20000000"),
                                externalBuy(LocalDate.of(2026, 9, 20), "-10000000")));
    }

    private TaxCalculationDTO captureSaved() {
        ArgumentCaptor<TaxCalculationDTO> captor = ArgumentCaptor.forClass(TaxCalculationDTO.class);
        verify(taxMapper).insertCalculation(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("확정신고 행이 없으면 FINAL_REPORT로 저장한다")
    void 확정신고_저장() {
        stubAccount(BenefitType.POSSIBLE);
        stubGoldenCalculation();
        when(taxMapper.existsByAccountAndBasis(ACCOUNT_ID, TaxBasisType.FINAL_REPORT))
                .thenReturn(false);

        TaxCalculationSaveResponseDTO response = taxCalculationService.calculateAndSave(ACCOUNT_ID);

        assertThat(captureSaved().getBasisType()).isEqualTo(TaxBasisType.FINAL_REPORT);
        assertThat(response.getBasisType()).isEqualTo(TaxBasisType.FINAL_REPORT);
    }

    @Test
    @DisplayName("계산 결과가 저장 DTO의 각 컬럼으로 매핑된다")
    void 저장값_매핑() {
        stubAccount(BenefitType.POSSIBLE);
        stubGoldenCalculation();
        when(taxMapper.existsByAccountAndBasis(ACCOUNT_ID, TaxBasisType.FINAL_REPORT))
                .thenReturn(false);

        taxCalculationService.calculateAndSave(ACCOUNT_ID);

        TaxCalculationDTO saved = captureSaved();
        assertThat(saved.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(saved.getCalculatedAt()).isEqualTo(NOW);
        assertThat(saved.getWeightedSell()).isEqualByComparingTo("43000000");
        assertThat(saved.getOriginalGainAmount()).isEqualByComparingTo("32000000");
        assertThat(saved.getWeightedGain()).isEqualByComparingTo("27800000");
        assertThat(saved.getWeightedExternalAmount()).isEqualByComparingTo("11000000");
        assertThat(saved.getAdjustRatio()).isEqualByComparingTo("0.7442");
        assertThat(saved.getFinalDeduction()).isEqualByComparingTo("20688760.00");
        assertThat(saved.getFinalTax()).isEqualByComparingTo("1938472.80");
    }

    @Test
    @DisplayName("저장한 값이 그대로 응답으로 나간다")
    void 응답_매핑() {
        stubAccount(BenefitType.POSSIBLE);
        stubGoldenCalculation();
        when(taxMapper.existsByAccountAndBasis(ACCOUNT_ID, TaxBasisType.FINAL_REPORT))
                .thenReturn(false);

        TaxCalculationSaveResponseDTO response = taxCalculationService.calculateAndSave(ACCOUNT_ID);

        TaxCalculationDTO saved = captureSaved();
        assertThat(response.getAccountId()).isEqualTo(saved.getAccountId());
        assertThat(response.getCalculatedAt()).isEqualTo(saved.getCalculatedAt());
        assertThat(response.getAdjustRatio()).isEqualByComparingTo(saved.getAdjustRatio());
        assertThat(response.getFinalDeduction()).isEqualByComparingTo(saved.getFinalDeduction());
        assertThat(response.getFinalTax()).isEqualByComparingTo(saved.getFinalTax());
    }

    @Test
    @DisplayName("확정신고 후 혜택이 배제되면 EARLY_WITHDRAWAL_CLAWBACK으로 저장한다")
    void 조기인출_정정_저장() {
        stubAccount(BenefitType.IMPOSSIBLE);
        stubGoldenCalculation();
        when(taxMapper.existsByAccountAndBasis(ACCOUNT_ID, TaxBasisType.FINAL_REPORT))
                .thenReturn(true);
        when(taxMapper.existsByAccountAndBasis(ACCOUNT_ID, TaxBasisType.EARLY_WITHDRAWAL_CLAWBACK))
                .thenReturn(false);

        taxCalculationService.calculateAndSave(ACCOUNT_ID);

        TaxCalculationDTO saved = captureSaved();
        assertThat(saved.getBasisType()).isEqualTo(TaxBasisType.EARLY_WITHDRAWAL_CLAWBACK);
        // 혜택 배제라 공제 0, 세액은 감면 없는 값
        assertThat(saved.getAdjustRatio()).isEqualByComparingTo("0");
        assertThat(saved.getFinalDeduction()).isEqualByComparingTo("0");
        assertThat(saved.getFinalTax()).isEqualByComparingTo("6490000.00");
        // 매도 사실은 그대로 남는다
        assertThat(saved.getWeightedGain()).isEqualByComparingTo("27800000");
    }

    @Test
    @DisplayName("확정신고가 있는데 혜택이 유지 중이면 중복으로 보고 막는다")
    void 확정신고_중복() {
        stubAccount(BenefitType.POSSIBLE);
        when(taxMapper.existsByAccountAndBasis(ACCOUNT_ID, TaxBasisType.FINAL_REPORT))
                .thenReturn(true);

        assertThatThrownBy(() -> taxCalculationService.calculateAndSave(ACCOUNT_ID))
                .isInstanceOf(TaxCalculationAlreadyExistsException.class)
                .hasMessageContaining("확정신고");

        verify(taxMapper, never()).insertCalculation(any());
    }

    @Test
    @DisplayName("혜택이 축소(REDUCED)된 계좌도 확정신고가 있으면 막는다")
    void 확정신고_중복_reduced() {
        stubAccount(BenefitType.REDUCED);
        when(taxMapper.existsByAccountAndBasis(ACCOUNT_ID, TaxBasisType.FINAL_REPORT))
                .thenReturn(true);

        assertThatThrownBy(() -> taxCalculationService.calculateAndSave(ACCOUNT_ID))
                .isInstanceOf(TaxCalculationAlreadyExistsException.class);

        verify(taxMapper, never()).insertCalculation(any());
    }

    @Test
    @DisplayName("이미 정정 처리된 계좌는 다시 저장하지 않는다")
    void 정정_중복() {
        stubAccount(BenefitType.IMPOSSIBLE);
        when(taxMapper.existsByAccountAndBasis(ACCOUNT_ID, TaxBasisType.FINAL_REPORT))
                .thenReturn(true);
        when(taxMapper.existsByAccountAndBasis(ACCOUNT_ID, TaxBasisType.EARLY_WITHDRAWAL_CLAWBACK))
                .thenReturn(true);

        assertThatThrownBy(() -> taxCalculationService.calculateAndSave(ACCOUNT_ID))
                .isInstanceOf(TaxCalculationAlreadyExistsException.class)
                .hasMessageContaining("정정");

        verify(taxMapper, never()).insertCalculation(any());
    }

    @Test
    @DisplayName("계좌가 없으면 저장하지 않고 예외를 던진다")
    void 저장_계좌없음() {
        when(accountMapper.selectByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taxCalculationService.calculateAndSave(ACCOUNT_ID))
                .isInstanceOf(AccountNotFoundException.class);

        verify(taxMapper, never()).insertCalculation(any());
        verifyNoInteractions(taxCalculator);
    }

    @Test
    @DisplayName("미리보기는 저장하지 않는다")
    void 미리보기는_저장안함() {
        stubAccount(BenefitType.POSSIBLE);
        stubGoldenCalculation();

        taxCalculationService.taxCalculate(ACCOUNT_ID);

        verify(taxMapper, never()).insertCalculation(any());
        verify(taxMapper, never()).existsByAccountAndBasis(anyLong(), any());
    }

    @Test
    @DisplayName("동시 요청으로 UNIQUE 제약에 걸리면 500이 아니라 409로 바꾼다")
    void 동시요청_중복저장() {
        stubAccount(BenefitType.POSSIBLE);
        stubGoldenCalculation();
        // 판정 시점엔 없다고 보고 통과했지만, 그 사이 다른 요청이 먼저 저장한 상황
        when(taxMapper.existsByAccountAndBasis(ACCOUNT_ID, TaxBasisType.FINAL_REPORT))
                .thenReturn(false);
        doThrow(new DuplicateKeyException("uk_tax_calc__account_basis"))
                .when(taxMapper)
                .insertCalculation(any());

        assertThatThrownBy(() -> taxCalculationService.calculateAndSave(ACCOUNT_ID))
                .isInstanceOf(TaxCalculationAlreadyExistsException.class)
                .hasMessageContaining("FINAL_REPORT")
                .hasMessageContaining(String.valueOf(ACCOUNT_ID));
    }

    @Test
    @DisplayName("저장 중 다른 DB 예외는 그대로 전파한다")
    void 다른_DB예외는_그대로() {
        stubAccount(BenefitType.POSSIBLE);
        stubGoldenCalculation();
        when(taxMapper.existsByAccountAndBasis(ACCOUNT_ID, TaxBasisType.FINAL_REPORT))
                .thenReturn(false);
        doThrow(new DataIntegrityViolationException("not null 위반"))
                .when(taxMapper)
                .insertCalculation(any());

        assertThatThrownBy(() -> taxCalculationService.calculateAndSave(ACCOUNT_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("스냅샷 조회 결과를 필드별로 정확히 응답 DTO로 옮긴다(필드 뒤바뀜 방지)")
    void 스냅샷조회_필드매핑() {
        LocalDateTime calculatedAt = LocalDateTime.of(2026, 8, 14, 2, 0);
        when(taxSnapshotMapper.selectByAccountIds(List.of(1L, 2L)))
                .thenReturn(
                        List.of(
                                TaxSnapshotDTO.builder()
                                        .snapshotId(99L)
                                        .accountId(1L)
                                        .calculatedAt(calculatedAt)
                                        .weightedSell(new BigDecimal("10"))
                                        .originalGainAmount(new BigDecimal("20"))
                                        .weightedGain(new BigDecimal("30"))
                                        .weightedExternalAmount(new BigDecimal("40"))
                                        .adjustRatio(new BigDecimal("0.5"))
                                        .finalDeduction(new BigDecimal("60"))
                                        .finalTax(new BigDecimal("70"))
                                        .build()));

        List<TaxSnapshotResponseDTO> result = taxCalculationService.findSnapshots(List.of(1L, 2L));

        assertThat(result)
                .singleElement()
                .satisfies(
                        dto -> {
                            assertThat(dto.getAccountId()).isEqualTo(1L);
                            assertThat(dto.getCalculatedAt()).isEqualTo(calculatedAt);
                            assertThat(dto.getWeightedSell()).isEqualByComparingTo("10");
                            assertThat(dto.getOriginalGainAmount()).isEqualByComparingTo("20");
                            assertThat(dto.getWeightedGain()).isEqualByComparingTo("30");
                            assertThat(dto.getWeightedExternalAmount()).isEqualByComparingTo("40");
                            assertThat(dto.getAdjustRatio()).isEqualByComparingTo("0.5");
                            assertThat(dto.getFinalDeduction()).isEqualByComparingTo("60");
                            assertThat(dto.getFinalTax()).isEqualByComparingTo("70");
                        });
    }

    @Test
    @DisplayName("스냅샷이 없는 계좌는 빈 목록을 돌려준다")
    void 스냅샷조회_결과없음() {
        when(taxSnapshotMapper.selectByAccountIds(List.of(999L))).thenReturn(List.of());

        assertThat(taxCalculationService.findSnapshots(List.of(999L))).isEmpty();
    }

    @Test
    @DisplayName("응답을 기다리지 않고 배치를 비동기로 실행 요청하며, 감사 로그를 남긴다")
    void 배치_수동실행() {
        when(clockService.now()).thenReturn(NOW);
        when(auditActorProvider.getCurrentAdminId()).thenReturn(99L);

        var response = taxCalculationService.triggerSnapshotBatch();

        assertThat(response.getStatus()).isEqualTo("REQUESTED");
        assertThat(response.getRunId()).isNotBlank();

        ArgumentCaptor<String> runIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(taxSnapshotJobLauncher).launchAsync(eq(NOW), runIdCaptor.capture());
        assertThat(runIdCaptor.getValue()).isEqualTo(response.getRunId());

        ArgumentCaptor<AuditLogDTO> auditLogCaptor = ArgumentCaptor.forClass(AuditLogDTO.class);
        verify(auditLogService).log(auditLogCaptor.capture());
        AuditLogDTO auditLog = auditLogCaptor.getValue();
        assertThat(auditLog.getAdminId()).isEqualTo(99L);
        assertThat(auditLog.getTargetTable()).isEqualTo("TAX_SNAPSHOT_BATCH");
        assertThat(auditLog.getTargetPk()).isEqualTo(response.getRunId());
        assertThat(auditLog.getAfterValue()).isEqualTo("REQUESTED");
        assertThat(auditLog.getReasonCode())
                .isEqualTo(TaxAuditLogReasonCode.TAX_SNAPSHOT_BATCH_REQUESTED.name());
    }
}
