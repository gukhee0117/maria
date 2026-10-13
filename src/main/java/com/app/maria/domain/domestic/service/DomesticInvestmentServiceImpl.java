package com.app.maria.domain.domestic.service;

import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.domestic.dto.*;
import com.app.maria.domain.domestic.dto.request.DomesticInvestmentSearchRequestDTO;
import com.app.maria.domain.domestic.dto.request.DomesticTradeRequestDTO;
import com.app.maria.domain.domestic.dto.response.*;
import com.app.maria.domain.domestic.mapper.DomesticStockBalanceMapper;
import com.app.maria.domain.domestic.type.Type;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import com.app.maria.global.response.ApiResponseDTO;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Service
@Transactional(rollbackFor = Exception.class, readOnly = true)
public class DomesticInvestmentServiceImpl implements DomesticInvestmentService {

    private final DomesticStockBalanceMapper domesticStockBalanceMapper;
    private final AccountMapper accountMapper;
    private final DomesticPurchaseEligibilityService domesticPurchaseEligibilityService;
    private final RestClient restClient;
    private final BusinessClockService businessClockService;

    public DomesticInvestmentServiceImpl(
            DomesticStockBalanceMapper domesticStockBalanceMapper,
            AccountMapper accountMapper,
            DomesticPurchaseEligibilityService domesticPurchaseEligibilityService,
            @Qualifier("returnSecuritiesRestClient") RestClient restClient,
            BusinessClockService businessClockService) {
        this.domesticStockBalanceMapper = domesticStockBalanceMapper;
        this.accountMapper = accountMapper;
        this.domesticPurchaseEligibilityService = domesticPurchaseEligibilityService;
        this.restClient = restClient;
        this.businessClockService = businessClockService;
    }

    @Override
    public DomesticInvestmentPageDTO getInvestments(DomesticInvestmentSearchRequestDTO request) {
        var condition = request.toDomesticInvestmentSearchDTO();
        if (Boolean.TRUE.equals(request.getHasUnpurchasableHolding())) {
            List<Long> unpurchasableAccountIds = resolveUnpurchasableAccountIds();
            if (unpurchasableAccountIds.isEmpty()) {
                return DomesticInvestmentPageDTO.builder()
                        .content(List.of())
                        .page(request.getPage())
                        .size(request.getSize())
                        .totalElements(0)
                        .totalPages(0)
                        .build();
            }
            condition.setUnpurchasableAccountIds(unpurchasableAccountIds);
        }
        if (request.getHasRecentBuy() != null) {
            int days = request.getRecentBuyDays() != null ? request.getRecentBuyDays() : 7;
            condition.setRecentBuySinceDate(
                    businessClockService.now().toLocalDate().minusDays(days));
        }

        List<DomesticInvestmentListDTO> content =
                domesticStockBalanceMapper.selectAccountSummaries(condition);
        int totalElements = domesticStockBalanceMapper.countAccountSummaries(condition);
        int totalPages = (int) Math.ceil((double) totalElements / request.getSize());

        return DomesticInvestmentPageDTO.builder()
                .content(content)
                .page(request.getPage())
                .size(request.getSize())
                .totalElements(totalElements)
                .totalPages(totalPages)
                .build();
    }

    private List<Long> resolveUnpurchasableAccountIds() {
        return findUnpurchasableFundHoldings()
                .map(DomesticFundHoldingDetailDTO::getAccountId)
                .distinct()
                .toList();
    }

    private Stream<DomesticFundHoldingDetailDTO> findUnpurchasableFundHoldings() {
        return domesticStockBalanceMapper.selectActiveFundHoldings().stream()
                .filter(
                        h ->
                                !domesticPurchaseEligibilityService.isPurchasable(
                                        Type.FUND,
                                        h.getDomesticStockRatio(),
                                        h.getInceptionDate()));
    }

    @Override
    public DomesticAccountDetailDTO getAccountDetail(Long accountId) {
        DomesticInvestmentListDTO summary =
                domesticStockBalanceMapper
                        .selectAccountSummaryById(accountId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorType.DOMESTIC_INVESTMENT_NOT_FOUND,
                                                accountId));

        List<DomesticHoldingDTO> holdings =
                domesticStockBalanceMapper.selectHoldingsByAccountId(accountId);
        holdings.forEach(
                h ->
                        h.setCurrentlyPurchasable(
                                domesticPurchaseEligibilityService.isPurchasable(
                                        h.getType(),
                                        h.getDomesticStockRatio(),
                                        h.getInceptionDate())));

