package com.app.maria.domain.tax.dto;

import com.app.maria.domain.tax.type.TaxRuleType;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class TaxRuleDTO {
    private Long ruleId;
    private TaxRuleType ruleType;
    private BigDecimal ruleValue;
    private LocalDate validFrom;
    private LocalDate validTo;
}
