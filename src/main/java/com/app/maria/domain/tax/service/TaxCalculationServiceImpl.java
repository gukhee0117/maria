package com.app.maria.domain.tax.service;

import com.app.maria.domain.account.dto.AccountBenefitLogDTO;
import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountBenefitLogMapper;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.type.BenefitType;
import com.app.maria.domain.settlement.component.SettlementBusinessDayCalculator;
import com.app.maria.domain.tax.batch.TaxSnapshotBatchHistoryReader;
import com.app.maria.domain.tax.batch.TaxSnapshotJobLauncher;
import com.app.maria.domain.tax.dto.ExternalBuyDTO;
import com.app.maria.domain.tax.dto.HeldLotDTO;
import com.app.maria.domain.tax.dto.SellLotDTO;
import com.app.maria.domain.tax.dto.TaxBreakdownDTO;
import com.app.maria.domain.tax.dto.TaxCalculationDTO;
import com.app.maria.domain.tax.dto.TaxCalculationResultDTO;
import com.app.maria.domain.tax.dto.TaxPeriodBreakdownDTO;
import com.app.maria.domain.tax.dto.TaxRuleDTO;
import com.app.maria.domain.tax.dto.response.CustomerTaxBasisResponseDTO;
import com.app.maria.domain.tax.dto.response.CustomerTaxBriefingResponseDTO;
import com.app.maria.domain.tax.dto.response.TaxActionSummaryResponseDTO;
import com.app.maria.domain.tax.dto.response.TaxBatchHistoryResponseDTO;
import com.app.maria.domain.tax.dto.response.TaxCalculationPreviewResponseDTO;
import com.app.maria.domain.tax.dto.response.TaxCalculationSaveResponseDTO;
import com.app.maria.domain.tax.dto.response.TaxExpectedReliefResponseDTO;
import com.app.maria.domain.tax.dto.response.TaxSnapshotBatchResultResponseDTO;
import com.app.maria.domain.tax.dto.response.TaxSnapshotResponseDTO;
import com.app.maria.domain.tax.mapper.TaxMapper;
import com.app.maria.domain.tax.mapper.TaxSnapshotMapper;
import com.app.maria.domain.tax.type.TaxAuditLogReasonCode;
import com.app.maria.domain.tax.type.TaxBasisType;
import com.app.maria.global.audit.dto.AuditLogDTO;
import com.app.maria.global.audit.provider.AuditActorProvider;
import com.app.maria.global.audit.service.AuditLogService;
import com.app.maria.global.client.exchange.ExchangeRateClient;
import com.app.maria.global.client.kis.KisExchangeCode;
import com.app.maria.global.client.kis.KisPriceClient;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.properties.RiaTaxProperties;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class TaxCalculationServiceImpl implements TaxCalculationService {
    private final TaxMapper taxMapper;
    private final AccountMapper accountMapper;
    private final AccountBenefitLogMapper accountBenefitLogMapper;
    private final BusinessClockService clockService;
    private final RiaTaxProperties riaTaxProperties;
    private final TaxCalculator taxCalculator;
    private final TaxBreakdownAssembler taxBreakdownAssembler;
    private final TaxSnapshotMapper taxSnapshotMapper;
    private final TaxSnapshotJobLauncher taxSnapshotJobLauncher;
    private final TaxSnapshotBatchHistoryReader taxSnapshotBatchHistoryReader;
    private final AuditLogService auditLogService;
    private final AuditActorProvider auditActorProvider;
    private final KisPriceClient kisPriceClient;
    private final ExchangeRateClient exchangeRateClient;
    private final SettlementBusinessDayCalculator settlementBusinessDayCalculator;
    private final TaxExpectedSnapshotReader expectedSnapshotReader;

    @Override
    @Transactional(readOnly = true)
    public TaxCalculationPreviewResponseDTO taxCalculate(Long accountId) {
        AccountDTO account = findAccount(accountId);
        Optional<AccountBenefitLogDTO> latestBenefitLog =
                accountBenefitLogMapper.selectLatestByAccountId(accountId);

        TaxCalculationInputs inputs = loadInputs(account);
        TaxCalculationResultDTO result =
                taxCalculator.calculate(
                        inputs.sellLots(),
                        inputs.taxRules(),
                        inputs.externalTrades(),
                        BenefitType.isReliefExcluded(account.getBenefit()));
        TaxBreakdownDTO breakdown =
                taxBreakdownAssembler.assemble(
                        inputs.sellLots(), inputs.externalTrades(), inputs.taxRules());

        return TaxCalculationPreviewResponseDTO.of(
                accountId,
                result,
                breakdown,
                latestBenefitLog.map(AccountBenefitLogDTO::getReason).orElse(null),
                latestBenefitLog.map(AccountBenefitLogDTO::getChangedAt).orElse(null),
                taxMapper.selectLatestCalculation(accountId).orElse(null));
    }

    @Override
    @Transactional
    public TaxCalculationSaveResponseDTO calculateAndSave(Long accountId) {
        AccountDTO account = findAccount(accountId);
        TaxBasisType basisType = resolveBasisType(account);

        TaxCalculationDTO taxCalculationDTO =
                TaxCalculationDTO.of(
                        accountId, basisType, clockService.now(), calculateFor(account));

        try {
            taxMapper.insertCalculation(taxCalculationDTO);
        } catch (DuplicateKeyException e) {
            throw new AppException(alreadyExistsErrorType(basisType), accountId);
        }

        return TaxCalculationSaveResponseDTO.of(taxCalculationDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaxSnapshotResponseDTO> findSnapshots(List<Long> accountIds) {
        return taxSnapshotMapper.selectByAccountIds(accountIds).stream()
                .map(TaxSnapshotResponseDTO::of)
                .toList();
    }

    @Override
    public TaxSnapshotBatchResultResponseDTO triggerSnapshotBatch() {
        String runId = UUID.randomUUID().toString();
        taxSnapshotJobLauncher.launchAsync(clockService.now(), runId);

        auditLogService.log(
                AuditLogDTO.builder()
                        .adminId(auditActorProvider.getCurrentAdminId())
                        .targetTable("TAX_SNAPSHOT_BATCH")
                        .targetPk(runId)
                        .afterValue("REQUESTED")
                        .reasonCode(TaxAuditLogReasonCode.TAX_SNAPSHOT_BATCH_REQUESTED.name())
                        .build());

        return TaxSnapshotBatchResultResponseDTO.of(runId);
    }

    @Override
    public List<TaxBatchHistoryResponseDTO> getRecentBatchHistory() {
        return taxSnapshotBatchHistoryReader.findRecent().stream()
                .map(TaxBatchHistoryResponseDTO::of)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TaxExpectedReliefResponseDTO previewExpectedRelief(Long accountId) {
        AccountDTO account = findAccount(accountId);
        LocalDateTime asOf = clockService.now();
        List<HeldLotDTO> heldLots = taxMapper.selectHeldLotsByAccountId(accountId);
        TaxCalculationInputs inputs =
                new TaxCalculationInputs(
                        List.of(),
                        taxMapper.selectTaxRules(),
                        taxMapper.selectExternalBuysByAccountIdsAndYear(
                                List.of(accountId), riaTaxProperties.getYear(), asOf));
        return calculateExpectedRelief(account, heldLots, asOf, inputs, false).expected();
    }

    private ExpectedCalculation calculateExpectedRelief(
            AccountDTO account,
            List<HeldLotDTO> heldLots,
            LocalDateTime asOf,
            TaxCalculationInputs inputs,
            boolean includeFinalized) {
        Long accountId = account.getAccountId();

        LocalDate expectedFinalAt =
                settlementBusinessDayCalculator.calculateFinalAt(asOf).toLocalDate();
        List<SellLotDTO> hypotheticalSellLots =
                buildHypotheticalSellLots(accountId, heldLots, expectedFinalAt);

        List<SellLotDTO> combinedLots = new ArrayList<>(inputs.sellLots());
        combinedLots.addAll(hypotheticalSellLots);

        TaxCalculationResultDTO result =
                taxCalculator.calculate(
                        combinedLots,
                        inputs.taxRules(),
                        inputs.externalTrades(),
                        BenefitType.isReliefExcluded(account.getBenefit()));

        TaxCalculationResultDTO finalized =
                includeFinalized
                        ? taxCalculator.calculate(
                                inputs.sellLots(),
                                inputs.taxRules(),
                                inputs.externalTrades(),
                                BenefitType.isReliefExcluded(account.getBenefit()))
                        : null;
        return new ExpectedCalculation(
                TaxExpectedReliefResponseDTO.of(
                        accountId, expectedFinalAt, result, heldLots.size()),
                finalized,
                inputs.taxRules());
    }

    private record ExpectedCalculation(
            TaxExpectedReliefResponseDTO expected,
            TaxCalculationResultDTO finalized,
            List<TaxRuleDTO> rules) {}

    @Override
    @Transactional(readOnly = true)
    public TaxActionSummaryResponseDTO getActionSummary() {
        LocalDateTime now = clockService.now();
        LocalDateTime startOfToday = now.toLocalDate().atStartOfDay();

        return TaxActionSummaryResponseDTO.builder()
                .unconfirmedFinalReportCount(taxMapper.countUnconfirmedFinalReport())
                .unprocessedClawbackCount(taxMapper.countUnprocessedClawback())
                .benefitChangedTodayCount(taxMapper.countBenefitChangedSince(startOfToday, now))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerTaxBriefingResponseDTO getCustomerTaxBriefing(Long customerId) {
        AccountDTO account = findCustomerAccount(customerId);
        TaxSnapshotResponseDTO snapshot =
                findSnapshots(List.of(account.getAccountId())).stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorType.TAX_SNAPSHOT_NOT_FOUND,
                                                account.getAccountId()));
        var current = briefingScenario(snapshot.getOriginalGainAmount(), snapshot.getFinalTax());
        return CustomerTaxBriefingResponseDTO.of(
                account.getAccountId(), current, null, snapshot.getCalculatedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerTaxBasisResponseDTO getCustomerTaxBasis(Long customerId) {
        AccountDTO account = findCustomerAccount(customerId);
        TaxCalculationInputs inputs = loadInputs(account);
        TaxCalculationResultDTO result =
                taxCalculator.calculate(
                        inputs.sellLots(),
                        inputs.taxRules(),
                        inputs.externalTrades(),
                        BenefitType.isReliefExcluded(account.getBenefit()));
        TaxBreakdownDTO breakdown =
                taxBreakdownAssembler.assemble(
                        inputs.sellLots(), inputs.externalTrades(), inputs.taxRules());
        return CustomerTaxBasisResponseDTO.of(
                account.getAccountId(),
                account.getBenefit(),
                result,
                breakdown.getPeriodBreakdown().stream()
                        .filter(p -> overlapsYear(p, riaTaxProperties.getYear()))
                        .toList(),
                taxCalculator.basicDeduction(inputs.taxRules()),
                taxCalculator.calculateGeneralTax(
                        result.getOriginalGainAmount(), inputs.taxRules()),
                clockService.now());
    }

    @Override
    public CustomerTaxBriefingResponseDTO.ExpectedTaxScenario getCustomerExpectedTaxBriefing(
            Long customerId) {
        var snapshot = expectedSnapshotReader.readCustomer(customerId);
        ExpectedCalculation calculation =
                calculateExpectedRelief(
                        snapshot.account(),
                        snapshot.heldLots(),
                        snapshot.asOf(),
                        new TaxCalculationInputs(
                                snapshot.finalizedLots(),
                                snapshot.rules(),
                                snapshot.externalTrades()),
                        true);
        TaxExpectedReliefResponseDTO expected = calculation.expected();
        var result = expected.getTaxCalculationResultDTO();
        var scenario =
                briefingScenario(
                        result.getOriginalGainAmount(), result.getFinalTax(), calculation.rules());
        var finalized =
                Objects.requireNonNull(
                        calculation.finalized(),
                        "Customer projection requires finalized calculation result");
        var finalizedScenario =
                briefingScenario(
                        finalized.getOriginalGainAmount(),
                        finalized.getFinalTax(),
                        calculation.rules());
        return CustomerTaxBriefingResponseDTO.ExpectedTaxScenario.builder()
                .generalTax(scenario.getGeneralTax())
                .riaTax(scenario.getRiaTax())
                .reliefAmount(scenario.getReliefAmount())
                .finalizedReliefAmount(finalizedScenario.getReliefAmount())
                .additionalReliefAmount(
                        scenario.getReliefAmount().subtract(finalizedScenario.getReliefAmount()))
                .expectedFinalAt(expected.getExpectedFinalAt())
                .holdingCount(expected.getHoldingCount())
                .build();
    }

    /** 다른 연도의 RELIEF_RATE 규칙 행이 섞여 있어도 세액 연도와 겹치는 구간만 근거로 보여 준다. */
    private static boolean overlapsYear(TaxPeriodBreakdownDTO period, int year) {
        return period.getValidFrom().getYear() <= year && year <= period.getValidTo().getYear();
    }

    private AccountDTO findCustomerAccount(Long customerId) {
        return accountMapper
                .selectByCustomerId(customerId)
                .orElseThrow(() -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, customerId));
    }

    private CustomerTaxBriefingResponseDTO.TaxScenario briefingScenario(
            BigDecimal originalGain, BigDecimal riaTax) {
        return briefingScenario(originalGain, riaTax, taxMapper.selectTaxRules());
    }

    private CustomerTaxBriefingResponseDTO.TaxScenario briefingScenario(
            BigDecimal originalGain, BigDecimal riaTax, List<TaxRuleDTO> rules) {
        BigDecimal generalTax = taxCalculator.calculateGeneralTax(originalGain, rules);
        return CustomerTaxBriefingResponseDTO.TaxScenario.of(
                generalTax, riaTax, generalTax.subtract(riaTax).max(BigDecimal.ZERO));
    }

    // 같은 종목이 여러 lot으로 흩어져 있어도 현재가/환율 조회는 종목당 한 번만 한다.
    private List<SellLotDTO> buildHypotheticalSellLots(
            Long accountId, List<HeldLotDTO> heldLots, LocalDate expectedFinalAt) {
        Map<String, BigDecimal> priceByTicker = new HashMap<>();
        Map<String, BigDecimal> fxRateByCurrency = new HashMap<>();
        List<SellLotDTO> sellLots = new ArrayList<>();

        for (HeldLotDTO lot : heldLots) {
            BigDecimal previousClose =
                    priceByTicker.computeIfAbsent(
                            lot.getTicker(),
                            ticker ->
                                    kisPriceClient.getPreviousClose(
                                            KisExchangeCode.fromMarket(lot.getMarket()), ticker));
            BigDecimal exchangeRate =
                    fxRateByCurrency.computeIfAbsent(
                            lot.getCurrency(), exchangeRateClient::getBaseRate);
            BigDecimal finalAmount =
                    lot.getCurrentQty().multiply(previousClose).multiply(exchangeRate);

            sellLots.add(
                    SellLotDTO.builder()
                            .accountId(accountId)
                            .inboundDetailId(lot.getInboundDetailId())
                            .purchaseFxRate(lot.getPurchaseFxRate())
                            .purchasePrice(lot.getPurchasePrice())
                            .sellQty(lot.getCurrentQty())
                            .finalAt(expectedFinalAt)
                            .finalAmount(finalAmount)
                            .productLabel(lot.getProductLabel())
                            .build());
        }
        return sellLots;
    }

    private TaxBasisType resolveBasisType(AccountDTO account) {
        Long accountId = account.getAccountId();

        if (!taxMapper.existsByAccountAndBasis(accountId, TaxBasisType.FINAL_REPORT)) {
            return TaxBasisType.FINAL_REPORT;
        }
        if (account.getBenefit() != BenefitType.IMPOSSIBLE) {
            throw new AppException(ErrorType.TAX_FINAL_REPORT_ALREADY_EXISTS, accountId);
        }
        if (taxMapper.existsByAccountAndBasis(accountId, TaxBasisType.EARLY_WITHDRAWAL_CLAWBACK)) {
            throw new AppException(
                    ErrorType.TAX_EARLY_WITHDRAWAL_CLAWBACK_ALREADY_EXISTS, accountId);
        }
        return TaxBasisType.EARLY_WITHDRAWAL_CLAWBACK;
    }

    private ErrorType alreadyExistsErrorType(TaxBasisType basisType) {
        return basisType == TaxBasisType.FINAL_REPORT
                ? ErrorType.TAX_FINAL_REPORT_ALREADY_EXISTS
                : ErrorType.TAX_EARLY_WITHDRAWAL_CLAWBACK_ALREADY_EXISTS;
    }

    private AccountDTO findAccount(Long accountId) {
        return accountMapper
                .selectByAccountId(accountId)
                .orElseThrow(() -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, accountId));
    }

    private TaxCalculationResultDTO calculateFor(AccountDTO account) {
        TaxCalculationInputs inputs = loadInputs(account);
        return taxCalculator.calculate(
                inputs.sellLots(),
                inputs.taxRules(),
                inputs.externalTrades(),
                BenefitType.isReliefExcluded(account.getBenefit()));
    }

    private TaxCalculationInputs loadInputs(AccountDTO account) {
        int taxYear = riaTaxProperties.getYear();
        List<Long> accountIds = List.of(account.getAccountId());
        LocalDateTime now = clockService.now();

        List<SellLotDTO> sellLots =
                taxMapper.selectFinalizedLotsByAccountIdsAndYear(accountIds, taxYear, now);
        List<TaxRuleDTO> taxRules = taxMapper.selectTaxRules();
        List<ExternalBuyDTO> externalTrades =
                taxMapper.selectExternalBuysByAccountIdsAndYear(accountIds, taxYear, now);

        return new TaxCalculationInputs(sellLots, taxRules, externalTrades);
    }

    private record TaxCalculationInputs(
            List<SellLotDTO> sellLots,
            List<TaxRuleDTO> taxRules,
            List<ExternalBuyDTO> externalTrades) {}
}
