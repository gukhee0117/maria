package com.app.maria.domain.news.service;

import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.domestic.mapper.DomesticStockBalanceMapper;
import com.app.maria.domain.domestic.type.DomesticStockStatus;
import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.investorassessment.service.InvestorAssessmentService;
import com.app.maria.domain.news.dto.NewsContract.*;
import com.app.maria.domain.news.mapper.NewsInventoryMapper;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NewsCustomerContextService {
    private final AccountMapper accounts;
    private final DomesticStockBalanceMapper holdings;
    private final InvestorAssessmentService assessments;

    private final NewsInventoryMapper newsInventory;

    // 성향 조회(InvestorAssessmentService)가 JPA 트랜잭션이라 같은 매니저로 연다. 기본(MyBatis) 매니저로 열면 두 매니저가
    // 같은 DB 연결을 잡으려다 실패한다. JPA 매니저도 MyBatis 조회에 같은 연결을 넘겨서 보유 종목과 성향을 한 시점으로 읽는다
    @Transactional(
            transactionManager = "jpaTransactionManager",
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ)
    public Context load(Long customerId) {
        return read(customerId, false, true);
    }

    @Transactional(
            transactionManager = "jpaTransactionManager",
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ)
    public Context loadForSelection(Long customerId) {
        return read(customerId, true, true);
    }

    @Transactional(
            transactionManager = "jpaTransactionManager",
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ)
    public Context loadHoldings(Long customerId) {
        return read(customerId, false, false);
    }

    private Context read(Long customerId, boolean includePriorities, boolean includeRevision) {
        var allHoldings =
                accounts.selectByCustomerId(customerId)
                        .map(a -> holdings.selectHoldingsByAccountId(a.getAccountId()))
                        .orElse(java.util.List.of());
        var activeHoldings =
                allHoldings.stream()
                        .filter(
                                h ->
                                        h.getType() == Type.STOCK
                                                && h.getQty() != null
                                                && h.getQty().signum() > 0)
                        .filter(
                                h ->
                                        h.getStatus() != null
                                                && h.getStatus() != DomesticStockStatus.SOLD_OUT
                                                && h.getStatus() != DomesticStockStatus.TERMINATED)
                        .filter(h -> h.getTicker() != null && h.getTicker().matches("[0-9]{6}"))
                        .toList();
        String balanceRevision =
                NewsCacheIdentity.hash(
                        allHoldings.stream()
                                .map(
                                        h ->
                                                h.getDomesticProductId()
                                                        + ":"
                                                        + h.getTicker()
                                                        + ":"
                                                        + h.getType()
                                                        + ":"
                                                        + h.getQty()
                                                        + ":"
                                                        + h.getStatus())
                                .sorted()
                                .collect(Collectors.joining("|")));
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
                                        (first, second) -> first)),
                includePriorities
                        ? newsInventory.selectHoldingThemePriorities(customerId).stream()
                                .collect(
                                        Collectors.toMap(
                                                row -> row.getTheme(),
                                                row -> row.getPriority(),
                                                Long::max))
                        : Map.of(),
                includeRevision ? newsInventory.selectLatestOrderId(customerId) : 0L,
                balanceRevision);
    }
}
