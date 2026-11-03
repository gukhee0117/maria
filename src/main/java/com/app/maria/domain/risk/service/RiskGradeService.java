package com.app.maria.domain.risk.service;

import com.app.maria.domain.risk.dto.UsableRiskGradeDTO;
import java.util.List;
import java.util.Optional;

public interface RiskGradeService {
    List<UsableRiskGradeDTO> findUsable();

    /** 종목 하나의 사용 가능한 위험등급. 자료가 없거나 version 0이거나 만료됐으면 empty. */
    Optional<UsableRiskGradeDTO> findUsableByTicker(String ticker);
}
