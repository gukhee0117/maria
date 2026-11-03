package com.app.maria.domain.community.kkiru;

import com.app.maria.global.client.opendart.OpenDartClient;
import com.app.maria.global.clock.service.BusinessClockService;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 배당 도구(설계 4.4 ②, OpenDART {@code alotMatter}). 직전 사업연도(없으면 그 전 연도) 사업보고서에서 보통주 주당
 * 현금배당금·현금배당수익률·(연결)현금배당성향·현금배당금 총액을 읽는다. 값이 {@code -}인 항목은 건너뛴다. 넷 다 없으면 "기재되어 있지 않다"는 한 문장이다(보고서에
 * 적힌 사실이지 "배당하지 않았다"는 단정이 아니다).
 */
@Component
@Order(10)
public class DividendTool extends OpenDartTool {
    private static final List<String> WORDS = List.of("배당");
    private static final String ANNUAL_REPORT = "11011";

    public DividendTool(OpenDartClient client, BusinessClockService clock) {
        super(client, clock);
    }

    @Override
    public String name() {
        return "dividend";
    }

    @Override
    protected List<String> words() {
        return WORDS;
    }

    @Override
    protected List<Evidence> collectFor(KkiruQuestion question, OpenDartClient.Corp corp) {
        int thisYear = clock.now().getYear();
        for (int year : new int[] {thisYear - 1, thisYear - 2}) {
            List<JsonNode> rows =
                    client.list(
                            "alotMatter",
                            Map.of(
                                    "corp_code", corp.code(),
                                    "bsns_year", String.valueOf(year),
                                    "reprt_code", ANNUAL_REPORT));
            if (!rows.isEmpty()) {
                return evidenceOf(rows, corp, year);
            }
        }
        return List.of();
    }

    private static List<Evidence> evidenceOf(
            List<JsonNode> rows, OpenDartClient.Corp corp, int year) {
        String receiptNo = rows.get(0).path("rcept_no").asText();
        String url = KkiruFormat.dartLink(receiptNo);
        String head = corp.name() + "의 " + year + "년 사업보고서에는 ";
        List<Evidence> evidence = new ArrayList<>();

        row(rows, "주당 현금배당금", true)
                .flatMap(r -> line(r, "보통주 주당 현금배당금이 ", "으로", KkiruFormat::won))
                .ifPresent(t -> evidence.add(item(receiptNo, "주당현금배당금", head + t, url)));
        row(rows, "현금배당수익률", true)
                .flatMap(r -> line(r, "보통주 현금배당수익률이 ", "로", KkiruFormat::percent))
                .ifPresent(t -> evidence.add(item(receiptNo, "현금배당수익률", head + t, url)));
        row(rows, "(연결)현금배당성향", false)
                .flatMap(r -> line(r, "연결 현금배당성향이 ", "로", KkiruFormat::percent))
                .ifPresent(t -> evidence.add(item(receiptNo, "현금배당성향", head + t, url)));
        row(rows, "현금배당금총액", false)
                .flatMap(r -> line(r, "현금배당금총액이 ", "으로", KkiruFormat::millionWon))
                .ifPresent(t -> evidence.add(item(receiptNo, "현금배당금총액", head + t, url)));

        if (evidence.isEmpty()) {
            evidence.add(
                    item(
                            receiptNo,
                            "none",
                            corp.name() + "의 " + year + "년 사업보고서에는 현금배당 금액이 기재되어 있지 않습니다.",
                            url));
        }
        return evidence;
    }

    /** {@code se}가 이름으로 시작하는 행. 보통주만 보는 항목은 stock_knd가 보통주인 행. */
    private static Optional<JsonNode> row(
            List<JsonNode> rows, String seStart, boolean commonStockOnly) {
        return rows.stream()
                .filter(r -> r.path("se").asText().startsWith(seStart))
                .filter(r -> !commonStockOnly || r.path("stock_knd").asText().equals("보통주"))
                .findFirst();
    }

    /**
     * 문장 조각 "{라벨}{당기값}{조사} 기재되어 있습니다(전기 …, 전전기 …)." 당기값이 없으면 empty. 전기·전전기 값은 있는 것만 괄호에 넣는다. 조사는 값의
     * 끝 글자("원"·"%")에 맞춰 호출하는 쪽이 정한다.
     */
    private static Optional<String> line(
            JsonNode row, String label, String particle, Function<BigDecimal, String> format) {
        Optional<BigDecimal> current = KkiruFormat.number(row.path("thstrm").asText());
        if (current.isEmpty()) {
            return Optional.empty();
        }
        List<String> before = new ArrayList<>();
        KkiruFormat.number(row.path("frmtrm").asText())
                .ifPresent(v -> before.add("전기 " + format.apply(v)));
        KkiruFormat.number(row.path("lwfr").asText())
                .ifPresent(v -> before.add("전전기 " + format.apply(v)));
        String tail = before.isEmpty() ? "" : "(" + String.join(", ", before) + ")";
        return Optional.of(
                label + format.apply(current.get()) + particle + " 기재되어 있습니다" + tail + ".");
    }

    private static Evidence item(String receiptNo, String key, String text, String url) {
        return new Evidence("dividend:" + receiptNo + ":" + key, text, url);
    }
}
