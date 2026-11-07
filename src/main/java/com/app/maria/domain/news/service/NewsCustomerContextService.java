package com.app.maria.domain.news.service;

import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.domestic.mapper.DomesticStockBalanceMapper;
import com.app.maria.domain.domestic.type.DomesticStockStatus;
import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.investorassessment.service.InvestorAssessmentService;
import com.app.maria.domain.news.dto.NewsContract.*;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NewsCustomerContextService {
    private final AccountMapper accounts;
    private final DomesticStockBalanceMapper holdings;
    private final InvestorAssessmentService assessments;

    public Context load(Long customerId) {
        var activeHoldings =
                accounts.selectByCustomerId(customerId)
                        .map(
                                a ->
                                        holdings
                                                .selectHoldingsByAccountId(a.getAccountId())
                                                .stream()
                                                .filter(
                                                        h ->
                                                                h.getType() == Type.STOCK
                                                                        && h.getQty() != null
                                                                        && h.getQty().signum() > 0)
                                                .filter(
                                                        h ->
                                                                h.getStatus() != null
                                                                        && h.getStatus()
                                                                                != DomesticStockStatus
                                                                                        .SOLD_OUT
                                                                        && h.getStatus()
                                                                                != DomesticStockStatus
                                                                                        .TERMINATED)
                                                .filter(
                                                        h ->
                                                                h.getTicker() != null
                                                                        && h.getTicker()
                                                                                .matches(
                                                                                        "[0-9]{6}"))
                                                .toList())
                        .orElse(java.util.List.of());
        Set<String> tickers =
                activeHoldings.stream().map(h -> h.getTicker()).collect(Collectors.toSet());
        Map<String, String> names =
                activeHoldings.stream()
                        .filter(h -> h.getName() != null && !h.getName().isBlank())
                        .collect(
                                Collectors.toMap(
                                        h -> h.getTicker(),
                                        h -> h.getName(),
                                        (first, second) -> first));
        var status = assessments.getMine(customerId);
        var personalization =
                !status.isAssessed()
                        ? PersonalizationStatus.UNASSIGNED
                        : status.isExpired()
                                ? PersonalizationStatus.EXPIRED
                                : PersonalizationStatus.ACTIVE;
        return new Context(
                customerId,
                tickers,
                personalization == PersonalizationStatus.ACTIVE
                        ? status.getInvestorType()
                        : InvestorType.NEUTRAL,
                personalization,
                names,
                activeHoldings.stream()
                        .filter(h -> h.getSectorName() != null && !h.getSectorName().isBlank())
                        .collect(
                                Collectors.toMap(
                                        h -> h.getTicker(),
                                        h -> h.getSectorName(),
                                        (first, second) -> first)));
    }
}
