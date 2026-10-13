package com.app.maria.domain.domestic.service;

import com.app.maria.domain.domestic.dto.DomesticProductDTO;
import com.app.maria.domain.domestic.mapper.DomesticProductMapper;
import com.app.maria.domain.domestic.type.Type;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DomesticPurchaseEligibilityServiceImpl implements DomesticPurchaseEligibilityService {

    private static final int DOMESTIC_STOCK_RATIO_THRESHOLD = 80;
    private static final int INCEPTION_GRACE_PERIOD_MONTHS = 1;

    private final DomesticProductMapper domesticProductMapper;
    private final BusinessClockService businessClockService;

    @Override
    public boolean isPurchasable(Long domesticProductId) {
        DomesticProductDTO product =
                domesticProductMapper
                        .selectById(domesticProductId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorType.DOMESTIC_PRODUCT_NOT_FOUND,
                                                domesticProductId));
        return isPurchasable(
                product.getType(), product.getDomesticStockRatio(), product.getInceptionDate());
    }

    @Override
    public boolean isPurchasable(
            Type type, BigDecimal domesticStockRatio, LocalDate inceptionDate) {
        if (type == Type.STOCK) {
            return true;
        }
        LocalDate today = businessClockService.now().toLocalDate();
        return isDomesticStockRatioMet(domesticStockRatio)
                && isInceptionPeriodMet(inceptionDate, today);
    }

    private boolean isDomesticStockRatioMet(BigDecimal domesticStockRatio) {
        return domesticStockRatio != null
                && domesticStockRatio.compareTo(BigDecimal.valueOf(DOMESTIC_STOCK_RATIO_THRESHOLD))
                        >= 0;
    }

    private boolean isInceptionPeriodMet(LocalDate inceptionDate, LocalDate today) {
        return inceptionDate != null
                && !inceptionDate.isAfter(today.minusMonths(INCEPTION_GRACE_PERIOD_MONTHS));
    }
}
