package com.app.maria.domain.community.kkiru;

import com.app.maria.global.client.opendart.OpenDartClient;
import com.app.maria.global.clock.service.BusinessClockService;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 재무 도구(설계 4.4 ⑤, OpenDART {@code fnlttSinglAcnt}, 질문에 따라 {@code fnlttSinglIndx}). 가장 최근 보고서를 찾아(올해
 * 3분기 → 반기 → 1분기 → 지난해 사업보고서) 매출액·영업이익·당기순이익을 읽는다. 연결이 있으면 연결, 없으면 별도이고 어느 쪽인지 문장에 쓴다. 금융업은 매출액 계정이
 * 없어 영업이익·당기순이익·순이자손익을 읽는다.
 *
 * <p>분기·반기 보고서에서 {@code thstrm_amount}는 그 분기만이고 {@code thstrm_add_amount}가 누적이다. 문장에 "분기"인지 "누적"인지
 * 반드시 쓴다.
 */
@Component
@Order(40)
public class FinancialTool extends OpenDartTool {
    private static final List<String> WORDS =
            List.of("매출", "영업이익", "순이익", "이익", "적자", "흑자", "실적", "재무", "부채", "자본", "roe", "어닝");
    private static final List<String> STABILITY_WORDS = List.of("부채", "자본", "유동", "안정");
    private static final List<String> PROFITABILITY_WORDS = List.of("roe", "이익률", "마진", "수익성");

    private record Period(int year, String code) {}

    /** 보고서 종류별 문장 조각. */
    private record Label(String period, String base, String cumulative) {}

    public FinancialTool(OpenDartClient client, BusinessClockService clock) {
        super(client, clock);
    }

    @Override
    public String name() {
        return "financial";
    }

    @Override
    protected List<String> words() {
        return WORDS;
    }

    @Override
    protected List<Evidence> collectFor(KkiruQuestion question, OpenDartClient.Corp corp) {
        int year = clock.now().getYear();
        // 3분기 → 반기 → 1분기 → 지난해 사업보고서 순으로 처음 자료가 있는 것을 쓴다(호출은 최대 4번).
        List<Period> candidates =
                List.of(
                        new Period(year, "11014"),
                        new Period(year, "11012"),
                        new Period(year, "11013"),
                        new Period(year - 1, "11011"));
        for (Period period : candidates) {
            List<JsonNode> rows =
                    client.list(
                            "fnlttSinglAcnt",
                            Map.of(
                                    "corp_code", corp.code(),
                                    "bsns_year", String.valueOf(period.year()),
                                    "reprt_code", period.code()));
            if (!rows.isEmpty()) {
                List<Evidence> evidence = accounts(rows, corp, period, question);
                evidence.addAll(indicators(corp, period, QuestionWords.normalize(question.text())));
                return evidence;
            }
        }
        return List.of();
    }

    // ---------- 계정 ----------

    private List<Evidence> accounts(
            List<JsonNode> rows, OpenDartClient.Corp corp, Period period, KkiruQuestion question) {
        boolean consolidated = rows.stream().anyMatch(r -> r.path("fs_div").asText().equals("CFS"));
        String fsDiv = consolidated ? "CFS" : "OFS";
        String scope = consolidated ? "연결" : "별도";
        List<JsonNode> income =
                rows.stream()
                        .filter(r -> r.path("fs_div").asText().equals(fsDiv))
                        .filter(r -> r.path("sj_div").asText().equals("IS"))
                        .toList();
        Label label = labelOf(period);
        List<Evidence> evidence = new ArrayList<>();

        boolean hasRevenue = find(income, n -> n.equals("매출액")).isPresent();
        if (hasRevenue) {
            find(income, n -> n.equals("매출액"))
                    .flatMap(r -> sentence(r, corp, label, scope, "매출액"))
                    .ifPresent(evidence::add);
            find(income, n -> n.startsWith("영업이익"))
                    .flatMap(r -> sentence(r, corp, label, scope, "영업이익"))
                    .ifPresent(evidence::add);
            find(income, n -> n.startsWith("당기순이익"))
                    .flatMap(r -> sentence(r, corp, label, scope, "당기순이익"))
                    .ifPresent(evidence::add);
            return evidence;
        }
        // 금융업: 매출액 계정이 없다. 매출을 물었으면 먼저 그 사실을 알리고, 이어서 영업이익 등을 읽는다.
        Optional<JsonNode> operating = find(income, n -> n.startsWith("영업이익"));
        if (operating.isPresent() && QuestionWords.normalize(question.text()).contains("매출")) {
            String receiptNo = operating.get().path("rcept_no").asText();
            evidence.add(
                    new Evidence(
                            "financial:" + receiptNo + ":no-revenue",
                            "이 회사는 재무제표에 매출액 계정이 없습니다.",
                            KkiruFormat.dartLink(receiptNo)));
        }
        operating.flatMap(r -> sentence(r, corp, label, scope, "영업이익")).ifPresent(evidence::add);
        find(income, n -> n.startsWith("당기순이익"))
                .flatMap(r -> sentence(r, corp, label, scope, "당기순이익"))
                .ifPresent(evidence::add);
        find(income, n -> n.equals("순이자손익"))
                .flatMap(r -> sentence(r, corp, label, scope, "순이자손익"))
                .ifPresent(evidence::add);
        return evidence;
    }

