package com.app.maria.domain.targetproduct.service;

import com.app.maria.domain.externaltradesync.dto.response.MydataTradeResponseDTO;
import com.app.maria.domain.targetproduct.dto.*;
import com.app.maria.domain.targetproduct.dto.request.TargetProductSearchRequestDTO;
import com.app.maria.domain.targetproduct.dto.response.MydataFundResponseDTO;
import com.app.maria.domain.targetproduct.mapper.TargetProductMapper;
import com.app.maria.domain.targetproduct.type.StockType;
import com.app.maria.domain.targetproduct.type.TradeType;
import com.app.maria.global.client.mydatafund.MydataFundClient;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.properties.ExternalTradeSyncProperties;
import com.app.maria.global.sort.OrderBySpec;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class TargetProductServiceImpl implements TargetProductService {

    private static final int FOREIGN_STOCK_RATIO_THRESHOLD = 60;
    private static final int INCEPTION_GRACE_PERIOD_MONTHS = 1;

    private final TargetProductMapper targetProductMapper;
    private final MydataFundClient mydataFundClient;
    private final BusinessClockService businessClockService;
    private final ExternalTradeSyncProperties externalTradeSyncProperties;

    @Override
    public TargetProductJudgementDTO judge(MydataTradeResponseDTO trade) {

        boolean isTarget;
        BigDecimal foreignStockRatio = null;
        LocalDate inceptionDate = null;
        String fundName = null;
        LocalDateTime now = businessClockService.now();

        StockType stockType = StockType.valueOf(trade.getStockType());
        TradeType tradeType = TradeType.valueOf(trade.getTradeType());

        if (stockType == StockType.FUND) {
            MydataFundResponseDTO fund = mydataFundClient.getFund(trade.getFundCode());

            if (fund.getForeignStockRatio() != null) {
                foreignStockRatio = fund.getForeignStockRatio();
            }
            inceptionDate = fund.getInceptionDate();
            fundName = fund.getFundName();

            isTarget =
                    isForeignStockRatioMet(fund)
                            && isInceptionPeriodMet(fund, trade.getTradeDate());
        } else {
            isTarget = true;
        }

        BigDecimal netBuyAmount =
                tradeType == TradeType.SELL ? trade.getAmount().negate() : trade.getAmount();

        TargetProductJudgementDTO dto = new TargetProductJudgementDTO();
        dto.setMydataTradeId(trade.getTradeId());
        dto.setCiHash(trade.getCiHash());
        dto.setStockType(stockType);
        dto.setFundCode(trade.getFundCode());
        dto.setFundName(fundName);
        dto.setTicker(trade.getTicker());
        dto.setIsTarget(isTarget);
        dto.setForeignStockRatio(foreignStockRatio);
        dto.setInceptionDate(inceptionDate);
        dto.setTradeType(tradeType);
        dto.setAmount(trade.getAmount());
        dto.setTradeDate(trade.getTradeDate());
        dto.setNetBuyAmount(netBuyAmount);
        dto.setJudgedAt(now);

        targetProductMapper.insertJudgement(dto);

        return dto;
    }

    @Override
    @Transactional(readOnly = true)
    public TargetProductJudgementPageDTO getJudgements(TargetProductSearchRequestDTO request) {
        TargetProductSearchDTO searchDTO = request.toTargetProductSearchDTO();
        if (Boolean.TRUE.equals(request.getTodayOnly())) {
            LocalDate today = businessClockService.now().toLocalDate();
            searchDTO.setJudgedAtFrom(today.atStartOfDay());
            searchDTO.setJudgedAtTo(today.plusDays(1).atStartOfDay());
        }
        List<TargetProductJudgementListDTO> content =
                targetProductMapper.selectJudgements(searchDTO);
        long totalElements = targetProductMapper.countFilteredJudgements(searchDTO);
        int totalPages = (int) Math.ceil((double) totalElements / request.getSize());

        return TargetProductJudgementPageDTO.builder()
                .content(content)
                .page(request.getPage())
                .size(request.getSize())
                .totalElements(totalElements)
                .totalPages(totalPages)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public TargetProductSummaryDTO getSummary() {
        LocalDate today = businessClockService.now().toLocalDate();
        LocalDate tomorrow = today.plusDays(1);
        TargetProductSummaryDTO todayStats = targetProductMapper.selectSummary(today, tomorrow);
        int totalCount = targetProductMapper.countJudgements();

        return TargetProductSummaryDTO.builder()
                .todayJudgementCount(todayStats.getTodayJudgementCount())
                .todayTargetCount(todayStats.getTodayTargetCount())
                .todayTargetNetBuyAmount(todayStats.getTodayTargetNetBuyAmount())
                .todayInheritanceGiftCount(todayStats.getTodayInheritanceGiftCount())
                .totalJudgementCount(totalCount)
                .build();
    }

    private boolean isForeignStockRatioMet(MydataFundResponseDTO fund) {
        return fund.getForeignStockRatio() != null
                && fund.getForeignStockRatio()
                                .compareTo(BigDecimal.valueOf(FOREIGN_STOCK_RATIO_THRESHOLD))
                        >= 0;
    }

    private boolean isInceptionPeriodMet(MydataFundResponseDTO fund, LocalDate asOfDate) {
        return fund.getInceptionDate() != null
                && !fund.getInceptionDate()
                        .isAfter(asOfDate.minusMonths(INCEPTION_GRACE_PERIOD_MONTHS));
    }

    @Override
    @Transactional(readOnly = true)
    public TargetProductJudgementFailurePageDTO getPermanentFailures(int page, int size) {
        LocalDateTime cutoff = failureCutoff();
        return toFailurePage(
                targetProductMapper.selectPermanentFailures(cutoff, page * size, size),
                cutoff,
                page,
                size);
    }

    /** 영구 실패 목록의 정렬 키(화면 머리글) → 정렬 열. 여기 없는 키는 무시한다. */
    private static final Map<String, String> FAILURE_SORT_COLUMNS =
            Map.of(
                    "customerName", "c.name",
                    "tradeDate", "f.trade_date",
                    "failureCount", "f.failure_count",
                    "lastError", "f.last_error",
                    "firstFailedAt", "f.first_failed_at",
                    "lastFailedAt", "f.last_failed_at");

    @Override
    @Transactional(readOnly = true)
    public TargetProductJudgementFailurePageDTO getPermanentFailures(
            int page, int size, String sort, String direction) {
        LocalDateTime cutoff = failureCutoff();
        String orderBy =
                OrderBySpec.resolve(
                        FAILURE_SORT_COLUMNS,
                        sort,
                        direction,
                        "f.first_failed_at ASC, f.failure_id ASC");
        return toFailurePage(
                targetProductMapper.selectPermanentFailuresOrdered(
                        cutoff, orderBy, page * size, size),
                cutoff,
                page,
                size);
    }

    private LocalDateTime failureCutoff() {
        return businessClockService
                .now()
                .minusDays(externalTradeSyncProperties.getMaxJudgeFailureDays());
    }

    private TargetProductJudgementFailurePageDTO toFailurePage(
            List<TargetProductJudgementFailureListDTO> content,
            LocalDateTime cutoff,
            int page,
            int size) {
        long totalElements = targetProductMapper.countPermanentFailures(cutoff);
        int totalPages = (int) Math.ceil((double) totalElements / size);

        return TargetProductJudgementFailurePageDTO.builder()
                .content(content)
                .page(page)
                .size(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .build();
    }
}
