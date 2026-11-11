package com.app.maria.domain.stockpackage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.domestic.service.DomesticPurchaseEligibilityService;
import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.investorassessment.dto.response.InvestorAssessmentStatusResponseDTO;
import com.app.maria.domain.investorassessment.service.InvestorAssessmentService;
import com.app.maria.domain.risk.dto.UsableRiskGradeDTO;
import com.app.maria.domain.risk.service.RiskGradeService;
import com.app.maria.domain.risk.type.RiskDataStatus;
import com.app.maria.domain.stockpackage.dto.request.StockPackageSaveRequestDTO;
import com.app.maria.domain.stockpackage.dto.request.StockPackageSaveRequestDTO.ItemRequest;
import com.app.maria.domain.stockpackage.mapper.StockPackageMapper;
import com.app.maria.domain.stockpackage.vo.StockPackageItemVO;
import com.app.maria.domain.stockpackage.vo.StockPackageVO;
import com.app.maria.domain.stockpackage.vo.StockProductVO;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockPackageServiceImplTest {
    private static final Long CUSTOMER_ID = 1L;
    private static final Long PACKAGE_ID = 10L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 10, 0);

    @Mock private StockPackageMapper mapper;
    @Mock private InvestorAssessmentService assessmentService;
    @Mock private RiskGradeService riskGradeService;
    @Mock private DomesticPurchaseEligibilityService eligibilityService;
    @Mock private BusinessClockService clock;
    @Mock private PlatformTransactionManager transactionManager;
    @InjectMocks private StockPackageServiceImpl service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient()
                .doCallRealMethod()
                .when(riskGradeService)
                .findUsableGrades(org.mockito.ArgumentMatchers.anyCollection());
        org.mockito.Mockito.doCallRealMethod().when(riskGradeService).findUsableGrades();
        org.mockito.Mockito.lenient()
                .doCallRealMethod()
                .when(riskGradeService)
                .findConfirmedGrades(org.mockito.ArgumentMatchers.anyCollection());
        when(clock.now()).thenReturn(NOW);
        // 기본: 중립형(4등급까지), 유효한 진단, 종목 1(5등급)과 2(2등급), 모두 구매 가능
        when(assessmentService.getMine(CUSTOMER_ID)).thenReturn(assessed(InvestorType.NEUTRAL));
        when(riskGradeService.findUsable()).thenReturn(List.of(grade(1L, 5), grade(2L, 2)));
        when(mapper.selectProductsByIds(anyList())).thenReturn(List.of(product(1L), product(2L)));
        when(eligibilityService.isPurchasable(any(Type.class), any(), any())).thenReturn(true);
        when(mapper.selectByIdAndCustomerId(eq(PACKAGE_ID), eq(CUSTOMER_ID)))
                .thenReturn(Optional.of(pkg()));
        when(mapper.selectByIdAndCustomerIdForUpdate(eq(PACKAGE_ID), eq(CUSTOMER_ID)))
                .thenReturn(Optional.of(pkg()));
        when(mapper.selectItemsByPackageId(PACKAGE_ID)).thenReturn(List.of());
    }

    @Test
    @DisplayName("성향 안 종목으로 비중 합 100이면 만들어진다")
    void createOk() {
        StockPackageSaveRequestDTO request = request(item(1L, "100", false));
        doInsertAssigningId();

        service.create(CUSTOMER_ID, request);

        ArgumentCaptor<List<StockPackageItemVO>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).insertItems(eq(PACKAGE_ID), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getUnsuitableConfirmedAt()).isNull();
    }

    @Test
    @DisplayName("빈 패키지는 성향이나 등급을 검사하지 않고 만들어진다")
    void createEmpty() {
        when(assessmentService.getMine(CUSTOMER_ID))
                .thenReturn(InvestorAssessmentStatusResponseDTO.unassigned());
        doInsertAssigningId();

        service.create(CUSTOMER_ID, request());

        verify(mapper, never()).insertItems(any(), anyList());
    }

    @Test
    @DisplayName("비중 합이 100이 아니면 거부한다")
    void weightSumInvalid() {
        assertError(request(item(1L, "60", false)), ErrorType.STOCK_PACKAGE_WEIGHT_SUM_INVALID);
    }

    @Test
    @DisplayName("같은 종목을 두 번 담으면 거부한다")
    void duplicated() {
        assertError(
                request(item(1L, "50", false), item(1L, "50", false)),
                ErrorType.STOCK_PACKAGE_ITEM_DUPLICATED);
    }

    @Test
    @DisplayName("진단이 없거나 만료되면 종목을 담을 수 없다")
    void profileRequired() {
        when(assessmentService.getMine(CUSTOMER_ID))
                .thenReturn(InvestorAssessmentStatusResponseDTO.unassigned());
        assertError(request(item(1L, "100", false)), ErrorType.STOCK_PACKAGE_PROFILE_REQUIRED);

        when(assessmentService.getMine(CUSTOMER_ID))
                .thenReturn(
                        InvestorAssessmentStatusResponseDTO.of(
                                InvestorType.NEUTRAL, NOW.minusYears(3), NOW.minusYears(1), true));
        assertError(request(item(1L, "100", false)), ErrorType.STOCK_PACKAGE_PROFILE_REQUIRED);
    }

    @Test
    @DisplayName("성향 밖 종목은 확인 없이는 거부하고 확인하면 시각이 기록된다")
    void unsuitable() {
        assertError(
                request(item(2L, "100", false)), ErrorType.STOCK_PACKAGE_UNSUITABLE_NOT_CONFIRMED);

        doInsertAssigningId();
        service.create(CUSTOMER_ID, request(item(2L, "100", true)));

        ArgumentCaptor<List<StockPackageItemVO>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).insertItems(eq(PACKAGE_ID), captor.capture());
        assertThat(captor.getValue().get(0).getUnsuitableConfirmedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("저장할 때는 목록용 저장값이 아니라 확정용 등급(주식 실시간 재확인)으로 성향을 판단한다")
    void saveUsesConfirmedGradesNotStoredListGrades() {
        // 저장값은 5등급(성향 안)이지만 배치 뒤에 투자경고로 지정돼 실시간은 1등급
        org.mockito.Mockito.doReturn(java.util.Map.of(1L, 1))
                .when(riskGradeService)
                .findConfirmedGrades(org.mockito.ArgumentMatchers.anyCollection());

        assertError(
                request(item(1L, "100", false)), ErrorType.STOCK_PACKAGE_UNSUITABLE_NOT_CONFIRMED);
    }

    @Test
    @DisplayName("수정할 때 이미 확인한 종목은 처음 확인 시각을 유지한다")
    void updateKeepsConfirmedAt() {
        LocalDateTime first = NOW.minusDays(5);
        when(mapper.selectItemsByPackageId(PACKAGE_ID))
                .thenReturn(
                        List.of(
                                StockPackageItemVO.builder()
                                        .domesticProductId(2L)
                                        .unsuitableConfirmedAt(first)
                                        .build()));

        service.update(CUSTOMER_ID, PACKAGE_ID, request(item(2L, "100", true)));

        ArgumentCaptor<List<StockPackageItemVO>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).insertItems(eq(PACKAGE_ID), captor.capture());
        assertThat(captor.getValue().get(0).getUnsuitableConfirmedAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("구매 불가 종목은 거부한다")
    void notPurchasable() {
        when(eligibilityService.isPurchasable(any(Type.class), any(), any())).thenReturn(false);
        assertError(
                request(item(1L, "100", false)), ErrorType.STOCK_PACKAGE_PRODUCT_NOT_PURCHASABLE);
    }

    @Test
    @DisplayName("위험등급이 없는 종목은 거부한다")
    void gradeUnavailable() {
        when(riskGradeService.findUsable()).thenReturn(List.of());
        assertError(request(item(1L, "100", false)), ErrorType.STOCK_PACKAGE_GRADE_UNAVAILABLE);
    }

    @Test
    @DisplayName("수정할 때 이미 담겨 있던 등급 없는 종목은 그대로 둘 수 있다")
    void updateKeepsUngradedItem() {
        when(riskGradeService.findUsable()).thenReturn(List.of());
        when(mapper.selectItemsByPackageId(PACKAGE_ID))
                .thenReturn(List.of(StockPackageItemVO.builder().domesticProductId(1L).build()));

        service.update(CUSTOMER_ID, PACKAGE_ID, request(item(1L, "100", false)));

        ArgumentCaptor<List<StockPackageItemVO>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).insertItems(eq(PACKAGE_ID), captor.capture());
        assertThat(captor.getValue())
                .extracting(StockPackageItemVO::getDomesticProductId)
                .containsExactly(1L);
    }

    @Test
    @DisplayName("수정으로 새로 담는 등급 없는 종목은 거부한다")
    void updateRejectsNewUngradedItem() {
        when(riskGradeService.findUsable()).thenReturn(List.of());
        when(mapper.selectItemsByPackageId(PACKAGE_ID))
                .thenReturn(List.of(StockPackageItemVO.builder().domesticProductId(2L).build()));

        assertThatThrownBy(
                        () ->
                                service.update(
                                        CUSTOMER_ID, PACKAGE_ID, request(item(1L, "100", false))))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.STOCK_PACKAGE_GRADE_UNAVAILABLE));
    }

    @Test
    @DisplayName("없는 종목이면 종목 없음으로 거부한다")
    void productNotFound() {
        when(mapper.selectProductsByIds(anyList())).thenReturn(List.of());
        assertError(request(item(1L, "100", false)), ErrorType.DOMESTIC_PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("남의 패키지는 조회·수정·삭제 모두 없는 것처럼 404")
    void notOwner() {
        when(assessmentService.getMine(2L)).thenReturn(assessed(InvestorType.NEUTRAL));
        when(mapper.selectByIdAndCustomerId(eq(PACKAGE_ID), eq(2L))).thenReturn(Optional.empty());
        when(mapper.selectByIdAndCustomerIdForUpdate(eq(PACKAGE_ID), eq(2L)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(2L, PACKAGE_ID))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.STOCK_PACKAGE_NOT_FOUND));
        assertThatThrownBy(() -> service.update(2L, PACKAGE_ID, request()))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.delete(2L, PACKAGE_ID)).isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("주문이 있는 패키지는 삭제할 수 없고 없으면 삭제된다")
    void delete() {
        when(mapper.countOrdersByPackageId(PACKAGE_ID)).thenReturn(1);
        assertThatThrownBy(() -> service.delete(CUSTOMER_ID, PACKAGE_ID))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.STOCK_PACKAGE_HAS_ORDERS));
        verify(mapper, never()).deletePackage(any(), any());

        when(mapper.countOrdersByPackageId(PACKAGE_ID)).thenReturn(0);
        service.delete(CUSTOMER_ID, PACKAGE_ID);
        verify(mapper).deletePackage(PACKAGE_ID, CUSTOMER_ID);
    }

    @Test
    @DisplayName("상세 조회는 종목별 등급과 성향 밖 여부를 채운다")
    void detailFillsGrade() {
        when(mapper.selectItemsByPackageId(PACKAGE_ID))
                .thenReturn(
                        List.of(
                                StockPackageItemVO.builder().domesticProductId(1L).build(),
                                StockPackageItemVO.builder().domesticProductId(2L).build()));

        var detail = service.get(CUSTOMER_ID, PACKAGE_ID);

        assertThat(detail.getItems().get(0).getRiskGrade()).isEqualTo(5);
        assertThat(detail.getItems().get(0).getOutOfProfile()).isFalse();
        assertThat(detail.getItems().get(1).getRiskGrade()).isEqualTo(2);
        assertThat(detail.getItems().get(1).getOutOfProfile()).isTrue();
    }

    private void assertError(StockPackageSaveRequestDTO request, ErrorType expected) {
        assertThatThrownBy(() -> service.create(CUSTOMER_ID, request))
                .isInstanceOfSatisfying(
                        AppException.class, e -> assertThat(e.getErrorType()).isEqualTo(expected));
    }

    private void doInsertAssigningId() {
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            ((StockPackageVO) inv.getArgument(0)).setStockPackageId(PACKAGE_ID);
                            return 1;
                        })
                .when(mapper)
                .insertPackage(any(StockPackageVO.class));
    }

    private static StockPackageSaveRequestDTO request(ItemRequest... items) {
        return new StockPackageSaveRequestDTO("내 패키지", null, List.of(items));
    }

    private static ItemRequest item(Long productId, String weight, boolean confirmed) {
        return new ItemRequest(productId, new BigDecimal(weight), confirmed);
    }

    private static InvestorAssessmentStatusResponseDTO assessed(InvestorType type) {
        return InvestorAssessmentStatusResponseDTO.of(
                type, NOW.minusMonths(1), NOW.plusMonths(23), false);
    }

    private static UsableRiskGradeDTO grade(Long productId, int grade) {
        return new UsableRiskGradeDTO(
                productId,
                "T" + productId,
                "종목" + productId,
                new BigDecimal("0.3"),
                grade,
                new BigDecimal("0.2"),
                LocalDate.of(2023, 11, 1),
                LocalDate.of(2026, 11, 2),
                1L,
                RiskDataStatus.CURRENT);
    }

    private static StockProductVO product(Long id) {
        return StockProductVO.builder()
                .domesticProductId(id)
                .ticker("T" + id)
                .name("종목" + id)
                .type(Type.STOCK)
                .build();
    }

    private static StockPackageVO pkg() {
        return StockPackageVO.builder()
                .stockPackageId(PACKAGE_ID)
                .customerId(CUSTOMER_ID)
                .name("내 패키지")
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }
}
