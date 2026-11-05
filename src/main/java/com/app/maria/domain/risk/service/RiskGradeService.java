package com.app.maria.domain.risk.service;

import com.app.maria.domain.risk.dto.RiskGradeClassificationDTO;
import com.app.maria.domain.risk.dto.UsableRiskGradeDTO;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public interface RiskGradeService {
    List<UsableRiskGradeDTO> findUsable();

    /** Grade-only view. Implementations may use independent current stock classifications. */
    default Map<Long, Integer> findUsableGrades(java.util.Collection<Long> productIds) {
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

    /** 종목 하나의 사용 가능한 위험등급. 자료가 없거나 version 0이거나 만료됐으면 empty. */
    Optional<UsableRiskGradeDTO> findUsableByTicker(String ticker);
}
