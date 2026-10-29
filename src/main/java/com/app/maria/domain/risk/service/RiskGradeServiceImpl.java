package com.app.maria.domain.risk.service;

import com.app.maria.domain.risk.calendar.KrxTradingCalendar;
import com.app.maria.domain.risk.clock.RiskMarketClock;
import com.app.maria.domain.risk.dto.UsableRiskGradeDTO;
import com.app.maria.domain.risk.mapper.StockRiskGradeMapper;
import com.app.maria.domain.risk.type.RiskDataStatus;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskGradeServiceImpl implements RiskGradeService {
    private final StockRiskGradeMapper mapper;
    private final KrxTradingCalendar calendar;
    private final RiskMarketClock clock;

    public RiskGradeServiceImpl(
            StockRiskGradeMapper mapper, KrxTradingCalendar calendar, RiskMarketClock clock) {
        this.mapper = mapper;
        this.calendar = calendar;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UsableRiskGradeDTO> findUsable() {
        var now = clock.now();
        var snapshot = calendar.load();
        var reference = snapshot.latest(now);
        if (!Integer.valueOf(32400).equals(mapper.sessionUtcOffsetSeconds()))
            throw new AppException(ErrorType.RISK_TIMEZONE_UNAVAILABLE);
        return mapper.findAll().stream()
                .filter(row -> row.getAnalysisVersion() != null && row.getAnalysisVersion() > 0)
                .filter(
                        row ->
                                snapshot.state(row.getPriceTo(), reference)
                                        != RiskDataStatus.EXPIRED)
                .map(
                        row ->
                                new UsableRiskGradeDTO(
                                        row.getDomesticProductId(),
                                        row.getTicker(),
                                        row.getVar975(),
                                        row.getRiskGrade(),
                                        row.getVolatility(),
                                        row.getPriceFrom(),
                                        row.getPriceTo(),
                                        row.getAnalysisVersion(),
                                        snapshot.state(row.getPriceTo(), reference)))
                .toList();
    }
}
