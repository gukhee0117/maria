package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.stockpackage.ai.StockRecommendationAnswerer;
import com.app.maria.domain.stockpackage.dto.request.StockCandidateRequestDTO;
import com.app.maria.domain.stockpackage.dto.request.StockRecommendationAskRequestDTO;
import com.app.maria.domain.stockpackage.dto.response.StockCandidateResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockRecommendationAskResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockRecommendationResponseDTO;
import com.app.maria.domain.stockpackage.type.InvestorRiskRange;
import com.app.maria.domain.stockpackage.vo.StockRecommendationVO;
import com.app.maria.domain.stockpackage.vo.StockRecommendationVO.Item;
import com.app.maria.global.clock.service.BusinessClockService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 성향별 추천. 후보 보기와 같은 규칙으로 "내 성향 범위 안 + RIA 계좌에서 살 수 있고 위험등급이 계산된" 상품만 고른 뒤, 내 성향이 허용하는 가장 높은 위험에 가까운
 * 순서로 담는다. 같은 거리 안에서는 업무일마다 달라지는 고정 순서를 쓰고, 업종이 겹치지 않게 먼저 채운다.
 *
 * <p>이유와 답변은 서버가 가진 사실(등급·변동성·테마·업종)만으로 만든다. 가격 전망, 수익률, 실적은 말하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class StockRecommendationServiceImpl implements StockRecommendationService {
    static final String DISCLAIMER =
            "교육용 프로젝트의 자체 분석 결과예요. 수익을 보장하지 않으며, 실제 금융상품 판매·권유 절차를 대체하지 않아요.";
    static final String FALLBACK_ANSWER =
            "지금은 그 질문에 답할 수 있는 정보가 없어요. 추천 종목의 추천 이유, 위험등급, 변동성, 비교에 대해 물어봐 주세요.";
    static final int MAX_LIMIT = 12;

    private static final Pattern GRADE_Q = Pattern.compile("등급|위험");
    private static final Pattern VOL_Q = Pattern.compile("변동성");
    private static final Pattern DIFF_Q = Pattern.compile("ETF|etf|펀드|주식\\s*차이|차이");
    private static final Pattern COMPARE_Q = Pattern.compile("비교");
    private static final Pattern WHY_Q = Pattern.compile("왜|이유|고른|골랐");

    private final StockCandidateService candidateService;
    private final StockRecommendationAnswerer answerer;
    private final BusinessClockService businessClockService;

    @Override
    public StockRecommendationResponseDTO recommend(Long customerId, String filter, int limit) {
        return new StockRecommendationResponseDTO(
                build(customerId, normalizeFilter(filter), limit));
    }

    @Override
    public StockRecommendationAskResponseDTO ask(
            Long customerId, StockRecommendationAskRequestDTO request) {
        StockRecommendationVO rec = build(customerId, "ALL", 6);
        String question = request.question().trim();
        List<Item> items = rec.getItems();
        if (!rec.isProfileAvailable() || items.isEmpty()) {
            String label = rec.getProfileLabel();
            String text =
                    !rec.isProfileAvailable()
                            ? "투자성향 진단이 없거나 만료돼서 추천할 수 없어요. 내 정보에서 투자성향을 진단해 주세요."
                            : label
                                    + "의 허용 범위 안에서 RIA 계좌로 살 수 있고 위험등급이 계산된 상품이 지금은 없어요. 위험등급은 1등급(매우 높은 위험)부터 6등급(매우 낮은 위험)까지이고, 과거 3년 가격으로 계산해요.";
            return new StockRecommendationAskResponseDTO(text, null, false, DISCLAIMER);
        }

        Item hit = findMentioned(items, question);
        Item focus = hit != null ? hit : findByTicker(items, request.focusTicker());

        Optional<String> rule = ruleAnswer(question, rec, items, hit, focus);
        if (rule.isPresent()) {
            return new StockRecommendationAskResponseDTO(
                    rule.get(), focus == null ? null : focus.ticker(), false, DISCLAIMER);
        }
        Optional<String> ai = answerer.answer(question, facts(rec, items));
        if (ai.isPresent()) {
            return new StockRecommendationAskResponseDTO(
                    ai.get(), focus == null ? null : focus.ticker(), true, DISCLAIMER);
        }
        return new StockRecommendationAskResponseDTO(FALLBACK_ANSWER, null, false, DISCLAIMER);
    }

    // ───── 추천 목록 ─────

    private StockRecommendationVO build(Long customerId, String filter, int limit) {
        int size = Math.max(1, Math.min(MAX_LIMIT, limit));
        Set<Type> types =
                switch (filter) {
                    case "STOCK" -> Set.of(Type.STOCK);
                    case "FUND" -> Set.of(Type.FUND);
                    default -> null;
                };
        StockCandidateResponseDTO candidates =
                candidateService.search(
                        customerId,
                        new StockCandidateRequestDTO(
                                types, null, null, null, null, false, null, null));
        LocalDateTime now = businessClockService.now();

        if (!candidates.isProfileAvailable()) {
            return StockRecommendationVO.builder()
                    .items(List.of())
                    .profileAvailable(false)
                    .filter(filter)
                    .subtitle("투자성향 진단이 필요해요")
                    .disclaimer(DISCLAIMER)
                    .checkedAt(now)
                    .build();
        }
        int minGrade = candidates.getAllowedMinGrade();
        String label = labelOf(minGrade);
        LocalDate day = now.toLocalDate();

        List<StockCandidateResponseDTO.ItemResponse> pool =
                candidates.getItems().stream()
                        .filter(StockCandidateResponseDTO.ItemResponse::isSelectable)
                        .filter(i -> i.getRiskGrade() != null)
                        .sorted(
                                Comparator.comparingInt(
                                                (StockCandidateResponseDTO.ItemResponse i) ->
                                                        i.getRiskGrade() - minGrade)
                                        .thenComparingInt(i -> shuffle(i.getTicker(), day)))
                        .toList();

        List<StockCandidateResponseDTO.ItemResponse> picked = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (StockCandidateResponseDTO.ItemResponse i : pool) {
            if (picked.size() >= size) {
                break;
            }
            if (seen.add(sectorKey(i))) {
                picked.add(i);
            }
        }
        for (StockCandidateResponseDTO.ItemResponse i : pool) {
            if (picked.size() >= size) {
                break;
            }
            if (!picked.contains(i)) {
                picked.add(i);
            }
        }
        picked.sort(
                Comparator.comparingInt(
                                (StockCandidateResponseDTO.ItemResponse i) ->
                                        i.getRiskGrade() - minGrade)
                        .thenComparingInt(i -> shuffle(i.getTicker(), day)));

        List<Item> items = picked.stream().map(i -> toItem(i, label, minGrade)).toList();
        return StockRecommendationVO.builder()
                .items(items)
                .profileAvailable(true)
                .profileLabel(label)
                .allowedMinGrade(minGrade)
                .filter(filter)
                .subtitle(label + " · " + allowedText(minGrade) + " 안에서")
                .disclaimer(DISCLAIMER)
                .checkedAt(now)
                .build();
    }

    private static Item toItem(
            StockCandidateResponseDTO.ItemResponse i, String label, int minGrade) {
        List<String> reasons = new ArrayList<>();
        reasons.add(
                label
                        + " 허용 범위("
                        + allowedText(minGrade)
                        + ") 안의 "
                        + i.getRiskGrade()
                        + "등급("
                        + gradeName(i.getRiskGrade())
                        + ") 상품이에요.");
        reasons.add(
                "RIA 계좌에서 살 수 있는 국내 상장 " + (i.getType() == Type.FUND ? "펀드·ETF" : "주식") + "이에요.");
        if (i.getVolatilityPercent() != null) {
            reasons.add(
                    "과거 3년 가격으로 계산한 연 변동성은 "
                            + i.getVolatilityPercent().stripTrailingZeros().toPlainString()
                            + "%예요.");
        }
        if (!i.getThemes().isEmpty()) {
            reasons.add(String.join(" · ", i.getThemes()) + " 테마에 속해요.");
        } else if (i.getSectorName() != null) {
            reasons.add(i.getSectorName() + " 업종이에요.");
        }
        return new Item(
                i.getDomesticProductId(),
                i.getTicker(),
                i.getName(),
                i.getMarket(),
                i.getType(),
                i.getSectorName(),
                i.getThemes(),
                i.getRiskGrade(),
                i.getVolatilityPercent(),
                List.copyOf(reasons));
    }

    // ───── 질문 답변 ─────

    private Optional<String> ruleAnswer(
            String q, StockRecommendationVO rec, List<Item> items, Item hit, Item focus) {
        if (WHY_Q.matcher(q).find() && focus != null) {
            return Optional.of(String.join(" ", focus.reasons()));
        }
        if (GRADE_Q.matcher(q).find() && !COMPARE_Q.matcher(q).find()) {
            return Optional.of(
                    "위험등급은 1등급(매우 높은 위험)부터 6등급(매우 낮은 위험)까지예요. 과거 3년 가격으로 리턴증권이 자체 산정해요.\n"
                            + items.stream()
                                    .map(i -> i.name() + " " + i.riskGrade() + "등급")
                                    .collect(Collectors.joining(" · "))
                            + "\n"
                            + rec.getProfileLabel()
                            + "은 "
                            + allowedText(rec.getAllowedMinGrade())
                            + " 상품만 추천받을 수 있어요.");
        }
        if (VOL_Q.matcher(q).find()) {
            return Optional.of(
                    items.stream()
                                    .filter(i -> i.volatilityPercent() != null)
                                    .map(
                                            i ->
                                                    i.name()
                                                            + " "
                                                            + i.volatilityPercent()
                                                                    .stripTrailingZeros()
                                                                    .toPlainString()
                                                            + "%")
                                    .collect(Collectors.joining(" · "))
                            + "\n연 변동성은 가격이 1년 동안 오르내린 폭을 과거 3년 가격으로 계산한 값이에요. 앞으로의 수익을 예측하는 값은 아니에요.");
        }
        if (COMPARE_Q.matcher(q).find()) {
            return Optional.of(
                    items.stream()
                            .map(
                                    i ->
                                            i.name()
                                                    + " "
                                                    + i.riskGrade()
                                                    + "등급"
                                                    + (i.volatilityPercent() == null
                                                            ? ""
                                                            : ", 연 변동성 "
                                                                    + i.volatilityPercent()
                                                                            .stripTrailingZeros()
                                                                            .toPlainString()
                                                                    + "%"))
                            .collect(Collectors.joining("\n")));
        }
        if (DIFF_Q.matcher(q).find()) {
            return Optional.of(
                    "주식은 한 회사에, ETF·펀드는 여러 종목을 묶은 상품 하나에 투자해요. 위험등급은 최근 3년 가격 변동으로 정해요. RIA 계좌에서는 국내주식 비중이 80% 이상이고 설정 1개월이 지난 국내 주식형펀드만 살 수 있어요.");
        }
        if (hit != null) {
            return Optional.of(String.join(" ", hit.reasons()));
        }
        return Optional.empty();
    }

    private static String facts(StockRecommendationVO rec, List<Item> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("고객 투자성향: ")
                .append(rec.getProfileLabel())
                .append(" (허용 위험등급 ")
                .append(allowedText(rec.getAllowedMinGrade()))
                .append(")\n");
        Map<String, String> lines = new LinkedHashMap<>();
        for (Item i : items) {
            lines.put(
                    i.ticker(),
                    "- "
                            + i.name()
                            + "("
                            + i.ticker()
                            + ") "
                            + (i.type() == Type.FUND ? "펀드·ETF" : "주식")
                            + ", 위험 "
                            + i.riskGrade()
                            + "등급, 연 변동성 "
                            + (i.volatilityPercent() == null
                                    ? "계산 전"
                                    : i.volatilityPercent().stripTrailingZeros().toPlainString()
                                            + "%")
                            + (i.sectorName() == null ? "" : ", 업종 " + i.sectorName())
                            + (i.themes().isEmpty() ? "" : ", 테마 " + String.join("/", i.themes())));
        }
        lines.values().forEach(l -> sb.append(l).append('\n'));
        return sb.toString();
    }

    private static Item findMentioned(List<Item> items, String q) {
        return items.stream()
                .filter(i -> q.contains(i.name()) || q.contains(i.ticker()))
                .findFirst()
                .orElse(null);
    }

    private static Item findByTicker(List<Item> items, String ticker) {
        if (ticker == null) {
            return null;
        }
        return items.stream().filter(i -> i.ticker().equals(ticker)).findFirst().orElse(null);
    }

    // ───── 공통 ─────

    private static String normalizeFilter(String filter) {
        if (filter == null) {
            return "ALL";
        }
        String f = filter.trim().toUpperCase();
        return f.equals("STOCK") || f.equals("FUND") ? f : "ALL";
    }

    private static String sectorKey(StockCandidateResponseDTO.ItemResponse i) {
        return i.getType() + ":" + (i.getSectorName() == null ? i.getTicker() : i.getSectorName());
    }

    /** 업무일마다 달라지지만 같은 날에는 같은 순서가 나오는 값이다. */
    private static int shuffle(String ticker, LocalDate day) {
        return Math.floorMod((ticker + day).hashCode(), 10_007);
    }

    private static String labelOf(int minGrade) {
        for (InvestorRiskRange r : InvestorRiskRange.values()) {
            if (r.minGrade() == minGrade) {
                return r.label();
            }
        }
        return "내 투자성향";
    }

    private static String allowedText(int minGrade) {
        return minGrade == 6 ? "6등급" : minGrade + "~6등급";
    }

    private static String gradeName(int grade) {
        return switch (grade) {
            case 1 -> "매우 높은 위험";
            case 2 -> "높은 위험";
            case 3 -> "다소 높은 위험";
            case 4 -> "보통 위험";
            case 5 -> "낮은 위험";
            default -> "매우 낮은 위험";
        };
    }
}