        Long customerId =
                accountMapper
                        .selectByAccountId(accountId)
                        .orElseThrow(() -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, accountId))
                        .getCustomerId();
        String ciHash =
                accountMapper
                        .selectCiHashByCustomerId(customerId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorType.ACCOUNT_CUSTOMER_IDENTITY_NOT_FOUND,
                                                customerId));

        List<DomesticTradeHistoryDTO> tradeHistory = fetchDomesticTradeHistory(ciHash);

        return DomesticAccountDetailDTO.builder()
                .accountId(summary.getAccountId())
                .accountNo(summary.getAccountNo())
                .customerName(summary.getCustomerName())
                .cashAmount(summary.getCashAmount())
                .holdings(holdings)
                .tradeHistory(tradeHistory)
                .build();
    }

    @Override
    public DomesticInvestmentSummaryResponseDTO getSummary(int days) {
        LocalDate today = businessClockService.now().toLocalDate();
        LocalDate sinceDate = today.minusDays(days);
        DomesticInvestmentSummaryDTO stats =
                domesticStockBalanceMapper.selectSummaryStats(sinceDate);

        int unpurchasableCount = (int) findUnpurchasableFundHoldings().count();

        return new DomesticInvestmentSummaryResponseDTO(
                DomesticInvestmentSummaryDTO.builder()
                        .totalAccountCount(stats.getTotalAccountCount())
                        .restrictedAccountCount(stats.getRestrictedAccountCount())
                        .unpurchasableHoldingCount(unpurchasableCount)
                        .totalCashAmount(stats.getTotalCashAmount())
                        .domesticStockAmount(stats.getDomesticStockAmount())
                        .domesticFundAmount(stats.getDomesticFundAmount())
                        .stockHoldingAccountCount(stats.getStockHoldingAccountCount())
                        .fundHoldingAccountCount(stats.getFundHoldingAccountCount())
                        .recentBuyAccountCount(stats.getRecentBuyAccountCount())
                        .noRecentBuyAccountCount(
                                stats.getTotalAccountCount() - stats.getRecentBuyAccountCount())
                        .build());
    }

    @Override
    public List<DomesticUnpurchasableHoldingResponseDTO> getUnpurchasableHoldings() {
        return findUnpurchasableFundHoldings()
                .map(DomesticUnpurchasableHoldingResponseDTO::new)
                .toList();
    }

    @Override
    public List<DomesticAccountLiteResponseDTO> getRecentBuyAccounts(
            int days, boolean hasRecentBuy) {
        LocalDate today = businessClockService.now().toLocalDate();
        LocalDate sinceDate = today.minusDays(days);
        return domesticStockBalanceMapper
                .selectAccountsByRecentBuyStatus(sinceDate, hasRecentBuy)
                .stream()
                .map(DomesticAccountLiteResponseDTO::new)
                .toList();
    }

    @Override
    public List<DomesticRestrictedHoldingResponseDTO> getRestrictedHoldings() {
        return domesticStockBalanceMapper.selectRestrictedHoldings().stream()
                .map(DomesticRestrictedHoldingResponseDTO::new)
                .toList();
    }

    @Override
    public DomesticCashHeavyPageResponseDTO getCashHeavyAccounts(int page, int size) {
        List<DomesticCashHeavyAccountDTO> content =
                domesticStockBalanceMapper.selectCashHeavyAccounts(page * size, size);
        int totalElements = domesticStockBalanceMapper.countCashHeavyAccounts();
        int totalPages = (int) Math.ceil((double) totalElements / size);
        return new DomesticCashHeavyPageResponseDTO(
                DomesticCashHeavyPageDTO.builder()
                        .content(content)
                        .page(page)
                        .size(size)
                        .totalElements(totalElements)
                        .totalPages(totalPages)
                        .build());
    }

    private List<DomesticTradeHistoryDTO> fetchDomesticTradeHistory(String ciHash) {
        try {
            ApiResponseDTO<List<DomesticTradeHistoryDTO>> apiResponse =
                    restClient
                            .post()
                            .uri("/api/domestic-trades")
                            .body(new DomesticTradeRequestDTO(ciHash))
                            .retrieve()
                            .body(
                                    new ParameterizedTypeReference<
                                            ApiResponseDTO<List<DomesticTradeHistoryDTO>>>() {});
            if (apiResponse == null || apiResponse.getData() == null) {
                return List.of();
            }
            return apiResponse.getData();
        } catch (RestClientException e) {
            log.warn("증권사 매매내역 조회 실패 - 매매내역 없이 나머지 정보만 반환합니다.", e);
            return List.of();
        }
    }
}
