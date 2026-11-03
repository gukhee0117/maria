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
 * 공시 목록 도구(설계 4.4 ⑥, OpenDART {@code list}). 최근 30일 공시를 한 번 조회해 총 건수 한 문장과 최신 5건을 근거로 낸다.
 * 공시자({@code flr_nm})에는 임원 개인 이름이 올 수 있어서 쓰지 않는다. 보고서명 뒤에는 공백이 붙어 오므로 trim한다.
 */
@Component
@Order(50)
public class DisclosureTool extends OpenDartTool {
    private static final List<String> WORDS = List.of("공시", "dart", "다트", "보고서");
    private static final int DAYS = 30;
    private static final int LATEST = 5;

    public DisclosureTool(OpenDartClient client, BusinessClockService clock) {
        super(client, clock);
    }

    @Override
    public String name() {
        return "disclosure";
    }

    @Override
    protected List<String> words() {
        return WORDS;
    }

    @Override
    protected List<Evidence> collectFor(KkiruQuestion question, OpenDartClient.Corp corp) {
        LocalDate today = clock.now().toLocalDate();
        OpenDartClient.Page page =
                client.page(
                        "list",
                        Map.of(
                                "corp_code", corp.code(),
                                "bgn_de", today.minusDays(DAYS).format(COMPACT_DATE),
                                "end_de", today.format(COMPACT_DATE),
                                "last_reprt_at", "Y",
                                "page_count", "10"));
        if (page.rows().isEmpty()) {
            return List.of(
                    new Evidence(
                            "disclosure:none:" + question.ticker(),
                            "최근 " + DAYS + "일 동안 공시가 조회되지 않았습니다.",
                            null));
        }
        List<Evidence> evidence = new ArrayList<>();
        evidence.add(
                new Evidence(
                        "disclosure:count:" + question.ticker(),
                        corp.name() + "의 최근 " + DAYS + "일 공시는 " + page.totalCount() + "건입니다.",
                        null));
        page.rows().stream()
                .sorted(
                        Comparator.comparing((JsonNode r) -> r.path("rcept_no").asText())
                                .reversed())
                .limit(LATEST)
                .forEach(r -> latest(r).ifPresent(evidence::add));
        return evidence;
    }

    private static java.util.Optional<Evidence> latest(JsonNode r) {
        String receiptNo = r.path("rcept_no").asText();
        String title = r.path("report_nm").asText().trim();
        if (title.isEmpty()) {
            return java.util.Optional.empty();
        }
        String date = KkiruFormat.dateOfCompact(r.path("rcept_dt").asText()).orElse("");
        return java.util.Optional.of(
                new Evidence(
                        "disclosure:" + receiptNo,
                        date + "에 \"" + title + "\" 공시가 접수되었습니다.",
                        KkiruFormat.dartLink(receiptNo)));
    }
}
