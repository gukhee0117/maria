package com.app.maria.domain.risk.service;

import com.app.maria.domain.risk.dto.RiskGradeClassificationDTO;
import com.app.maria.domain.risk.dto.UsableRiskGradeDTO;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public interface RiskGradeService {
    List<UsableRiskGradeDTO> findUsable();

    /** Grade-only view. Implementations may use independent current stock classifications. */
    default Map<Long, Integer> findUsableGrades(Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) return Map.of();
        return findUsableGrades().entrySet().stream()
                .filter(e -> productIds.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    default Map<Long, Integer> findUsableGrades() {
        return findUsable().stream()
                .collect(
                        Collectors.toMap(
                                UsableRiskGradeDTO::domesticProductId,
                                UsableRiskGradeDTO::riskGrade,
                                (a, b) -> a));
    }

    default Optional<RiskGradeClassificationDTO> findClassificationByTicker(String ticker) {
        return findUsableByTicker(ticker)
                .map(
                        g ->
                                new RiskGradeClassificationDTO(
                                        g.domesticProductId(),
                                        g.ticker(),
                                        g.name(),
                                        g.riskGrade(),
                                        "HISTORICAL_METRICS"));
    }

    /**
     * 여러 종목의 사용 가능한 위험등급을 ticker별로 돌려준다(없거나 version 0이거나 만료된 종목은 빠진다). 달력은 한 번만 읽고, 한 종목의 자료가 깨져도 그
     * 종목만 빠진다. 달력·시간대 자체를 못 읽으면 예외다.
     */
    Map<String, UsableRiskGradeDTO> findUsableByTickers(Set<String> tickers);

    /** 종목 하나의 사용 가능한 위험등급. 자료가 없거나 version 0이거나 만료됐으면 empty. */
    Optional<UsableRiskGradeDTO> findUsableByTicker(String ticker);
}
