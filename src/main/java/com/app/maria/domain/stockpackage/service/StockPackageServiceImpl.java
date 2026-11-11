package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.domestic.service.DomesticPurchaseEligibilityService;
import com.app.maria.domain.investorassessment.dto.response.InvestorAssessmentStatusResponseDTO;
import com.app.maria.domain.investorassessment.service.InvestorAssessmentService;
import com.app.maria.domain.risk.service.RiskGradeService;
import com.app.maria.domain.stockpackage.dto.request.StockPackageSaveRequestDTO;
import com.app.maria.domain.stockpackage.dto.request.StockPackageSaveRequestDTO.ItemRequest;
import com.app.maria.domain.stockpackage.dto.response.StockPackageResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockPackageSummaryResponseDTO;
import com.app.maria.domain.stockpackage.mapper.StockPackageMapper;
import com.app.maria.domain.stockpackage.type.InvestorRiskRange;
import com.app.maria.domain.stockpackage.vo.StockPackageItemVO;
import com.app.maria.domain.stockpackage.vo.StockPackageVO;
import com.app.maria.domain.stockpackage.vo.StockProductVO;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 투자성향 조회(InvestorAssessmentService)는 JPA 트랜잭션 매니저를 쓰고 이 서비스의 DB 쓰기는 MyBatis(기본 트랜잭션 매니저)를 쓴다. 두
 * 매니저가 같은 DB 연결을 동시에 잡으면 충돌하므로, 성향 조회는 항상 쓰기 트랜잭션 밖에서 먼저 하고 쓰기만 TransactionTemplate으로 묶는다.
 */
@Service
public class StockPackageServiceImpl implements StockPackageService {
    private static final BigDecimal FULL_WEIGHT = BigDecimal.valueOf(100);

    private final StockPackageMapper stockPackageMapper;
    private final InvestorAssessmentService investorAssessmentService;
    private final RiskGradeService riskGradeService;
    private final DomesticPurchaseEligibilityService purchaseEligibilityService;
    private final BusinessClockService businessClockService;
    private final TransactionTemplate tx;

