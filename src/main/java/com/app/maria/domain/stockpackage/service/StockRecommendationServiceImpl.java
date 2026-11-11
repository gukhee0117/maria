package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.stockpackage.ai.AiBusyException;
import com.app.maria.domain.stockpackage.ai.StockRecommendationAnswerer;
import com.app.maria.domain.stockpackage.dto.request.StockCandidateRequestDTO;
import com.app.maria.domain.stockpackage.dto.request.StockRecommendationAskRequestDTO;
import com.app.maria.domain.stockpackage.dto.response.StockCandidateResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockRecommendationAskResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockRecommendationResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockTendencyResponseDTO;
import com.app.maria.domain.stockpackage.mapper.StockOrderMapper;
import com.app.maria.domain.stockpackage.type.InvestorRiskRange;
import com.app.maria.domain.stockpackage.vo.StockBalanceVO;
import com.app.maria.domain.stockpackage.vo.StockRecommendationVO;
import com.app.maria.domain.stockpackage.vo.StockRecommendationVO.Item;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
            "교육용 프로젝트의 자체 분석 결과예요. 수익을 보장하지 않으며, 실제 금융상품 판매·권유 절차를 대체하지 않아요. 투자 판단과 책임은 본인에게 있어요.";
    static final String FALLBACK_ANSWER =
            "지금은 그 질문에 답할 수 있는 정보가 없어요. 추천 종목의 추천 이유, 위험등급, 변동성, 비교에 대해 물어봐 주세요.";
    // AI 호출이 모두 쓰이는 중일 때의 안내. "정보 없음"(FALLBACK_ANSWER)과 달리 잠시 뒤에 다시 물으면 된다는 뜻이다
    static final String BUSY_ANSWER = "지금 질문이 몰려서 답을 만들지 못했어요. 잠시 뒤에 다시 물어봐 주세요.";
    static final String TRADING_NEEDS_PROFILE = "투자성향 진단 후 거래 경향 추천을 볼 수 있어요.";
    static final String TRADING_NEEDS_PROFILE_ANSWER =
            "투자성향 진단이 없거나 만료돼서 거래 경향 추천을 볼 수 없어요. 내 정보에서 투자성향을 진단해 주세요.";
    static final int MAX_LIMIT = 12;
    // 질문에서 limit을 주지 않으면 이 개수로 추천을 다시 계산한다. 화면과 같은 종목으로 답하려면 화면이 보여 주는 개수를 보내야 한다
    static final int DEFAULT_ASK_LIMIT = 6;
    static final String BASIS_SURVEY = "SURVEY";
    static final String BASIS_TRADING = "TRADING";
    // 이 길이 이하의 테마 이름은 공백을 지우지 않고 비교한다(다른 단어에 걸리는 오탐을 줄이려고)
    static final int SHORT_THEME_LENGTH = 2;

    private static final Pattern GRADE_Q = Pattern.compile("등급|위험");
    private static final Pattern VOL_Q = Pattern.compile("변동성");
    private static final Pattern DIFF_Q = Pattern.compile("ETF|etf|펀드|주식\\s*차이|차이");
    private static final Pattern COMPARE_Q = Pattern.compile("비교");
    private static final Pattern WHY_Q = Pattern.compile("왜|이유|고른|골랐");
    // 종목을 가리키지 않고 "왜 추천했어?"처럼 추천 자체의 이유를 묻는 말
    private static final Pattern PICK_Q = Pattern.compile("추천|고른|골랐|뽑");

    /** "이 종목", "이거"처럼 지금 펼쳐 본 종목을 가리키는 말. */
    private static final Pattern THIS_Q = Pattern.compile("이\\s*(종목|상품|주식|ETF|etf|펀드)|이거|이건|이게");

    private final StockCandidateService candidateService;
    private final StockRecommendationAnswerer answerer;
    private final BusinessClockService businessClockService;
    private final StockTendencyService tendencyService;
    private final AccountMapper accountMapper;
    private final StockOrderMapper stockOrderMapper;

    @Override
    public StockRecommendationResponseDTO recommend(
            Long customerId, String filter, int limit, String basis) {
        return new StockRecommendationResponseDTO(
                build(customerId, normalizeFilter(filter), limit, normalizeBasis(basis), null));
    }

    /**
     * 질문에 테마(예: 반도체)가 있으면 그 테마 안으로 좁혀 추천을 다시 계산하고, 그 종목들로 답한다. 추천은 성향 범위 안 상품으로만 하고(적합성 원칙), 그 테마에
     * 범위 밖 상품이 있으면 개수만 알려 후보 화면에서 고객이 직접 확인하게 한다.
     */
    @Override
    public StockRecommendationAskResponseDTO ask(
            Long customerId, StockRecommendationAskRequestDTO request) {
        int limit = request.limit() == null ? DEFAULT_ASK_LIMIT : request.limit();
        String question = request.question().trim();
        String theme = themeIn(question);
        StockRecommendationVO rec =
                build(customerId, "ALL", limit, normalizeBasis(request.basis()), theme);
        List<Item> items = rec.getItems();
        boolean trading = BASIS_TRADING.equals(rec.getBasis());
        if (!rec.isBasisAvailable() || items.isEmpty()) {
            String text;
            if (theme != null && rec.isBasisAvailable()) {
                text =
                        theme
                                + " 테마에는 내 투자성향 범위 안이고 RIA 계좌로 살 수 있으며 위험등급이 계산된, 지금 보유하지 않은 상품이 없어요."
                                + outOfProfileNote(rec);
            } else if (trading && !rec.isProfileAvailable()) {
                text = TRADING_NEEDS_PROFILE_ANSWER;
            } else if (trading) {
                text =
                        !rec.isBasisAvailable()
                                ? "거래 기준 추천은 체결된 매수가 "
                                        + rec.getMinBuyCount()
                                        + "건 이상일 때 볼 수 있어요. 지금은 "
                                        + rec.getTradeBuyCount()
                                        + "건이에요."
                                : "내 거래 경향에 맞는 상품이 지금은 없어요.";
            } else {
                text =
                        !rec.isBasisAvailable()
                                ? "투자성향 진단이 없거나 만료돼서 추천할 수 없어요. 내 정보에서 투자성향을 진단해 주세요."
                                : rec.getProfileLabel()
                                        + "의 허용 범위 안에서 RIA 계좌로 살 수 있고 위험등급이 계산된 상품이 지금은 없어요. 위험등급은 1등급(매우 높은 위험)부터 6등급(매우 낮은 위험)까지이고, 과거 3년 가격으로 계산해요.";
            }
            return new StockRecommendationAskResponseDTO(text, null, false, DISCLAIMER);
        }

        Item hit = findMentioned(items, question);
        Item focus = hit != null ? hit : findByTicker(items, request.focusTicker());

        Optional<String> rule = ruleAnswer(question, rec, items, hit, focus);
        if (rule.isPresent()) {
            // 테마 질문이면 규칙 답에도 추천하지 않은 범위 밖 상품 개수와 후보 화면 안내를 붙인다
            return new StockRecommendationAskResponseDTO(
                    rule.get() + outOfProfileNote(rec),
                    focus == null ? null : focus.ticker(),
                    false,
                    DISCLAIMER);
        }
        // 테마를 말했는데 다른 규칙에 걸리지 않으면 AI에 넘기지 않고 그 테마 안에서 고른 종목을 사실로 보여 준다
        if (theme != null) {
            return new StockRecommendationAskResponseDTO(
                    themeAnswer(rec, items),
                    focus == null ? null : focus.ticker(),
                    false,
                    DISCLAIMER);
        }
        Optional<String> ai;
        try {
            ai = answerer.answer(question, facts(rec, items));
        } catch (AiBusyException e) {
            return new StockRecommendationAskResponseDTO(BUSY_ANSWER, null, false, DISCLAIMER);
        }
        if (ai.isPresent()) {
            return new StockRecommendationAskResponseDTO(
                    ai.get(), focus == null ? null : focus.ticker(), true, DISCLAIMER);
        }
        return new StockRecommendationAskResponseDTO(FALLBACK_ANSWER, null, false, DISCLAIMER);
    }

    // ───── 추천 목록 ─────

    /** theme이 있으면 그 테마 안에서 고른다. 어느 쪽이든 추천은 설문 성향 범위 안 상품으로만 한다. */
    private StockRecommendationVO build(
            Long customerId, String filter, int limit, String basis, String theme) {
        return BASIS_TRADING.equals(basis)
                ? buildTrading(customerId, filter, limit, theme)
                : buildSurvey(customerId, filter, limit, theme);
    }

    private StockRecommendationVO buildSurvey(
            Long customerId, String filter, int limit, String theme) {
        int size = Math.max(1, Math.min(MAX_LIMIT, limit));
        Set<Type> types = typesOf(filter);
        StockCandidateResponseDTO candidates = searchCandidates(customerId, types, theme);
        LocalDateTime now = businessClockService.now();

        if (!candidates.isProfileAvailable()) {
            return empty(filter, BASIS_SURVEY, now)
                    .profileAvailable(false)
                    .subtitle("투자성향 진단이 필요해요")
                    .build();
        }
        int minGrade = candidates.getAllowedMinGrade();
        String label = labelOf(minGrade);

        // 설문 기준: 목표는 허용 범위에서 가장 위험한 경계, 범위는 허용 범위 전체
        Target target = new Target(BigDecimal.valueOf(minGrade), minGrade, 6, false);
        Taste taste = tasteOf(customerId);
        Set<Long> held = heldProductIds(customerId);
        List<Item> items =
                choose(
                        candidates,
                        target,
                        taste,
                        held,
                        now.toLocalDate(),
                        size,
                        i -> surveyLead(i, label, minGrade),
                        true,
                        theme != null);
        return StockRecommendationVO.builder()
                .items(items)
                .profileAvailable(true)
                .profileLabel(label)
                .allowedMinGrade(minGrade)
                .theme(theme)
                .outOfProfileCount(theme == null ? 0 : countOutOfProfile(candidates, held))
                .basis(BASIS_SURVEY)
                .basisAvailable(true)
                .basisFacts(List.of())
                .filter(filter)
                .subtitle(label + " · " + allowedText(minGrade) + " 안에서")
                .disclaimer(DISCLAIMER)
                .checkedAt(now)
                .targetAdjusted(false)
                .targetGrade(target.grade())
                .gradeFrom(target.low())
                .gradeTo(target.high())
                .build();
    }

    /**
     * 추천을 줄 수 없을 때 공통으로 채우는 빈 결과(items·basisFacts는 빈 목록, basisAvailable=false). 호출하는 쪽이
     * profileAvailable과 subtitle을 더하고, 필요하면 tradeBuyCount·minBuyCount를 더하거나 basisFacts를 다시 지정해(나중
     * 호출이 덮어쓴다) build한다.
     */
    private static StockRecommendationVO.StockRecommendationVOBuilder empty(
            String filter, String basis, LocalDateTime now) {
        return StockRecommendationVO.builder()
                .items(List.of())
                .basis(basis)
                .basisAvailable(false)
                .basisFacts(List.of())
                .filter(filter)
                .disclaimer(DISCLAIMER)
                .checkedAt(now);
    }

    // ───── 거래 기준 추천 ─────

    /**
     * 거래에서 본 경향으로 추천한다. 목표 등급은 평균 매수 위험등급이다. 평균이 설문 허용 범위보다 위험하면 목표를 허용 최소 등급으로 보정하고
     * targetAdjusted를 true로 준다. 후보 범위는 (보정한 목표를 소수 첫째 자리에서 반올림한 정수)의 앞뒤 1등급을 설문 허용 범위(허용 최소
     * 등급~6등급)와 겹친 구간이다(자세한 계산은 {@link #tradingTarget}). 설문 진단이 없거나 만료돼서 허용 범위를 모르면 추천하지 않고
     * basisAvailable=false로 안내만 준다. 고르는 규칙은 설문 기준과 같은 함수를 쓴다.
     */
    private StockRecommendationVO buildTrading(
            Long customerId, String filter, int limit, String theme) {
        int size = Math.max(1, Math.min(MAX_LIMIT, limit));
        Set<Type> types = typesOf(filter);
        LocalDateTime now = businessClockService.now();
        StockTendencyResponseDTO.TradingResponse trading =
                tendencyService.getTendency(customerId).getTrading();
        StockCandidateResponseDTO candidates = searchCandidates(customerId, types, theme);

        // 허용 범위(설문 진단)를 모르면 거래 기준도 범위를 정할 수 없어 추천하지 않는다
        if (!candidates.isProfileAvailable()) {
            return empty(filter, BASIS_TRADING, now)
                    .profileAvailable(false)
                    .basisFacts(List.of(TRADING_NEEDS_PROFILE))
                    .tradeBuyCount(trading.getBuyCount())
                    .minBuyCount(trading.getMinBuyCount())
                    .subtitle("투자성향 진단이 필요해요")
                    .build();
        }
        if (!trading.isAvailable() || trading.getAvgBuyGrade() == null) {
            return empty(filter, BASIS_TRADING, now)
                    .profileAvailable(true)
                    .tradeBuyCount(trading.getBuyCount())
                    .minBuyCount(trading.getMinBuyCount())
                    .subtitle("거래 " + trading.getMinBuyCount() + "건 이상 매수하면 볼 수 있어요")
                    .build();
        }

        BigDecimal avg = trading.getAvgBuyGrade();
        int allowedMin = candidates.getAllowedMinGrade();
        Target target = tradingTarget(avg, allowedMin);
        Taste taste = tasteOf(trading);
        Set<Long> held = heldProductIds(customerId);

        List<Item> items =
                choose(
                        candidates,
                        target,
                        taste,
                        held,
                        now.toLocalDate(),
                        size,
                        i -> tradingLead(i, avg),
                        false,
                        theme != null);
        List<String> facts = new ArrayList<>();
        facts.add("평균 매수 위험등급은 " + plain(avg) + "등급이에요.");
        if (target.adjusted()) {
            facts.add(
                    "평균 매수 등급이 설문 허용 범위("
                            + allowedText(allowedMin)
                            + ")보다 위험해서, 허용 범위 경계인 "
                            + allowedMin
                            + "등급을 기준으로 골랐어요.");
        }
        if (taste.sector() != null) {
            facts.add("가장 많이 산 업종은 " + taste.sector() + "이에요.");
        }
        if (taste.theme() != null) {
            facts.add("가장 많이 산 테마는 " + taste.theme() + "이에요.");
        }
        return StockRecommendationVO.builder()
                .items(items)
                .profileAvailable(true)
                .theme(theme)
                .outOfProfileCount(theme == null ? 0 : countOutOfProfile(candidates, held))
                .basis(BASIS_TRADING)
                .basisAvailable(true)
                .basisFacts(List.copyOf(facts))
                .tradeBuyCount(trading.getBuyCount())
                .minBuyCount(trading.getMinBuyCount())
                .filter(filter)
                .subtitle("내 거래 경향 · 평균 매수 " + plain(avg) + "등급 근처")
                .disclaimer(DISCLAIMER)
                .checkedAt(now)
                .targetAdjusted(target.adjusted())
                .targetGrade(target.grade())
                .gradeFrom(target.low())
                .gradeTo(target.high())
                .build();
    }

    // ───── 공통 선택 ─────

    /** 추천을 고르는 목표 등급과 후보 등급 범위(양 끝 포함). 기준마다 이것만 다르게 넘긴다. */
    record Target(BigDecimal grade, int low, int high, boolean adjusted) {}

    /** 많이 산 업종·테마. 거래 데이터가 없으면 둘 다 null이고, 그러면 업종·테마 우선 규칙은 적용되지 않는다. */
    record Taste(String sector, String theme) {}

    /**
     * 거래 기준의 목표와 범위. ① 평균 매수 등급이 허용 최소 등급보다 위험(숫자가 작음)하면 목표를 허용 최소 등급으로 보정한다(adjusted=true). ② 범위의
     * 중심은 목표를 소수 첫째 자리에서 반올림한 정수(3.2→3, 3.5→4)이고, 그 앞뒤 1등급을 설문 허용 범위(허용 최소 등급~6등급)와 겹친 구간이 범위다. 소수
     * 그대로의 목표 등급은 "목표에 가까운 순서" 정렬에만 쓴다. 예) 허용 2~6: 평균 3.2 → 2~4, 평균 1.21 → 목표 2로 보정, 2~3.
     */
    static Target tradingTarget(BigDecimal avg, int allowedMin) {
        boolean adjusted = avg.compareTo(BigDecimal.valueOf(allowedMin)) < 0;
        BigDecimal grade = adjusted ? BigDecimal.valueOf(allowedMin) : avg;
        int center = grade.setScale(0, RoundingMode.HALF_UP).intValue();
        return new Target(
                grade, Math.max(allowedMin, center - 1), Math.min(6, center + 1), adjusted);
    }

    /**
     * 테마가 없으면 includeOutOfProfile=false로 설문 허용 범위 안 상품만 받는다. 테마가 있으면 범위 밖 개수를 알리려고 그 테마 상품 전체를 받는다.
     * 추천({@link #choose})은 어느 쪽이든 범위 안 상품만 고른다.
     */
    private StockCandidateResponseDTO searchCandidates(
            Long customerId, Set<Type> types, String theme) {
        return candidateService.search(
                customerId,
                theme == null
                        ? new StockCandidateRequestDTO(
                                types, null, null, null, null, false, null, null)
                        : new StockCandidateRequestDTO(
                                types, List.of(theme), null, null, null, true, null, null));
    }

    /** 질문에 들어 있는 테마 이름. 여러 개면 가장 긴 이름(더 구체적인 테마)을 고르고, 없으면 null이다. */
    private String themeIn(String question) {
        List<String> names = candidateService.themeNames();
        if (names == null) {
            return null;
        }
        // "2차 전지"·"ai반도체"처럼 띄어쓰기나 대소문자가 달라도 같은 테마로 본다. 다만 "건설"처럼 두 글자 이하 이름은 공백을 지우면
        // "이 건 설명"이 "건설명"이 되듯 다른 단어에 걸리기 쉬워, 공백을 그대로 둔 채 대소문자만 무시하고 비교한다
        String squashed = squash(question);
        String lower = question.toLowerCase(Locale.ROOT);
        return names.stream()
                .filter(n -> n != null && !squash(n).isEmpty())
                .filter(
                        n ->
                                squash(n).length() <= SHORT_THEME_LENGTH
                                        ? lower.contains(n.trim().toLowerCase(Locale.ROOT))
                                        : squashed.contains(squash(n)))
                .max(Comparator.comparingInt((String n) -> squash(n).length()))
                .orElse(null);
    }

    /** 공백을 모두 빼고 소문자로 바꾼다. 테마 이름 비교에만 쓴다. */
    private static String squash(String s) {
        return s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static Set<Type> typesOf(String filter) {
        return switch (filter) {
            case "STOCK" -> Set.of(Type.STOCK);
            case "FUND" -> Set.of(Type.FUND);
            default -> null;
        };
    }

    /** 고객 계좌 전체에서 지금 잔량이 있는 상품. 계좌가 없으면 보유 없음이다. */
    private Set<Long> heldProductIds(Long customerId) {
        return accountMapper
                .selectByCustomerId(customerId)
                .map(
                        a ->
                                stockOrderMapper
                                        .selectHeldBalancesByAccount(a.getAccountId())
                                        .stream()
                                        .map(StockBalanceVO::getDomesticProductId)
                                        .collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    /** 설문 기준에서 쓰는 업종·테마 취향. 체결 매수가 최소 건수에 못 미치면 비어 있다. */
    private Taste tasteOf(Long customerId) {
        return tasteOf(tendencyService.getTendency(customerId).getTrading());
    }

    private static Taste tasteOf(StockTendencyResponseDTO.TradingResponse trading) {
        if (trading == null || !trading.isAvailable()) {
            return new Taste(null, null);
        }
        return new Taste(
                trading.getTopSector() == null ? null : trading.getTopSector().getName(),
                trading.getTopTheme() == null ? null : trading.getTopTheme().getName());
    }

    /**
     * 두 기준이 같이 쓰는 선택 함수. 후보에서 고를 수 없거나 등급이 없는 상품, 목표 범위 밖 등급, 설문 허용 범위 밖 상품, 이미 보유한 상품을 뺀다. 남은 상품은
     * 많이 산 업종·테마와 같은 것 먼저, 목표 등급에 가까운 순서, 업무일마다 달라지는 고정 순서로 줄을 세우고, 업종이 겹치지 않게 먼저 채운 뒤 모자라면 나머지로
     * 채운다.
     *
     * <p>widen이 true(고객이 테마를 골랐을 때)면 목표 구간 안 상품으로 모자랄 때 목표 구간(거래 기준의 평균 매수 등급 앞뒤 1등급) 밖이지만 설문 허용 범위
     * 안인 상품을 목표 등급에 가까운 순서로 더 채운다. 설문 허용 범위 밖 상품은 어느 경우에도 추천하지 않는다.
     */
    private List<Item> choose(
            StockCandidateResponseDTO candidates,
            Target target,
            Taste taste,
            Set<Long> held,
            LocalDate day,
            int size,
            Function<StockCandidateResponseDTO.ItemResponse, String> lead,
            boolean withThemeLine,
            boolean widen) {
        int allowedMin = candidates.getAllowedMinGrade();
        String label = labelOf(allowedMin);
        List<StockCandidateResponseDTO.ItemResponse> inProfilePool =
                pickable(candidates, held)
                        .filter(i -> i.getRiskGrade() >= allowedMin)
                        .filter(i -> !Boolean.TRUE.equals(i.getOutOfProfile()))
                        .toList();

        // ① 목표 구간 안의 범위 안 상품
        Comparator<StockCandidateResponseDTO.ItemResponse> order =
                Comparator.comparingInt(
                                (StockCandidateResponseDTO.ItemResponse i) ->
                                        matchRank(i, taste.sector(), taste.theme()))
                        .thenComparing(i -> distance(target, i))
                        .thenComparingInt(i -> shuffle(i.getTicker(), day))
                        .thenComparing(StockCandidateResponseDTO.ItemResponse::getTicker);
        List<StockCandidateResponseDTO.ItemResponse> first =
                pickSpread(
                        inProfilePool.stream()
                                .filter(i -> i.getRiskGrade() >= target.low())
                                .filter(i -> i.getRiskGrade() <= target.high())
                                .sorted(order)
                                .toList(),
                        size);
        List<Item> result = new ArrayList<>();
        first.forEach(i -> result.add(toItem(i, lead.apply(i), taste, withThemeLine)));
        if (!widen || result.size() >= size) {
            return List.copyOf(result);
        }

        // ② 목표 구간 밖이지만 설문 허용 범위 안인 상품
        Comparator<StockCandidateResponseDTO.ItemResponse> widenOrder =
                Comparator.comparing(
                                (StockCandidateResponseDTO.ItemResponse i) -> distance(target, i))
                        .thenComparingInt(i -> matchRank(i, taste.sector(), taste.theme()))
                        .thenComparingInt(i -> shuffle(i.getTicker(), day))
                        .thenComparing(StockCandidateResponseDTO.ItemResponse::getTicker);
        pickSpread(
                        inProfilePool.stream()
                                .filter(i -> !first.contains(i))
                                .sorted(widenOrder)
                                .toList(),
                        size - result.size())
                .forEach(
                        i ->
                                result.add(
                                        toItem(
                                                i,
                                                widenedLead(i, target, label, allowedMin),
                                                taste,
                                                withThemeLine)));
        return List.copyOf(result);
    }

    /** 테마 안에서 담을 수 있고 위험등급이 있으며 지금 보유하지 않은, 설문 허용 범위 밖 상품 수. 추천에는 넣지 않고 개수만 알린다. */
    private static int countOutOfProfile(StockCandidateResponseDTO candidates, Set<Long> held) {
        int allowedMin = candidates.getAllowedMinGrade();
        return (int) pickable(candidates, held).filter(i -> i.getRiskGrade() < allowedMin).count();
    }

    /** 담을 수 있고 위험등급이 있으며 지금 보유하지 않은 후보. */
    private static Stream<StockCandidateResponseDTO.ItemResponse> pickable(
            StockCandidateResponseDTO candidates, Set<Long> held) {
        return candidates.getItems().stream()
                .filter(StockCandidateResponseDTO.ItemResponse::isSelectable)
                .filter(i -> i.getRiskGrade() != null)
                .filter(i -> !held.contains(i.getDomesticProductId()));
    }

    private static BigDecimal distance(Target target, StockCandidateResponseDTO.ItemResponse i) {
        return target.grade().subtract(BigDecimal.valueOf(i.getRiskGrade())).abs();
    }

    /** 업종이 겹치지 않게 먼저 채운 뒤 모자라면 나머지로 채운다. pool의 순서를 그대로 유지한다. */
    private static List<StockCandidateResponseDTO.ItemResponse> pickSpread(
            List<StockCandidateResponseDTO.ItemResponse> pool, int size) {
        Set<StockCandidateResponseDTO.ItemResponse> picked = new LinkedHashSet<>();
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
            // 첫 번째 루프에서 이미 담긴 항목은 Set이 무시한다
            picked.add(i);
        }
        return pool.stream().filter(picked::contains).toList();
    }

    /** 0이 가장 앞이다: 업종·테마 모두 같으면 0, 업종만 1, 테마만 2, 둘 다 아니면 3. */
    private static int matchRank(
            StockCandidateResponseDTO.ItemResponse i, String topSector, String topTheme) {
        boolean sector = sectorMatched(i, topSector);
        boolean theme = themeMatched(i, topTheme);
        return sector && theme ? 0 : sector ? 1 : theme ? 2 : 3;
    }

    private static boolean sectorMatched(
            StockCandidateResponseDTO.ItemResponse i, String topSector) {
        return topSector != null && topSector.equals(i.getSectorName());
    }

    private static boolean themeMatched(StockCandidateResponseDTO.ItemResponse i, String topTheme) {
        return topTheme != null && i.getThemes().contains(topTheme);
    }

    private static String surveyLead(
            StockCandidateResponseDTO.ItemResponse i, String label, int minGrade) {
        return label
                + " 허용 범위("
                + allowedText(minGrade)
                + ") 안의 "
                + i.getRiskGrade()
                + "등급("
                + gradeName(i.getRiskGrade())
                + ") 상품이에요.";
    }

    /** 목표 구간 밖이지만 설문 허용 범위 안이라 테마 추천에서 더 채운 상품의 첫 이유. 왜 들어왔는지 함께 알린다. */
    private static String widenedLead(
            StockCandidateResponseDTO.ItemResponse i, Target target, String label, int allowedMin) {
        return "기준 등급 근처("
                + target.low()
                + "~"
                + target.high()
                + "등급) 상품이 모자라, 같은 테마에서 "
                + label
                + " 허용 범위("
                + allowedText(allowedMin)
                + ") 안의 "
                + i.getRiskGrade()
                + "등급("
                + gradeName(i.getRiskGrade())
                + ") 상품을 더 골랐어요.";
    }

    private static String tradingLead(StockCandidateResponseDTO.ItemResponse i, BigDecimal avg) {
        return "내 거래의 평균 매수 위험등급은 "
                + plain(avg)
                + "등급이고, 이 상품은 가까운 "
                + i.getRiskGrade()
                + "등급("
                + gradeName(i.getRiskGrade())
                + ")이에요.";
    }

    private static Item toItem(
            StockCandidateResponseDTO.ItemResponse i,
            String lead,
            Taste taste,
            boolean withThemeLine) {
        boolean sector = sectorMatched(i, taste.sector());
        boolean theme = themeMatched(i, taste.theme());
        List<String> reasons = new ArrayList<>();
        reasons.add(lead);
        if (sector) {
            reasons.add("가장 많이 산 업종(" + taste.sector() + ")과 같은 업종이에요.");
        }
        if (theme) {
            reasons.add("가장 많이 산 테마(" + taste.theme() + ")에 속해요.");
        }
        reasons.add(
                "RIA 계좌에서 살 수 있는 국내 상장 " + (i.getType() == Type.FUND ? "펀드·ETF" : "주식") + "이에요.");
        if (i.getVolatilityPercent() != null) {
            reasons.add("과거 3년 가격으로 계산한 연 변동성은 " + plain(i.getVolatilityPercent()) + "%예요.");
        }
        if (withThemeLine) {
            if (!i.getThemes().isEmpty()) {
                reasons.add(String.join(" · ", i.getThemes()) + " 테마에 속해요.");
            } else if (i.getSectorName() != null) {
                reasons.add(i.getSectorName() + " 업종이에요.");
            }
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
                false,
                List.copyOf(reasons),
                sector,
                theme,
                theme ? taste.theme() : null);
    }

    // ───── 질문 답변 ─────

    private Optional<String> ruleAnswer(
            String q, StockRecommendationVO rec, List<Item> items, Item hit, Item focus) {
        if (WHY_Q.matcher(q).find() && focus != null) {
            return Optional.of(String.join(" ", focus.reasons()));
        }
        // 펼쳐 본 종목을 가리키는 질문은 그 종목 하나의 사실로만 답한다
        if (focus != null
                && THIS_Q.matcher(q).find()
                && !COMPARE_Q.matcher(q).find()
                && (VOL_Q.matcher(q).find() || GRADE_Q.matcher(q).find())) {
            boolean vol = VOL_Q.matcher(q).find();
            if (vol && focus.volatilityPercent() == null) {
                return Optional.of(focus.name() + "의 연 변동성은 아직 계산되지 않았어요.");
            }
            return Optional.of(
                    vol
                            ? focus.name()
                                    + "의 연 변동성은 "
                                    + plain(focus.volatilityPercent())
                                    + "%예요. 가격이 1년 동안 오르내린 폭을 과거 3년 가격으로 계산한 값이고, 앞으로의 수익을 예측하는 값은 아니에요."
                            : focus.name()
                                    + "은(는) "
                                    + focus.riskGrade()
                                    + "등급("
                                    + gradeName(focus.riskGrade())
                                    + ")이에요. 과거 3년 가격 변동으로 리턴증권이 자체 산정한 값이에요.");
        }
        // "변동성 위험"처럼 둘이 같이 나오면 더 구체적인 변동성 답을 먼저 한다
        if (VOL_Q.matcher(q).find()) {
            return Optional.of(
                    items.stream()
                                    .filter(i -> i.volatilityPercent() != null)
                                    .map(i -> i.name() + " " + plain(i.volatilityPercent()) + "%")
                                    .collect(Collectors.joining(" · "))
                            + "\n연 변동성은 가격이 1년 동안 오르내린 폭을 과거 3년 가격으로 계산한 값이에요. 앞으로의 수익을 예측하는 값은 아니에요.");
        }
        if (GRADE_Q.matcher(q).find() && !COMPARE_Q.matcher(q).find()) {
            return Optional.of(
                    "위험등급은 1등급(매우 높은 위험)부터 6등급(매우 낮은 위험)까지예요. 과거 3년 가격으로 리턴증권이 자체 산정해요.\n"
                            + items.stream()
                                    .map(i -> i.name() + " " + i.riskGrade() + "등급")
                                    .collect(Collectors.joining(" · "))
                            + "\n"
                            + rangeSentence(rec));
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
                                                                    + plain(i.volatilityPercent())
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
        // 종목을 펼치거나 말하지 않고 "왜 추천했어?"처럼 물으면 AI에 넘기지 않고 추천 기준과 종목별 근거로 답한다
        if (WHY_Q.matcher(q).find() && PICK_Q.matcher(q).find()) {
            return Optional.of(whyAnswer(rec, items));
        }
        return Optional.empty();
    }

    /**
     * 추천 기준 한 문장과 종목별 위험등급·거래 경향 일치 여부로 "왜 이 종목들을 추천했는지"를 알려 준다. 이유는 모두 서버가 가진 사실이다.
     *
     * @param items 비어 있지 않아야 한다. 비면 종목 줄 없이 "종목을 눌러 펼치면..." 안내만 붙는다.
     */
    private static String whyAnswer(StockRecommendationVO rec, List<Item> items) {
        String lines =
                items.stream()
                        .map(i -> itemLine(i, matchPhrase(i)))
                        .collect(Collectors.joining("\n"));
        return rangeSentence(rec)
                + " 그 안에서 RIA 계좌로 살 수 있고 위험등급이 계산됐으며 지금 보유하지 않은 상품을 골랐어요.\n"
                + lines
                + "\n종목을 눌러 펼치면 종목별 이유를 볼 수 있어요.";
    }

    /**
     * 테마를 말한 질문에 대한 답. 그 테마 안에서 고른 범위 안 종목을 위험등급과 함께 나열하고, 추천하지 않은 범위 밖 종목은 개수만 알린다.
     *
     * @param items 비어 있지 않아야 한다.
     */
    private static String themeAnswer(StockRecommendationVO rec, List<Item> items) {
        String lines =
                items.stream()
                        .map(
                                i ->
                                        itemLine(
                                                i,
                                                i.volatilityPercent() == null
                                                        ? ""
                                                        : ", 연 변동성 "
                                                                + plain(i.volatilityPercent())
                                                                + "%"))
                        .collect(Collectors.joining("\n"));
        StringBuilder sb =
                new StringBuilder(rec.getTheme())
                        .append(" 테마 안에서 내 투자성향 범위 안이고 RIA 계좌로 살 수 있으며 지금 보유하지 않은 상품을 골랐어요.\n")
                        .append(lines)
                        .append(outOfProfileNote(rec));
        sb.append("\n종목을 눌러 펼치면 종목별 이유를 볼 수 있어요.");
        return sb.toString();
    }

    /** "- 종목명 N등급(위험 이름)" 뒤에 extra를 붙인 한 줄. */
    private static String itemLine(Item i, String extra) {
        return "- "
                + i.name()
                + " "
                + i.riskGrade()
                + "등급("
                + gradeName(i.riskGrade())
                + ")"
                + extra;
    }

    /** 테마 안에 추천하지 않은 범위 밖 상품이 있으면 개수와 후보 화면 안내를 준다(앞에 줄바꿈 포함). 없으면 빈 문자열이다. */
    private static String outOfProfileNote(StockRecommendationVO rec) {
        if (rec.getTheme() == null || rec.getOutOfProfileCount() == 0) {
            return "";
        }
        return "\n이 테마에는 내 투자성향 범위 밖 상품도 "
                + rec.getOutOfProfileCount()
                + "개 있어요. 추천에는 넣지 않았고, 새 패키지의 후보 찾기에서 직접 확인할 수 있어요.";
    }

    /** 거래에서 많이 산 업종·테마와 일치하면 그 사실을 덧붙인다. 일치하지 않으면 빈 문자열이다. */
    private static String matchPhrase(Item i) {
        List<String> parts = new ArrayList<>();
        if (i.sectorMatched()) {
            parts.add("많이 산 업종(" + i.sectorName() + ")과 같아요");
        }
        if (i.themeMatched()) {
            parts.add("많이 산 테마(" + i.matchedTheme() + ")에 속해요");
        }
        return parts.isEmpty() ? "" : ", " + String.join(", ", parts);
    }

    private static String facts(StockRecommendationVO rec, List<Item> items) {
        StringBuilder sb = new StringBuilder();
        if (BASIS_TRADING.equals(rec.getBasis())) {
            sb.append("추천 기준: 고객의 거래에서 본 경향\n");
            rec.getBasisFacts().forEach(f -> sb.append(f).append('\n'));
        } else {
            sb.append("고객 투자성향: ")
                    .append(rec.getProfileLabel())
                    .append(" (허용 위험등급 ")
                    .append(allowedText(rec.getAllowedMinGrade()))
                    .append(")\n");
        }
        if (rec.getTheme() != null) {
            sb.append("고객이 고른 테마: ").append(rec.getTheme()).append(" (설문 허용 범위 안 상품만 추천함)\n");
        }
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
                                    : plain(i.volatilityPercent()) + "%")
                            + (i.sectorName() == null ? "" : ", 업종 " + i.sectorName())
                            + (i.themes().isEmpty() ? "" : ", 테마 " + String.join("/", i.themes())));
        }
        lines.values().forEach(l -> sb.append(l).append('\n'));
        return sb.toString();
    }

    private static String rangeSentence(StockRecommendationVO rec) {
        if (rec.getTheme() != null) {
            return rec.getTheme() + " 테마 안에서도 내 투자성향 범위 안 상품만 추천해요.";
        }
        if (BASIS_TRADING.equals(rec.getBasis())) {
            return rec.getGradeFrom() == null
                    ? "거래 기준 추천은 내 평균 매수 위험등급 앞뒤 1등급과 설문 허용 범위가 겹치는 상품을 보여줘요."
                    : "거래 기준 추천은 내 평균 매수 위험등급 앞뒤 1등급과 설문 허용 범위가 겹치는 "
                            + rec.getGradeFrom()
                            + "~"
                            + rec.getGradeTo()
                            + "등급 상품을 보여줘요.";
        }
        return rec.getProfileLabel()
                + "은 "
                + allowedText(rec.getAllowedMinGrade())
                + " 상품만 추천받을 수 있어요.";
    }

    private static String normalizeBasis(String basis) {
        return basis != null && BASIS_TRADING.equalsIgnoreCase(basis.trim())
                ? BASIS_TRADING
                : BASIS_SURVEY;
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

    /** 15.50 → 15.5. 끝의 0을 떼고 지수 표기 없이 문자열로 만든다. */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
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
