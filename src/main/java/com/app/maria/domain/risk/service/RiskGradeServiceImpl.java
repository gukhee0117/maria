package com.app.maria.domain.risk.service;

import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.risk.calendar.KrxTradingCalendar;
import com.app.maria.domain.risk.clock.RiskMarketClock;
import com.app.maria.domain.risk.dto.RiskGradeClassificationDTO;
import com.app.maria.domain.risk.dto.RiskProductDTO;
import com.app.maria.domain.risk.dto.StockRiskGradeDTO;
import com.app.maria.domain.risk.dto.UsableRiskGradeDTO;
import com.app.maria.domain.risk.mapper.StockRiskGradeMapper;
import com.app.maria.domain.risk.type.RiskDataStatus;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class RiskGradeServiceImpl implements RiskGradeService {
    private final StockRiskGradeMapper mapper;
    private final KrxTradingCalendar calendar;
    private final RiskMarketClock clock;
    private final CurrentStockRiskService stockClassifications;

    /**
     * Test-only constructor for historical metric reads; current classification requires the
     * injected constructor.
     */
    public RiskGradeServiceImpl(
            StockRiskGradeMapper mapper, KrxTradingCalendar calendar, RiskMarketClock clock) {
        this(mapper, calendar, clock, null);
    }

    @Autowired
    public RiskGradeServiceImpl(
            StockRiskGradeMapper mapper,
            KrxTradingCalendar calendar,
            RiskMarketClock clock,
            CurrentStockRiskService stockClassifications) {
        this.stockClassifications = stockClassifications;
        this.mapper = mapper;
        this.calendar = calendar;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UsableRiskGradeDTO> findUsable() {
        var context = context();
        return mapper.findAll().stream()
                .map(row -> toUsable(row, context))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, UsableRiskGradeDTO> findUsableByTickers(Set<String> tickers) {
        var context = context();
        Map<String, UsableRiskGradeDTO> usable = new LinkedHashMap<>();
        for (String ticker : tickers) {
            try {
                Optional.ofNullable(mapper.findByTicker(ticker))
                        .flatMap(row -> toUsable(row, context))
                        .ifPresent(grade -> usable.put(ticker, grade));
            } catch (AppException e) {
                // 이 종목의 자료만 깨진 경우(달력에 없는 priceTo 등): 나머지 종목은 계속 쓴다.
                log.warn("위험등급 자료 이상으로 제외: {} ({})", ticker, e.getErrorType());
            }
        }
        return usable;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UsableRiskGradeDTO> findUsableByTicker(String ticker) {
        var context = context();
        return Optional.ofNullable(mapper.findByTicker(ticker))
                .flatMap(row -> toUsable(row, context));
    }

    @Override
    public Map<Long, Integer> findUsableGrades() {
        return resolveGrades(null);
    }

    @Override
    public Map<Long, Integer> findUsableGrades(Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) return Map.of();
        return resolveGrades(Set.copyOf(productIds));
    }

    /**
     * 패키지 저장용. 저장값은 마지막 배치 시점 기준이라 그 뒤에 투자경고로 지정된 주식을 놓칠 수 있으므로, 담는 주식만 실시간 상태로 다시 확인한다. 실시간 확인에
     * 실패한 주식은 저장값으로 대신하지 않고 등급 없음으로 둔다(새로 담기는 거부된다).
     */
    @Override
    public Map<Long, Integer> findConfirmedGrades(Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) return Map.of();
        var result = new HashMap<>(resolveGrades(Set.copyOf(productIds)));
        for (var p : mapper.findProductsByIds(Set.copyOf(productIds))) {
            if (p.type() != Type.STOCK) continue;
            result.remove(p.domesticProductId());
            currentStock(p).ifPresent(g -> result.put(g.domesticProductId(), g.riskGrade()));
        }
        return Map.copyOf(result);
    }

    /**
     * 목록·패키지 화면용. 주식도 펀드처럼 risk 배치가 저장한 등급을 읽는다(주식은 마지막 배치 때 본 투자경고·관리종목 여부). 요청마다 KIS로 주식을 하나씩
     * 조회하면 후보 수백 개가 쌓여 수십 초~수 분이 걸렸다. 실시간 판정은 단건 조회({@link #findClassificationByTicker})와 패키지
     * 저장({@link #findConfirmedGrades})만 한다.
     */
    private Map<Long, Integer> resolveGrades(Set<Long> requested) {
        var result = new HashMap<Long, Integer>();
        try {
            for (var g : findUsable()) {
                if (requested == null || requested.contains(g.domesticProductId()))
                    result.put(g.domesticProductId(), g.riskGrade());
            }
        } catch (AppException e) {
            if (!e.getErrorType().name().startsWith("RISK_")) throw e;
            log.warn("RISK_METRICS_UNAVAILABLE code={}", e.getErrorType().name());
        }
        return Map.copyOf(result);
    }

    @Override
    public Optional<RiskGradeClassificationDTO> findClassificationByTicker(String ticker) {
        if (ticker == null || !ticker.matches("[0-9A-Z]{6}")) return Optional.empty();
        var product = mapper.findProductByTicker(ticker);
        if (product == null) return Optional.empty();
        if (product.type() == Type.STOCK) return currentStock(product);
        if (product.type() != Type.FUND) return Optional.empty();
        return findUsableByTicker(ticker)
                .map(
                        g ->
                                new RiskGradeClassificationDTO(
                                        g.domesticProductId(),
                                        g.ticker(),
                                        g.name(),
                                        g.riskGrade(),
                                        "FUND_VAR_OR_ASSET"));
    }

    private Optional<RiskGradeClassificationDTO> currentStock(RiskProductDTO product) {
        if (stockClassifications == null)
            throw new IllegalStateException(
                    "Current classification is unavailable with the test-only constructor");
        try {
            var current = stockClassifications.findCurrent(product.ticker());
            return Optional.of(
                    new RiskGradeClassificationDTO(
                            product.domesticProductId(),
                            product.ticker(),
                            product.name(),
                            current.riskGrade(),
                            "STOCK_RULE_CURRENT_STATUS"));
        } catch (AppException e) {
            if (!e.getErrorType().name().startsWith("RISK_")) throw e;
            log.warn("RISK_STOCK_CLASSIFICATION_UNAVAILABLE code={}", e.getErrorType().name());
            return Optional.empty();
        }
    }

    private record Context(KrxTradingCalendar.Snapshot snapshot, LocalDate reference) {}

    private Context context() {
        var snapshot = calendar.load();
        var reference = snapshot.latest(clock.now());
        if (!Integer.valueOf(32400).equals(mapper.sessionUtcOffsetSeconds()))
            throw new AppException(ErrorType.RISK_TIMEZONE_UNAVAILABLE);
        return new Context(snapshot, reference);
    }

    private Optional<UsableRiskGradeDTO> toUsable(StockRiskGradeDTO row, Context context) {
        if (row.getAnalysisVersion() == null || row.getAnalysisVersion() <= 0)
            return Optional.empty();
        var status = context.snapshot().state(row.getPriceTo(), context.reference());
        if (status == RiskDataStatus.EXPIRED) return Optional.empty();
        return Optional.of(
                new UsableRiskGradeDTO(
                        row.getDomesticProductId(),
                        row.getTicker(),
                        row.getName(),
                        row.getVar975(),
                        row.getRiskGrade(),
                        row.getVolatility(),
                        row.getPriceFrom(),
                        row.getPriceTo(),
                        row.getAnalysisVersion(),
                        status));
    }
}
