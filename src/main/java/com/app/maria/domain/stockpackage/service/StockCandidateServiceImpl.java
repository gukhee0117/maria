package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.domestic.service.DomesticPurchaseEligibilityService;
import com.app.maria.domain.investorassessment.dto.response.InvestorAssessmentStatusResponseDTO;
import com.app.maria.domain.investorassessment.service.InvestorAssessmentService;
import com.app.maria.domain.risk.dto.UsableRiskGradeDTO;
import com.app.maria.domain.risk.service.RiskGradeService;
import com.app.maria.domain.stockpackage.dto.request.StockCandidateRequestDTO;
import com.app.maria.domain.stockpackage.dto.response.StockCandidateResponseDTO;
import com.app.maria.domain.stockpackage.mapper.StockCandidateMapper;
import com.app.maria.domain.stockpackage.mapper.StockPackageMapper;
import com.app.maria.domain.stockpackage.type.CandidateSort;
import com.app.maria.domain.stockpackage.type.InvestorRiskRange;
import com.app.maria.domain.stockpackage.type.SortDirection;
import com.app.maria.domain.stockpackage.type.StockPackageSentence;
import com.app.maria.domain.stockpackage.vo.StockCandidateListVO;
import com.app.maria.domain.stockpackage.vo.StockCandidateListVO.Row;
import com.app.maria.domain.stockpackage.vo.StockCandidateProductVO;
import com.app.maria.domain.stockpackage.vo.StockProductThemeVO;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StockCandidateServiceImpl implements StockCandidateService {
    /** 한 번에 돌려주는 최대 개수. 넘으면 앞부분만 주고 truncated로 알린다. */
    public static final int MAX_ITEMS = 200;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final StockCandidateMapper candidateMapper;
    private final StockPackageMapper stockPackageMapper;
    private final InvestorAssessmentService investorAssessmentService;
    private final RiskGradeService riskGradeService;
    private final DomesticPurchaseEligibilityService purchaseEligibilityService;
    private final BusinessClockService businessClockService;

    @Override
    public StockCandidateResponseDTO search(Long customerId, StockCandidateRequestDTO request) {
        Integer minGrade = request.minGrade();
        Integer maxGrade = request.maxGrade();
        if (minGrade != null && maxGrade != null && minGrade > maxGrade) {
            throw new AppException(ErrorType.STOCK_PACKAGE_CANDIDATE_FILTER_INVALID);
        }
        CandidateSort sort = request.sort() == null ? CandidateSort.NAME : request.sort();
        SortDirection direction =
                request.direction() == null ? SortDirection.ASC : request.direction();

        Optional<InvestorRiskRange> range = currentRange(customerId);
        // 진단이 유효하고 범위 밖 포함을 켜지 않았으면 성향 범위 안의 상품만 본다.
        boolean restrictToProfile = range.isPresent() && !request.includeOutOfProfile();
        boolean gradeFiltered = minGrade != null || maxGrade != null;

        List<String> types =
                request.productTypes() == null
                        ? List.of()
                        : request.productTypes().stream().map(Enum::name).sorted().toList();
        List<String> themes =
                request.themes() == null
                        ? List.of()
                        : request.themes().stream().map(String::trim).distinct().toList();
        List<StockCandidateProductVO> products =
                candidateMapper.selectCandidateProducts(
                        types, themes, escapeLike(request.keyword()));

        List<UsableRiskGradeDTO> metricRows;
        try {
            metricRows = riskGradeService.findUsable();
        } catch (AppException e) {
            if (!e.getErrorType().name().startsWith("RISK_")) throw e;
            metricRows = List.of();
        }
        Map<Long, UsableRiskGradeDTO> grades =
                metricRows.stream()
                        .collect(
                                Collectors.toMap(
                                        UsableRiskGradeDTO::domesticProductId,
                                        g -> g,
                                        (a, b) -> a));

        // 주식·펀드 모두 저장값이라 위에서 읽은 행에서 바로 등급을 꺼낸다(findUsable을 두 번 읽지 않는다).
        Map<Long, Integer> classifications =
                grades.values().stream()
                        .collect(
                                Collectors.toMap(
                                        UsableRiskGradeDTO::domesticProductId,
                                        UsableRiskGradeDTO::riskGrade));
        List<StockCandidateProductVO> matched = new ArrayList<>();
        for (StockCandidateProductVO p : products) {
            if (!purchaseEligibilityService.isPurchasable(
                    p.getType(), p.getDomesticStockRatio(), p.getInceptionDate())) {
                continue;
            }
            UsableRiskGradeDTO g = grades.get(p.getDomesticProductId());
            if (!classifications.containsKey(p.getDomesticProductId())) {
                // 등급이 없는 상품은 등급 조건이나 성향 범위를 판단할 수 없어, 그런 조건이 없을 때만 보인다.
                if (gradeFiltered || restrictToProfile) {
                    continue;
                }
            } else {
                int grade = classifications.get(p.getDomesticProductId());
                if ((minGrade != null && grade < minGrade)
                        || (maxGrade != null && grade > maxGrade)
                        || (restrictToProfile && !range.get().allows(grade))) {
                    continue;
                }
            }
            matched.add(p);
        }

        List<Long> ids =
                matched.stream().map(StockCandidateProductVO::getDomesticProductId).toList();
        Map<Long, List<String>> themesByProduct = new HashMap<>();
        if (!ids.isEmpty()) {
            for (StockProductThemeVO t : stockPackageMapper.selectThemesByProductIds(ids)) {
                themesByProduct
                        .computeIfAbsent(t.getDomesticProductId(), k -> new ArrayList<>())
                        .add(t.getThemeName());
            }
        }

        List<Row> rows = new ArrayList<>();
        for (StockCandidateProductVO p : matched) {
            UsableRiskGradeDTO g = grades.get(p.getDomesticProductId());
            Integer grade = classifications.get(p.getDomesticProductId());
            rows.add(
                    new Row(
                            p.getDomesticProductId(),
                            p.getTicker(),
                            p.getName(),
                            p.getMarket(),
                            p.getType(),
                            p.getSectorName(),
                            themesByProduct.getOrDefault(p.getDomesticProductId(), List.of()),
                            grade,
                            g == null || g.volatility() == null
                                    ? null
                                    : g.volatility()
                                            .multiply(HUNDRED)
                                            .setScale(1, RoundingMode.HALF_UP),
                            grade != null && range.isPresent() ? !range.get().allows(grade) : null,
                            grade != null,
                            grade == null
                                    ? StockPackageSentence.CANDIDATE_UNSELECTABLE_NO_GRADE.render(
                                            Map.of())
                                    : null));
        }
        // 범위 밖도 함께 보면서 종목명순일 때는 범위 안 → 범위 밖 → 등급이 없어 범위를 모르는(담을 수 없는) 상품 순서로 묶고, 묶음 안에서는
        // 종목명순을 따른다. 위험등급순·변동성순은 고객이 값 순서를 고른 것이라 묶지 않는다
        boolean profileFirst =
                range.isPresent() && request.includeOutOfProfile() && sort == CandidateSort.NAME;
        Comparator<Row> order = comparator(sort, direction);
        if (profileFirst) {
            order =
                    Comparator.comparingInt(StockCandidateServiceImpl::profileGroup)
                            .thenComparing(order);
        }
        rows.sort(order);

        int total = rows.size();
        boolean truncated = total > MAX_ITEMS;
        List<Row> shown = truncated ? List.copyOf(rows.subList(0, MAX_ITEMS)) : List.copyOf(rows);

        return new StockCandidateResponseDTO(
                StockCandidateListVO.builder()
                        .items(shown)
                        .totalCount(total)
                        .truncated(truncated)
                        .profileAvailable(range.isPresent())
                        .allowedMinGrade(range.map(InvestorRiskRange::minGrade).orElse(null))
                        .includeOutOfProfile(request.includeOutOfProfile())
                        .sort(sort.name())
                        .direction(direction.name())
                        .summary(
                                summary(
                                        total,
                                        shown.size(),
                                        sort,
                                        direction,
                                        range.isPresent(),
                                        request.includeOutOfProfile(),
                                        profileFirst))
                        .disclaimer(StockPackageCheckServiceImpl.DISCLAIMER)
                        .checkedAt(businessClockService.now())
                        .build());
    }

    @Override
    public List<String> themeNames() {
        return candidateMapper.selectThemeNames();
    }

    /** 0: 성향 범위 안, 1: 범위 밖, 2: 등급이 없어 범위를 판단할 수 없음. */
    private static int profileGroup(Row r) {
        if (r.outOfProfile() == null) {
            return 2;
        }
        return r.outOfProfile() ? 1 : 0;
    }

    /** 값이 없는 쪽은 정렬 방향과 상관없이 맨 뒤에 둔다. 같은 값이면 종목명, 종목코드 순이다. */
    private static Comparator<Row> comparator(CandidateSort sort, SortDirection direction) {
        Comparator<Row> byName = Comparator.comparing(Row::name).thenComparing(Row::ticker);
        if (sort == CandidateSort.NAME) {
            return direction == SortDirection.ASC ? byName : byName.reversed();
        }
        Comparator<Row> byValue =
                sort == CandidateSort.RISK_GRADE
                        ? Comparator.comparing(
                                Row::riskGrade,
                                Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
                        : Comparator.comparing(
                                Row::volatilityPercent,
                                Comparator.nullsLast(Comparator.<BigDecimal>naturalOrder()));
        if (direction == SortDirection.DESC) {
            byValue =
                    sort == CandidateSort.RISK_GRADE
                            ? Comparator.comparing(
                                    Row::riskGrade,
                                    Comparator.nullsLast(Comparator.<Integer>reverseOrder()))
                            : Comparator.comparing(
                                    Row::volatilityPercent,
                                    Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder()));
        }
        return byValue.thenComparing(byName);
    }

    private static List<String> summary(
            int total,
            int shown,
            CandidateSort sort,
            SortDirection direction,
            boolean profileAvailable,
            boolean includeOutOfProfile,
            boolean profileFirst) {
        List<String> lines = new ArrayList<>();
        if (total == 0) {
            lines.add(StockPackageSentence.CANDIDATE_EMPTY.render(Map.of()));
        } else if (shown < total) {
            lines.add(
                    StockPackageSentence.CANDIDATE_COUNT_TRUNCATED.render(
                            Map.of(
                                    "count",
                                    String.valueOf(total),
                                    "shown",
                                    String.valueOf(shown))));
        } else {
            lines.add(
                    StockPackageSentence.CANDIDATE_COUNT.render(
                            Map.of("count", String.valueOf(total))));
        }
        boolean asc = direction == SortDirection.ASC;
        StockPackageSentence sortSentence =
                switch (sort) {
                    case NAME ->
                            asc
                                    ? StockPackageSentence.CANDIDATE_SORT_NAME_ASC
                                    : StockPackageSentence.CANDIDATE_SORT_NAME_DESC;
                    case RISK_GRADE ->
                            asc
                                    ? StockPackageSentence.CANDIDATE_SORT_GRADE_ASC
                                    : StockPackageSentence.CANDIDATE_SORT_GRADE_DESC;
                    case VOLATILITY ->
                            asc
                                    ? StockPackageSentence.CANDIDATE_SORT_VOLATILITY_ASC
                                    : StockPackageSentence.CANDIDATE_SORT_VOLATILITY_DESC;
                };
        lines.add(sortSentence.render(Map.of()));
        lines.add(StockPackageSentence.CANDIDATE_SORT_NOTE.render(Map.of()));
        lines.add(
                (!profileAvailable
                                ? StockPackageSentence.CANDIDATE_PROFILE_UNKNOWN
                                : includeOutOfProfile
                                        ? StockPackageSentence.CANDIDATE_PROFILE_ALL
                                        : StockPackageSentence.CANDIDATE_PROFILE_ONLY)
                        .render(Map.of()));
        if (profileFirst) {
            lines.add(StockPackageSentence.CANDIDATE_PROFILE_FIRST.render(Map.of()));
        }
        return List.copyOf(lines);
    }

    /** LIKE 특수문자를 일반 문자로 취급하도록 이스케이프한다. */
    private static String escapeLike(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return keyword.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private Optional<InvestorRiskRange> currentRange(Long customerId) {
        InvestorAssessmentStatusResponseDTO status = investorAssessmentService.getMine(customerId);
        if (!status.isAssessed() || status.isExpired()) {
            return Optional.empty();
        }
        return InvestorRiskRange.of(status.getInvestorType());
    }
}