    private static Optional<JsonNode> find(List<JsonNode> rows, Predicate<String> name) {
        return rows.stream().filter(r -> name.test(r.path("account_nm").asText())).findFirst();
    }

    private static Label labelOf(Period period) {
        String year = period.year() + "년";
        return switch (period.code()) {
            case "11013" -> new Label(year + " 1분기", "전년 같은 기간", null);
            case "11012" -> new Label(year + " 반기(2분기)", "전년 같은 기간", "상반기 누적");
            case "11014" -> new Label(year + " 3분기", "전년 같은 기간", "3분기 누적");
            default -> new Label(year, "전기", null);
        };
    }

    /**
     * 계정 한 줄: "{회사}의 {기간} {연결/별도} {계정}은 {이번}으로 {비교} {값} 대비 {증감}입니다." 비교값이 없거나, 증감률을 계산할 수 없거나, 적자에서
     * 흑자로 바뀐 경우는 문장이 달라진다. 누적 값이 있으면 뒤에 덧붙인다. 이번 값이 없으면 empty.
     */
    private static Optional<Evidence> sentence(
            JsonNode row, OpenDartClient.Corp corp, Label label, String scope, String account) {
        Optional<BigDecimal> current = KkiruFormat.number(row.path("thstrm_amount").asText());
        if (current.isEmpty()) {
            return Optional.empty();
        }
        String accountName = row.path("account_nm").asText();
        String head = corp.name() + "의 " + label.period() + " " + scope + " " + accountName + "은 ";
        String now = KkiruFormat.won(current.get());
        Optional<BigDecimal> base = KkiruFormat.number(row.path("frmtrm_amount").asText());
        String body;
        if (base.isEmpty()) {
            body = head + now + "입니다.";
        } else {
            String change = KkiruFormat.change(current.get(), base.get());
            String baseText = label.base() + " " + KkiruFormat.won(base.get());
            if (change == null) {
                body = head + now + "입니다(" + baseText + ", 증감률은 비교 불가).";
            } else if (change.equals(KkiruFormat.TURNED_PROFIT)) {
                body = head + now + "입니다(" + baseText + ", " + change + ").";
            } else {
                body = head + now + "으로 " + baseText + " 대비 " + change + "입니다.";
            }
        }
        if (label.cumulative() != null) {
            Optional<BigDecimal> total = KkiruFormat.number(row.path("thstrm_add_amount").asText());
            if (total.isPresent()) {
                body += " " + label.cumulative() + "은 " + KkiruFormat.won(total.get()) + "입니다.";
            }
        }
        String receiptNo = row.path("rcept_no").asText();
        return Optional.of(
                new Evidence(
                        "financial:" + receiptNo + ":" + account,
                        body,
                        KkiruFormat.dartLink(receiptNo)));
    }

    // ---------- 재무지표 ----------

    /**
     * 질문에 안정성 단어가 있으면 부채비율·자기자본비율, 수익성 단어가 있으면 ROE·순이익률을 같은 보고서에서 가져온다(추가 호출 1~2번). {@code
     * idx_val}은 null이 아니라 키가 아예 없는 행이 있어 값이 없으면 건너뛴다.
     */
    private List<Evidence> indicators(OpenDartClient.Corp corp, Period period, String question) {
        List<Evidence> evidence = new ArrayList<>();
        if (STABILITY_WORDS.stream().anyMatch(question::contains)) {
            indicatorRows(corp, period, "M220000", List.of("부채비율", "자기자본비율"), evidence);
        }
        if (PROFITABILITY_WORDS.stream().anyMatch(question::contains)) {
            indicatorRows(corp, period, "M210000", List.of("ROE", "순이익률"), evidence);
        }
        return evidence;
    }

    private void indicatorRows(
            OpenDartClient.Corp corp,
            Period period,
            String classCode,
            List<String> names,
            List<Evidence> evidence) {
        List<JsonNode> rows =
                client.list(
                        "fnlttSinglIndx",
                        Map.of(
                                "corp_code", corp.code(),
                                "bsns_year", String.valueOf(period.year()),
                                "reprt_code", period.code(),
                                "idx_cl_code", classCode));
        for (String name : names) {
            rows.stream()
                    .filter(r -> r.path("idx_nm").asText().equals(name))
                    .findFirst()
                    .flatMap(
                            r ->
                                    KkiruFormat.number(r.path("idx_val").asText())
                                            .map(value -> indicator(corp, period, r, name, value)))
                    .ifPresent(evidence::add);
        }
    }

    private static Evidence indicator(
            OpenDartClient.Corp corp, Period period, JsonNode row, String name, BigDecimal value) {
        String date = settlementDate(row.path("stlm_dt").asText());
        return new Evidence(
                "financial:idx:"
                        + row.path("idx_code").asText()
                        + ":"
                        + period.year()
                        + ":"
                        + period.code(),
                corp.name()
                        + "의 "
                        + date
                        + " 기준 "
                        + name
                        + " 수치는 "
                        + KkiruFormat.percent(value)
                        + "입니다.",
                null);
    }

    /** 결산 기준일 {@code 2025-12-31}을 {@code 2025년 12월 31일}로. 모양이 다르면 빈 문자열. */
    private static String settlementDate(String iso) {
        try {
            return KkiruFormat.date(LocalDate.parse(iso));
        } catch (DateTimeParseException e) {
            return "";
        }
    }
}
