package com.app.maria.domain.risk.service;

import com.app.maria.domain.risk.dto.UsableRiskGradeDTO;
import java.util.List;

public interface RiskGradeService {
    List<UsableRiskGradeDTO> findUsable();
}
