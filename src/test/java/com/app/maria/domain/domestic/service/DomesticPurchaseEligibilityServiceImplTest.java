package com.app.maria.domain.domestic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.app.maria.domain.domestic.dto.DomesticProductDTO;
import com.app.maria.domain.domestic.mapper.DomesticProductMapper;
import com.app.maria.domain.domestic.type.Type;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DomesticPurchaseEligibilityServiceImplTest {

    @Mock private DomesticProductMapper domesticProductMapper;

    @Mock private BusinessClockService businessClockService;

    @InjectMocks private DomesticPurchaseEligibilityServiceImpl domesticPurchaseEligibilityService;

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 10);

    private static DomesticProductDTO.DomesticProductDTOBuilder baseBuilder() {
        return DomesticProductDTO.builder()
                .domesticProductId(1L)
                .ticker("005930")
                .name("삼성전자")
                .market("KOSPI");
    }

    @Test
    @DisplayName("STOCK은 비중요건 없이 항상 매수 가능하고, Clock을 조회하지 않는다")
    void isPurchasableReturnsTrueForStockWithoutCheckingClock() {
        DomesticProductDTO product = baseBuilder().type(Type.STOCK).build();
        when(domesticProductMapper.selectById(1L)).thenReturn(Optional.of(product));

        boolean result = domesticPurchaseEligibilityService.isPurchasable(1L);

        assertThat(result).isTrue();
        verifyNoInteractions(businessClockService);
    }

    @Test
    @DisplayName("FUND는 국내주식비중 80% 이상 + 설정 1개월 경과를 모두 만족해야 매수 가능하다")
    void isPurchasableReturnsTrueForFundWhenBothConditionsMet() {
        DomesticProductDTO product =
                baseBuilder()
                        .type(Type.FUND)
                        .domesticStockRatio(BigDecimal.valueOf(85.00))
                        .inceptionDate(TODAY.minusMonths(2))
                        .build();
        when(domesticProductMapper.selectById(1L)).thenReturn(Optional.of(product));
        when(businessClockService.now()).thenReturn(TODAY.atStartOfDay());

        boolean result = domesticPurchaseEligibilityService.isPurchasable(1L);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("FUND의 국내주식비중이 80% 미만이면 매수 불가하다")
    void isPurchasableReturnsFalseWhenRatioBelowThreshold() {
        DomesticProductDTO product =
                baseBuilder()
                        .type(Type.FUND)
                        .domesticStockRatio(BigDecimal.valueOf(79.99))
                        .inceptionDate(TODAY.minusMonths(2))
                        .build();
        when(domesticProductMapper.selectById(1L)).thenReturn(Optional.of(product));
        when(businessClockService.now()).thenReturn(TODAY.atStartOfDay());

        boolean result = domesticPurchaseEligibilityService.isPurchasable(1L);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("FUND의 설정일이 1개월 미경과이면 비중이 충분해도 매수 불가하다")
    void isPurchasableReturnsFalseWhenInceptionPeriodNotMet() {
        DomesticProductDTO product =
                baseBuilder()
                        .type(Type.FUND)
                        .domesticStockRatio(BigDecimal.valueOf(95.00))
                        .inceptionDate(TODAY.minusDays(10))
                        .build();
        when(domesticProductMapper.selectById(1L)).thenReturn(Optional.of(product));
        when(businessClockService.now()).thenReturn(TODAY.atStartOfDay());

        boolean result = domesticPurchaseEligibilityService.isPurchasable(1L);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("설정일이 정확히 1개월 경과한 경계값은 요건을 충족한다")
    void isPurchasableTreatsExactlyOneMonthAsMet() {
        DomesticProductDTO product =
                baseBuilder()
                        .type(Type.FUND)
                        .domesticStockRatio(BigDecimal.valueOf(80.00))
                        .inceptionDate(TODAY.minusMonths(1))
                        .build();
        when(domesticProductMapper.selectById(1L)).thenReturn(Optional.of(product));
        when(businessClockService.now()).thenReturn(TODAY.atStartOfDay());

        boolean result = domesticPurchaseEligibilityService.isPurchasable(1L);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("FUND인데 국내주식비중이 null이면 매수 불가하다")
    void isPurchasableReturnsFalseWhenRatioIsNull() {
        DomesticProductDTO product =
                baseBuilder()
                        .type(Type.FUND)
                        .domesticStockRatio(null)
                        .inceptionDate(TODAY.minusMonths(2))
                        .build();
        when(domesticProductMapper.selectById(1L)).thenReturn(Optional.of(product));
        when(businessClockService.now()).thenReturn(TODAY.atStartOfDay());

        boolean result = domesticPurchaseEligibilityService.isPurchasable(1L);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("FUND인데 설정일이 null이면 매수 불가하다")
    void isPurchasableReturnsFalseWhenInceptionDateIsNull() {
        DomesticProductDTO product =
                baseBuilder()
                        .type(Type.FUND)
                        .domesticStockRatio(BigDecimal.valueOf(90.00))
                        .inceptionDate(null)
                        .build();
        when(domesticProductMapper.selectById(1L)).thenReturn(Optional.of(product));
        when(businessClockService.now()).thenReturn(TODAY.atStartOfDay());

        boolean result = domesticPurchaseEligibilityService.isPurchasable(1L);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("존재하지 않는 종목이면 AppException(DOMESTIC_PRODUCT_NOT_FOUND)을 던지고 Clock은 조회하지 않는다")
    void isPurchasableThrowsWhenProductNotFound() {
        when(domesticProductMapper.selectById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> domesticPurchaseEligibilityService.isPurchasable(999L))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.DOMESTIC_PRODUCT_NOT_FOUND);

        verifyNoInteractions(businessClockService);
    }

    @Test
    @DisplayName("(오버로드) STOCK은 이미 알고 있는 값만으로 판정하고 mapper를 조회하지 않는다")
    void isPurchasableWithKnownValuesReturnsTrueForStockWithoutQueryingMapper() {
        boolean result = domesticPurchaseEligibilityService.isPurchasable(Type.STOCK, null, null);

        assertThat(result).isTrue();
        verifyNoInteractions(domesticProductMapper);
    }

    @Test
    @DisplayName("(오버로드) FUND는 이미 알고 있는 비중·설정일 값만으로 판정하고 mapper를 조회하지 않는다")
    void isPurchasableWithKnownValuesReturnsTrueForFundWhenBothConditionsMet() {
        when(businessClockService.now()).thenReturn(TODAY.atStartOfDay());

        boolean result =
                domesticPurchaseEligibilityService.isPurchasable(
                        Type.FUND, BigDecimal.valueOf(85.00), TODAY.minusMonths(2));

        assertThat(result).isTrue();
        verifyNoInteractions(domesticProductMapper);
    }

    @Test
    @DisplayName("(오버로드) FUND의 국내주식비중이 80% 미만이면 매수 불가하다")
    void isPurchasableWithKnownValuesReturnsFalseWhenRatioBelowThreshold() {
        when(businessClockService.now()).thenReturn(TODAY.atStartOfDay());

        boolean result =
                domesticPurchaseEligibilityService.isPurchasable(
                        Type.FUND, BigDecimal.valueOf(79.99), TODAY.minusMonths(2));

        assertThat(result).isFalse();
        verifyNoInteractions(domesticProductMapper);
    }
}