    public StockPackageServiceImpl(
            StockPackageMapper stockPackageMapper,
            InvestorAssessmentService investorAssessmentService,
            RiskGradeService riskGradeService,
            DomesticPurchaseEligibilityService purchaseEligibilityService,
            BusinessClockService businessClockService,
            PlatformTransactionManager transactionManager) {
        this.stockPackageMapper = stockPackageMapper;
        this.investorAssessmentService = investorAssessmentService;
        this.riskGradeService = riskGradeService;
        this.purchaseEligibilityService = purchaseEligibilityService;
        this.businessClockService = businessClockService;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public StockPackageResponseDTO create(Long customerId, StockPackageSaveRequestDTO request) {
        Optional<InvestorRiskRange> range = currentRange(customerId);
        Map<Long, Integer> grades = confirmedGrades(request);
        return tx.execute(status -> doCreate(customerId, request, range, grades));
    }

    private StockPackageResponseDTO doCreate(
            Long customerId,
            StockPackageSaveRequestDTO request,
            Optional<InvestorRiskRange> range,
            Map<Long, Integer> grades) {
        LocalDateTime now = businessClockService.now();
        List<StockPackageItemVO> items =
                buildItems(request, range, grades, Map.of(), Set.of(), now);

        StockPackageVO pkg =
                StockPackageVO.builder()
                        .customerId(customerId)
                        .name(request.name().trim())
                        .totalAmount(request.totalAmount())
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
        stockPackageMapper.insertPackage(pkg);
        saveItems(pkg.getStockPackageId(), items);
        return readDetail(customerId, pkg.getStockPackageId(), range);
    }

    @Override
    public List<StockPackageSummaryResponseDTO> list(Long customerId) {
        return stockPackageMapper.selectSummariesByCustomerId(customerId).stream()
                .map(StockPackageSummaryResponseDTO::new)
                .toList();
    }

    @Override
    public StockPackageResponseDTO get(Long customerId, Long stockPackageId) {
        return readDetail(customerId, stockPackageId, currentRange(customerId));
    }

    @Override
    public StockPackageResponseDTO update(
            Long customerId, Long stockPackageId, StockPackageSaveRequestDTO request) {
        Optional<InvestorRiskRange> range = currentRange(customerId);
        Map<Long, Integer> grades = confirmedGrades(request);
        return tx.execute(status -> doUpdate(customerId, stockPackageId, request, range, grades));
    }

    private StockPackageResponseDTO doUpdate(
            Long customerId,
            Long stockPackageId,
            StockPackageSaveRequestDTO request,
            Optional<InvestorRiskRange> range,
            Map<Long, Integer> grades) {
        StockPackageVO pkg = lockOwnPackage(customerId, stockPackageId);
        List<StockPackageItemVO> existing =
                stockPackageMapper.selectItemsByPackageId(stockPackageId);
        Map<Long, LocalDateTime> confirmedBefore = confirmedAtByProduct(existing);
        Set<Long> keptIds =
                existing.stream()
                        .map(StockPackageItemVO::getDomesticProductId)
                        .collect(Collectors.toSet());
        LocalDateTime now = businessClockService.now();
        List<StockPackageItemVO> items =
                buildItems(request, range, grades, confirmedBefore, keptIds, now);

        pkg.setName(request.name().trim());
        pkg.setTotalAmount(request.totalAmount());
        pkg.setUpdatedAt(now);
        stockPackageMapper.updatePackage(pkg);
        stockPackageMapper.deleteItemsByPackageId(stockPackageId);
        saveItems(stockPackageId, items);
        return readDetail(customerId, stockPackageId, range);
    }

    @Override
    public void delete(Long customerId, Long stockPackageId) {
        tx.executeWithoutResult(
                status -> {
                    lockOwnPackage(customerId, stockPackageId);
                    if (stockPackageMapper.countOrdersByPackageId(stockPackageId) > 0) {
                        throw new AppException(ErrorType.STOCK_PACKAGE_HAS_ORDERS, stockPackageId);
                    }
                    stockPackageMapper.deleteItemsByPackageId(stockPackageId);
                    stockPackageMapper.deletePackage(stockPackageId, customerId);
                });
    }

    private StockPackageVO lockOwnPackage(Long customerId, Long stockPackageId) {
        return stockPackageMapper
                .selectByIdAndCustomerIdForUpdate(stockPackageId, customerId)
                .orElseThrow(
                        () -> new AppException(ErrorType.STOCK_PACKAGE_NOT_FOUND, stockPackageId));
    }

    /** 이미 확인한 성향 밖 종목은 다시 저장해도 처음 확인한 시각을 유지한다. */
    private Map<Long, LocalDateTime> confirmedAtByProduct(List<StockPackageItemVO> existing) {
        Map<Long, LocalDateTime> confirmed = new HashMap<>();
        for (StockPackageItemVO item : existing) {
            if (item.getUnsuitableConfirmedAt() != null) {
                confirmed.put(item.getDomesticProductId(), item.getUnsuitableConfirmedAt());
            }
        }
        return confirmed;
    }

    private void saveItems(Long stockPackageId, List<StockPackageItemVO> items) {
        if (!items.isEmpty()) {
            stockPackageMapper.insertItems(stockPackageId, items);
        }
    }

    /** 저장할 구성 종목을 검증해 만든다. 빈 패키지는 검증할 것이 없다. */
    private List<StockPackageItemVO> buildItems(
            StockPackageSaveRequestDTO request,
            Optional<InvestorRiskRange> profileRange,
            Map<Long, Integer> grades,
            Map<Long, LocalDateTime> confirmedBefore,
            Set<Long> keptIds,
            LocalDateTime now) {
        List<ItemRequest> requested = request.items();
        if (requested.isEmpty()) {
            return List.of();
        }
        List<Long> productIds = requested.stream().map(ItemRequest::domesticProductId).toList();
        if (new HashSet<>(productIds).size() != productIds.size()) {
            throw new AppException(ErrorType.STOCK_PACKAGE_ITEM_DUPLICATED);
        }
        BigDecimal weightSum =
                requested.stream()
                        .map(ItemRequest::weight)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (weightSum.compareTo(FULL_WEIGHT) != 0) {
            throw new AppException(ErrorType.STOCK_PACKAGE_WEIGHT_SUM_INVALID, weightSum);
        }

        InvestorRiskRange range =
                profileRange.orElseThrow(
                        () -> new AppException(ErrorType.STOCK_PACKAGE_PROFILE_REQUIRED));
        Map<Long, StockProductVO> products =
                stockPackageMapper.selectProductsByIds(productIds).stream()
                        .collect(Collectors.toMap(StockProductVO::getDomesticProductId, p -> p));

        return requested.stream()
                .map(item -> toItem(item, products, grades, range, confirmedBefore, keptIds, now))
                .toList();
    }

    private StockPackageItemVO toItem(
            ItemRequest item,
            Map<Long, StockProductVO> products,
            Map<Long, Integer> grades,
            InvestorRiskRange range,
            Map<Long, LocalDateTime> confirmedBefore,
            Set<Long> keptIds,
            LocalDateTime now) {
        Long productId = item.domesticProductId();
        StockProductVO product = products.get(productId);
        if (product == null) {
            throw new AppException(ErrorType.DOMESTIC_PRODUCT_NOT_FOUND, productId);
        }
        if (!purchaseEligibilityService.isPurchasable(
                product.getType(), product.getDomesticStockRatio(), product.getInceptionDate())) {
            throw new AppException(ErrorType.STOCK_PACKAGE_PRODUCT_NOT_PURCHASABLE, productId);
        }
        Integer grade = grades.get(productId);
        // 등급이 없는 종목은 새로 담을 수 없다. 이미 담겨 있던 것은 그대로 두어 다른 수정(이름·비중)이 막히지 않게 한다
        if (grade == null && !keptIds.contains(productId)) {
            throw new AppException(ErrorType.STOCK_PACKAGE_GRADE_UNAVAILABLE, productId);
        }
        LocalDateTime confirmedAt = grade == null ? confirmedBefore.get(productId) : null;
        if (grade != null && !range.allows(grade)) {
            if (!item.unsuitableConfirmed()) {
                throw new AppException(ErrorType.STOCK_PACKAGE_UNSUITABLE_NOT_CONFIRMED, productId);
            }
            confirmedAt = confirmedBefore.getOrDefault(productId, now);
        }
        return StockPackageItemVO.builder()
                .domesticProductId(productId)
                .weight(item.weight())
                .unsuitableConfirmedAt(confirmedAt)
                .createdAt(now)
                .build();
    }

    private StockPackageResponseDTO readDetail(
            Long customerId, Long stockPackageId, Optional<InvestorRiskRange> range) {
        StockPackageVO pkg =
                stockPackageMapper
                        .selectByIdAndCustomerId(stockPackageId, customerId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorType.STOCK_PACKAGE_NOT_FOUND, stockPackageId));
        List<StockPackageItemVO> items = stockPackageMapper.selectItemsByPackageId(stockPackageId);
        if (!items.isEmpty()) {
            Map<Long, Integer> grades =
                    gradeByProduct(
                            items.stream().map(StockPackageItemVO::getDomesticProductId).toList());
            for (StockPackageItemVO item : items) {
                Integer grade = grades.get(item.getDomesticProductId());
                item.setRiskGrade(grade);
                // 등급이 없거나 성향을 판단할 수 없으면 범위 밖인지 알 수 없어 null로 둔다.
                if (grade != null && range.isPresent()) {
                    item.setOutOfProfile(!range.get().allows(grade));
                }
            }
        }
        pkg.setItems(items);
        return new StockPackageResponseDTO(pkg);
    }

    /** 유효한 진단이 있는 고객의 성향 범위. 진단이 없거나 만료되면 비어 있다. */
    private Optional<InvestorRiskRange> currentRange(Long customerId) {
        InvestorAssessmentStatusResponseDTO status = investorAssessmentService.getMine(customerId);
        if (!status.isAssessed() || status.isExpired()) {
            return Optional.empty();
        }
        return InvestorRiskRange.of(status.getInvestorType());
    }

    /** 저장할 종목의 등급. 주식은 실시간으로 다시 확인해 KIS를 기다리므로, 패키지 행을 잠그는 트랜잭션에 들어가기 전에 부른다. */
    private Map<Long, Integer> confirmedGrades(StockPackageSaveRequestDTO request) {
        if (request.items().isEmpty()) return Map.of();
        return riskGradeService.findConfirmedGrades(
                request.items().stream().map(ItemRequest::domesticProductId).toList());
    }

    private Map<Long, Integer> gradeByProduct(java.util.Collection<Long> productIds) {
        return riskGradeService.findUsableGrades(productIds);
    }
}
