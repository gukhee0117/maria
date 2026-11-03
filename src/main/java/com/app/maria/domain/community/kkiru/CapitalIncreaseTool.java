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
 * 유상증자 도구(설계 4.4 ④, OpenDART {@code piicDecsn}). 최근 12개월 결정 공시를 최신 2건까지 읽는다. 이 API에는 결정일·발행가 필드가
 * 없어서 접수번호 앞 8자리를 접수일로 쓰고, 발행가는 말하지 않는다. 감자·전환사채·합병 등 다른 주요사항보고서는 호출 이름을 확인한 뒤 같은 틀로 추가한다.
 */
@Component
@Order(30)
public class CapitalIncreaseTool extends OpenDartTool {
    private static final List<String> WORDS = List.of("유증", "유상증자", "증자");
    private static final int LATEST = 2;

    /** 자금조달 목적 필드와 이름, 표시 순서(OpenDART 개발가이드 DS005 유상증자 결정 응답 설명과 같음). */
    private static final List<Map.Entry<String, String>> PURPOSES =
            List.of(
                    Map.entry("fdpp_fclt", "시설자금"),
                    Map.entry("fdpp_op", "운영자금"),
                    Map.entry("fdpp_dtrp", "채무상환자금"),
                    Map.entry("fdpp_bsninh", "영업양수자금"),
                    Map.entry("fdpp_ocsa", "타법인 증권 취득자금"),
                    Map.entry("fdpp_etc", "기타자금"));

    public CapitalIncreaseTool(OpenDartClient client, BusinessClockService clock) {
        super(client, clock);
    }

    @Override
    public String name() {
        return "capital";
    }

    @Override
    protected List<String> words() {
        return WORDS;
    }

    @Override
    protected List<Evidence> collectFor(KkiruQuestion question, OpenDartClient.Corp corp) {
        LocalDate today = clock.now().toLocalDate();
        List<JsonNode> rows =
                client.list(
                        "piicDecsn",
                        Map.of(
                                "corp_code", corp.code(),
                                "bgn_de", today.minusYears(1).format(COMPACT_DATE),
                                "end_de", today.format(COMPACT_DATE)));
        if (rows.isEmpty()) {
            return List.of(
                    new Evidence(
                            "capital:none:" + question.ticker(),
                            "OpenDART에서 최근 12개월("
                                    + KkiruFormat.date(today.minusYears(1))
                                    + "~"
                                    + KkiruFormat.date(today)
                                    + ") 유상증자 결정 공시가 조회되지 않았습니다.",
                            null));
        }
        return rows.stream()
                .sorted(
                        Comparator.comparing((JsonNode r) -> r.path("rcept_no").asText())
                                .reversed())
                .limit(LATEST)
                .map(CapitalIncreaseTool::evidenceOf)
                .toList();
    }

    private static Evidence evidenceOf(JsonNode r) {
        String receiptNo = r.path("rcept_no").asText();
        List<String> parts = new ArrayList<>();
        KkiruFormat.text(r, "ic_mthn").ifPresent(v -> parts.add("증자방식 " + v));
        KkiruFormat.text(r, "nstk_ostk_cnt")
                .flatMap(KkiruFormat::number)
                .ifPresent(v -> parts.add("신주 보통주 " + KkiruFormat.count(v) + "주"));
        for (Map.Entry<String, String> purpose : PURPOSES) {
            KkiruFormat.text(r, purpose.getKey())
                    .flatMap(KkiruFormat::number)
                    .ifPresent(
                            v ->
                                    parts.add(
                                            "자금조달 목적 "
                                                    + purpose.getValue()
                                                    + " "
                                                    + KkiruFormat.won(v)));
        }
        String date = KkiruFormat.dateOfCompact(receiptNo).orElse("");
        String head = r.path("corp_name").asText() + "의 유상증자 결정 공시(" + date + " 접수)";
        String text =
                parts.isEmpty()
                        ? head + "가 접수되었습니다."
                        : head + "는 " + String.join(", ", parts) + "입니다.";
        return new Evidence("capital:" + receiptNo, text, KkiruFormat.dartLink(receiptNo));
    }
}
