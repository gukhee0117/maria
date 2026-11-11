package com.app.maria.domain.risk.service;

import com.app.maria.domain.risk.clock.RiskMarketClock;
import com.app.maria.domain.risk.dto.StockRiskGradeDTO;
import com.app.maria.domain.risk.mapper.StockRiskGradeMapper;
import com.app.maria.global.client.risk.PythonGradeResponseDTO;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.ZonedDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class RiskGradeStore {
    public enum Outcome {
        INSERTED,
        UPDATED,
        DUPLICATE,
        IGNORED,
        CONFLICT
    }

    private final StockRiskGradeMapper mapper;

    public RiskGradeStore(StockRiskGradeMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public Outcome store(PythonGradeResponseDTO incoming, ZonedDateTime receivedAt) {
        incoming.validate();
        if (!Integer.valueOf(32400).equals(mapper.sessionUtcOffsetSeconds()))
            throw new AppException(ErrorType.RISK_TIMEZONE_UNAVAILABLE);
        var ids = mapper.lockProductsByTicker(incoming.ticker());
        if (ids.size() != 1)
            throw new AppException(ErrorType.RISK_PRODUCT_UNRESOLVED, incoming.ticker());
        var row = new StockRiskGradeDTO();
        row.setDomesticProductId(ids.get(0));
        row.setTicker(incoming.ticker());
        row.setVar975(incoming.var975());
        row.setRiskGrade(incoming.riskGrade());
        row.setVolatility(incoming.volatility());
        row.setPriceFrom(incoming.priceFrom());
        row.setPriceTo(incoming.priceTo());
        // The existing DATETIME columns have seconds precision; normalize before comparison.
        row.setCalculatedAt(
                incoming.asOf()
                        .atZoneSameInstant(RiskMarketClock.ZONE)
                        .toLocalDateTime()
                        .withNano(0));
        row.setSyncedAt(
                receivedAt.withZoneSameInstant(RiskMarketClock.ZONE).toLocalDateTime().withNano(0));
        row.setAnalysisVersion(incoming.analysisVersion());
        var old = mapper.findForUpdate(ids.get(0));
        if (old == null) {
            mapper.insert(row);
            return Outcome.INSERTED;
        }
        int dateOrder = row.getPriceTo().compareTo(old.getPriceTo());
        if (dateOrder < 0 || dateOrder == 0 && row.getAnalysisVersion() < old.getAnalysisVersion())
            return Outcome.IGNORED;
        if (dateOrder == 0 && row.getAnalysisVersion().equals(old.getAnalysisVersion())) {
            if (old.sameAnalysis(row)) return Outcome.DUPLICATE;
            log.warn(
                    "RISK_VERSION_CONFLICT ticker={} priceTo={} version={}",
                    incoming.ticker(),
                    incoming.priceTo(),
                    incoming.analysisVersion());
            return Outcome.CONFLICT;
        }
        if (mapper.updateIfNewer(row) != 1) throw new AppException(ErrorType.RISK_SYNC_CONFLICT);
        return Outcome.UPDATED;
    }
}
