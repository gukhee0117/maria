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
import com.app.maria.domain.tax.dto.TaxRuleDTO;
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
        List<HeldLotDTO> heldLots = taxMapper.selectHeldLotsByAccountId(accountId);

        LocalDate expectedFinalAt =
                settlementBusinessDayCalculator.calculateFinalAt(clockService.now()).toLocalDate();
        List<SellLotDTO> hypotheticalSellLots =
                buildHypotheticalSellLots(accountId, heldLots, expectedFinalAt);

        List<TaxRuleDTO> taxRules = taxMapper.selectTaxRules();
        List<ExternalBuyDTO> externalTrades =
                taxMapper.selectExternalBuysByAccountIdsAndYear(
                        List.of(accountId), riaTaxProperties.getYear(), clockService.now());

        TaxCalculationResultDTO result =
                taxCalculator.calculate(
                        hypotheticalSellLots,
                        taxRules,
                        externalTrades,
                        BenefitType.isReliefExcluded(account.getBenefit()));

        return TaxExpectedReliefResponseDTO.of(accountId, expectedFinalAt, result);
    }

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
    public CustomerTaxBriefingResponseDTO getCustomerTaxBriefing(Long customerId) {
        AccountDTO account =
                accountMapper
                        .selectByCustomerId(customerId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, customerId));
        Long accountId = account.getAccountId();
        LocalDateTime calculatedAt = clockService.now();
        List<Long> accountIds = List.of(accountId);

        List<TaxRuleDTO> taxRules = taxMapper.selectTaxRules();
        List<ExternalBuyDTO> externalTrades =
                taxMapper.selectExternalBuysByAccountIdsAndYear(
                        accountIds, riaTaxProperties.getYear(), calculatedAt);
        List<SellLotDTO> finalizedLots =
                taxMapper.selectFinalizedLotsByAccountIdsAndYear(
                        accountIds, riaTaxProperties.getYear(), calculatedAt);
        List<SellLotDTO> provisionalLots =
                taxMapper.selectProvisionalLotsByAccountIdsAndYear(
                        accountIds, riaTaxProperties.getYear(), calculatedAt);

        CustomerTaxBriefingResponseDTO.TaxScenario current =
                calculateBriefingScenario(account, finalizedLots, taxRules, externalTrades);

        LocalDate expectedFinalAt =
                settlementBusinessDayCalculator.calculateFinalAt(calculatedAt).toLocalDate();
        expectedFinalAt = latestFinalAt(expectedFinalAt, provisionalLots);

        List<HeldLotDTO> heldLots = taxMapper.selectHeldLotsByAccountId(accountId);
        List<SellLotDTO> hypotheticalLots;
        try {
            hypotheticalLots = buildHypotheticalSellLots(accountId, heldLots, expectedFinalAt);
        } catch (RuntimeException e) {
            log.warn(
                    "절세 브리핑 예상 세액 계산을 위한 외부 시세 조회에 실패했습니다. accountId={}, cause={}",
                    accountId,
                    e.getClass().getSimpleName());
            return CustomerTaxBriefingResponseDTO.of(accountId, current, null, calculatedAt);
        }

        List<SellLotDTO> projectedLots = new ArrayList<>(finalizedLots);
        projectedLots.addAll(provisionalLots);
        projectedLots.addAll(hypotheticalLots);

        CustomerTaxBriefingResponseDTO.TaxScenario projected =
                calculateBriefingScenario(account, projectedLots, taxRules, externalTrades);
        BigDecimal additionalRelief =
                projected
                        .getReliefAmount()
                        .subtract(current.getReliefAmount())
                        // 예상 감면액이 현재보다 작더라도 "추가 감면"을 음수로 표시하지 않는다.
                        .max(BigDecimal.ZERO);

        CustomerTaxBriefingResponseDTO.ExpectedTaxScenario expected =
                CustomerTaxBriefingResponseDTO.ExpectedTaxScenario.of(
                        projected.getGeneralTax(),
                        projected.getRiaTax(),
                        projected.getReliefAmount(),
                        additionalRelief,
                        expectedFinalAt);

        return CustomerTaxBriefingResponseDTO.of(accountId, current, expected, calculatedAt);
    }

    private LocalDate latestFinalAt(LocalDate expectedFinalAt, List<SellLotDTO> provisionalLots) {
        return provisionalLots.stream()
                .map(SellLotDTO::getFinalAt)
                .filter(java.util.Objects::nonNull)
                .reduce(expectedFinalAt, (latest, date) -> date.isAfter(latest) ? date : latest);
    }

    // 아직 안 판 보유 lot을 "오늘 결제 기준(T+2)"에 판다고 가정해 SellLotDTO로 만든다.
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

    private CustomerTaxBriefingResponseDTO.TaxScenario calculateBriefingScenario(
            AccountDTO account,
            List<SellLotDTO> sellLots,
            List<TaxRuleDTO> taxRules,
            List<ExternalBuyDTO> externalTrades) {
        TaxCalculationResultDTO generalResult =
                taxCalculator.calculate(sellLots, taxRules, externalTrades, true);
        TaxCalculationResultDTO riaResult =
                taxCalculator.calculate(
                        sellLots,
                        taxRules,
                        externalTrades,
                        BenefitType.isReliefExcluded(account.getBenefit()));
        BigDecimal reliefAmount =
                generalResult.getFinalTax().subtract(riaResult.getFinalTax()).max(BigDecimal.ZERO);

        return CustomerTaxBriefingResponseDTO.TaxScenario.of(
                generalResult.getFinalTax(), riaResult.getFinalTax(), reliefAmount);
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
