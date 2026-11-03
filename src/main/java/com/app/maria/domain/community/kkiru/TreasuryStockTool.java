package com.app.maria.domain.community.kkiru;

import com.app.maria.global.client.opendart.OpenDartClient;
import com.app.maria.global.clock.service.BusinessClockService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 자기주식 도구(설계 4.4 ③, OpenDART {@code tsstkAqDecsn}·{@code tsstkDpDecsn}). 최근 12개월의 취득·처분 결정 공시를 각각
 * 최신 2건까지 읽는다. 결과 순서가 날짜순이 아니라서 접수번호(앞 8자리가 접수일) 내림차순으로 정렬한다. 둘 다 비면 "조회되지 않았다"는 한 문장이다(조회 결과에 대한
 * 사실이지 "없다"는 단정이 아니다).
 */
@Component
@Order(20)
public class TreasuryStockTool extends OpenDartTool {
    private static final List<String> WORDS = List.of("자사주", "자기주식", "자시주");
    private static final int PER_KIND = 2;

    public TreasuryStockTool(OpenDartClient client, BusinessClockService clock) {
        super(client, clock);
    }

    @Override
    public String name() {
        return "treasury";
    }

    @Override
    protected List<String> words() {
        return WORDS;
    }

    @Override
    protected List<Evidence> collectFor(KkiruQuestion question, OpenDartClient.Corp corp) {
        LocalDate today = clock.now().toLocalDate();
        Map<String, String> params =
                Map.of(
                        "corp_code", corp.code(),
                        "bgn_de", today.minusYears(1).format(COMPACT_DATE),
                        "end_de", today.format(COMPACT_DATE));
        List<JsonNode> acquisitions = latest(client.list("tsstkAqDecsn", params));
        List<JsonNode> disposals = latest(client.list("tsstkDpDecsn", params));

        List<Evidence> evidence = new ArrayList<>();
        acquisitions.forEach(r -> evidence.add(acquisition(r)));
        disposals.forEach(r -> evidence.add(disposal(r)));
        if (evidence.isEmpty()) {
            evidence.add(
                    new Evidence(
                            "treasury:none:" + question.ticker(),
                            "OpenDART에서 최근 12개월("
                                    + KkiruFormat.date(today.minusYears(1))
                                    + "~"
                                    + KkiruFormat.date(today)
                                    + ") 자기주식 취득·처분 결정 공시가 조회되지 않았습니다.",
                            null));
        }
        return evidence;
    }

    private static List<JsonNode> latest(List<JsonNode> rows) {
        return rows.stream()
                .sorted(
                        Comparator.comparing((JsonNode r) -> r.path("rcept_no").asText())
                                .reversed())
                .limit(PER_KIND)
                .toList();
    }

    private static Evidence acquisition(JsonNode r) {
        List<String> parts = new ArrayList<>();
        amount(r, "aqpln_stk_ostk", "예정 수량 보통주 ", "주", parts);
        won(r, "aqpln_prc_ostk", "예정 금액 ", parts);
        KkiruFormat.period(r.path("aqexpd_bgd").asText(), r.path("aqexpd_edd").asText())
                .ifPresent(p -> parts.add("취득 예정 기간 " + p));
        KkiruFormat.text(r, "aq_pp").ifPresent(v -> parts.add("목적 " + v));
        return decision(r, "treasury:acq:", "aq_dd", "자기주식 취득", parts);
    }

    private static Evidence disposal(JsonNode r) {
        List<String> parts = new ArrayList<>();
        amount(r, "dppln_stk_ostk", "예정 수량 보통주 ", "주", parts);
        won(r, "dppln_prc_ostk", "예정 금액 ", parts);
        KkiruFormat.period(r.path("dpprpd_bgd").asText(), r.path("dpprpd_edd").asText())
                .ifPresent(p -> parts.add("처분 예정 기간 " + p));
        KkiruFormat.text(r, "dp_pp").ifPresent(v -> parts.add("목적 " + v));
        return decision(r, "treasury:disp:", "dp_dd", "자기주식 처분", parts);
    }

    /** 결정일(없으면 접수일)을 앞에 둔 한 문장. 쓸 수 있는 값이 하나도 없으면 접수 사실만 적는다. */
    private static Evidence decision(
            JsonNode r, String idPrefix, String dateField, String kind, List<String> parts) {
        String receiptNo = r.path("rcept_no").asText();
        String date =
                KkiruFormat.date(r.path(dateField).asText())
                        .or(() -> KkiruFormat.dateOfCompact(receiptNo))
                        .orElse("");
        String corpName = r.path("corp_name").asText();
        String text =
                parts.isEmpty()
                        ? corpName + "의 " + date + " " + kind + " 결정 공시가 접수되었습니다."
                        : corpName
                                + "의 "
                                + date
                                + " "
                                + kind
                                + " 결정 내용은 "
                                + String.join(", ", parts)
                                + "입니다.";
        return new Evidence(idPrefix + receiptNo, text, KkiruFormat.dartLink(receiptNo));
    }

    private static void amount(
            JsonNode r, String field, String label, String unit, List<String> parts) {
        KkiruFormat.text(r, field)
                .flatMap(KkiruFormat::number)
                .ifPresent(v -> parts.add(label + KkiruFormat.count(v) + unit));
    }

    private static void won(JsonNode r, String field, String label, List<String> parts) {
        KkiruFormat.text(r, field)
                .flatMap(KkiruFormat::number)
                .ifPresent(v -> parts.add(label + KkiruFormat.won(v)));
    }
}
