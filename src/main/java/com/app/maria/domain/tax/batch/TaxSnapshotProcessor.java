package com.app.maria.domain.tax.batch;

import com.app.maria.domain.account.type.BenefitType;
import com.app.maria.domain.tax.dto.TaxRuleDTO;
import com.app.maria.domain.tax.dto.TaxSnapshotDTO;
import com.app.maria.domain.tax.dto.TaxSnapshotTargetDTO;
import com.app.maria.domain.tax.mapper.TaxMapper;
import com.app.maria.domain.tax.service.TaxCalculator;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@StepScope
@RequiredArgsConstructor
public class TaxSnapshotProcessor implements ItemProcessor<TaxSnapshotTargetDTO, TaxSnapshotDTO> {
    private final TaxCalculator taxCalculator;
    private final TaxMapper taxMapper;

    @Value("#{jobParameters['calculatedAt']}")
    private LocalDateTime calculatedAt;

    private List<TaxRuleDTO> taxRules;

    @BeforeStep
    public void loadTaxRules(StepExecution stepExecution) {
        taxRules = taxMapper.selectTaxRules();
    }

    @Override
    public TaxSnapshotDTO process(TaxSnapshotTargetDTO target) {
        return TaxSnapshotDTO.of(
                target.getAccount().getAccountId(),
                calculatedAt,
                taxCalculator.calculate(
                        target.getSellLots(),
                        taxRules,
                        target.getExternalTrades(),
                        BenefitType.isReliefExcluded(target.getAccount().getBenefit())));
    }
}
