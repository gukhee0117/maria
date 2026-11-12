package com.app.maria.domain.stockpackage.vo;

import com.app.maria.domain.stockpackage.vo.StockPackageCheckVO.ExplanationBlock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/** 설문 투자성향. 모든 값은 코드가 정하고 설명은 문장은행으로 만든다. */
@Getter
@Builder
public class StockTendencyVO {
    private final Survey survey;
    private final List<ExplanationBlock> explanation;
    private final String disclaimer;
    private final LocalDateTime checkedAt;

    /** 설문 성향. available이 false면 진단이 없거나 만료된 것이다. */
    public record Survey(
            boolean available,
            String investorType,
            String label,
            Integer allowedMinGrade,
            LocalDateTime assessedAt,
            boolean expired) {}
}
