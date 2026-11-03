package com.app.maria.global.client.opendart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestTemplate;

/** 응답 해석(실제 응답 파일로 계약 고정), status 처리, 키 보호, 캐시. */
@ExtendWith(OutputCaptureExtension.class)
class OpenDartClientTest {
    private static final String KEY = "abcd1234abcd1234abcd1234abcd1234abcd1234";
    private static final MediaType JSON_UTF8 =
            MediaType.parseMediaType("application/json;charset=UTF-8");

    /** 손으로 옮기는 실제 시각. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-03T01:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private final RestTemplate restTemplate = new RestTemplate();
    private final RestTemplate downloadTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final MovableClock clock = new MovableClock();
    private final OpenDartProperties properties = new OpenDartProperties();
    private OpenDartClient client;

    OpenDartClientTest() {
        properties.setApiKey(KEY);
        client = newClient();
    }

    private OpenDartClient newClient() {
        return new OpenDartClient(
                restTemplate, downloadTemplate, properties, new ObjectMapper(), clock);
    }

    private static String fixture(String name) throws IOException {
        return Files.readString(
                Path.of("src/test/resources/opendart", name), StandardCharsets.UTF_8);
    }

    private void respond(String api, String body) {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/" + api + ".json?")))
                .andRespond(withSuccess(body, JSON_UTF8));
    }

    private static String status(String code) {
        return "{\"status\":\"" + code + "\",\"message\":\"테스트 메시지\"}";
    }

    private static final Map<String, String> PARAMS =
            Map.of("corp_code", "00164779", "bsns_year", "2025");

    // ---------- 계약: 실제 응답 파일 ----------

    @Test
    @DisplayName("배당: 실제 응답의 행·필드를 읽는다(보통주 주당 현금배당금 3,000 / 전기 2,204 / 전전기 1,200, 우선주는 '-')")
    void contractDividend() throws IOException {
        respond("alotMatter", fixture("alotMatter_hynix_2025_annual.json"));

        List<JsonNode> rows = client.list("alotMatter", PARAMS);

        assertThat(rows).hasSize(15);
        List<JsonNode> perShare =
                rows.stream().filter(r -> r.path("se").asText().startsWith("주당 현금배당금")).toList();
        assertThat(perShare).hasSize(2);
        JsonNode common = perShare.get(0);
        assertThat(common.path("stock_knd").asText()).isEqualTo("보통주");
        assertThat(common.path("thstrm").asText()).isEqualTo("3,000");
        assertThat(common.path("frmtrm").asText()).isEqualTo("2,204");
        assertThat(common.path("lwfr").asText()).isEqualTo("1,200");
        assertThat(perShare.get(1).path("thstrm").asText()).isEqualTo("-");
        assertThat(common.path("rcept_no").asText()).isNotBlank();
    }

    @Test
    @DisplayName("주요계정: 금액은 원 단위 전체 숫자 문자열, 연결(CFS)과 별도(OFS)가 각각 있다")
    void contractAccounts() throws IOException {
        respond("fnlttSinglAcnt", fixture("fnlttSinglAcnt_hynix_2025_annual.json"));

        List<JsonNode> rows = client.list("fnlttSinglAcnt", PARAMS);

        assertThat(rows).hasSize(30);
        JsonNode revenue =
                rows.stream()
                        .filter(
                                r ->
                                        r.path("account_nm").asText().equals("매출액")
                                                && r.path("fs_div").asText().equals("CFS"))
                        .findFirst()
                        .orElseThrow();
        assertThat(revenue.path("thstrm_amount").asText()).isEqualTo("97,146,675,000,000");
        assertThat(rows.stream().map(r -> r.path("fs_div").asText()).distinct())
                .containsExactlyInAnyOrder("CFS", "OFS");
    }

    @Test
    @DisplayName("금융업(KB금융)은 '매출액' 계정이 없고 순이자손익·영업이익(손실) 등이 있다")
    void contractFinancialCompanyHasNoRevenue() throws IOException {
        respond("fnlttSinglAcnt", fixture("fnlttSinglAcnt_kb_2025_annual.json"));

        List<String> names =
                client.list("fnlttSinglAcnt", PARAMS).stream()
                        .map(r -> r.path("account_nm").asText())
                        .toList();

        assertThat(names).doesNotContain("매출액").contains("순이자손익", "영업이익(손실)", "당기순이익(손실)");
    }

    @Test
    @DisplayName("반기 보고서 응답도 같은 필드 모양이고, 아직 없는 분기(2026 3분기)는 013이라 빈 목록이다")
    void contractHalfAndNoData() throws IOException {
        respond("fnlttSinglAcnt", fixture("fnlttSinglAcnt_hynix_2026_half.json"));
        respond("fnlttSinglAcnt", fixture("fnlttSinglAcnt_hynix_2026_q3_no_data.json"));

        assertThat(client.list("fnlttSinglAcnt", Map.of("reprt_code", "11012"))).hasSize(30);
        assertThat(client.list("fnlttSinglAcnt", Map.of("reprt_code", "11014"))).isEmpty();
    }

    @Test
    @DisplayName("재무지표: idx_val이 null이 아니라 키 자체가 없는 행이 있다(22행 중 6행) — 쓰는 쪽은 없는 값을 건너뛰어야 한다")
    void contractIndicatorValueMayBeMissing() throws IOException {
        respond("fnlttSinglIndx", fixture("fnlttSinglIndx_hynix_2025.json"));

        List<JsonNode> rows = client.list("fnlttSinglIndx", PARAMS);

        assertThat(rows).hasSize(22);
        assertThat(rows.stream().filter(r -> !r.has("idx_val")).count()).isEqualTo(6);
        JsonNode equityRatio =
                rows.stream()
                        .filter(r -> r.path("idx_nm").asText().equals("자기자본비율"))
                        .findFirst()
                        .orElseThrow();
        assertThat(equityRatio.path("idx_val").asText()).isEqualTo("68.519");
        assertThat(equityRatio.path("stlm_dt").asText()).isEqualTo("2025-12-31");
    }

    @Test
    @DisplayName("자기주식 취득: 날짜가 한글 문자열이고, 결과가 날짜순이 아니다(접수번호 앞 8자리가 접수일)")
    void contractTreasuryStock() throws IOException {
        respond("tsstkAqDecsn", fixture("tsstkAqDecsn_samsung.json"));

        List<JsonNode> rows = client.list("tsstkAqDecsn", PARAMS);

        assertThat(rows).hasSize(7);
        assertThat(rows.get(0).path("aq_dd").asText()).isEqualTo("2026년 01월 28일");
        List<String> receipts = rows.stream().map(r -> r.path("rcept_no").asText()).toList();
        assertThat(receipts).isNotEqualTo(receipts.stream().sorted().toList());
        assertThat(receipts)
                .isNotEqualTo(
                        receipts.stream().sorted(java.util.Comparator.reverseOrder()).toList());
        assertThat(rows.get(0).has("aqpln_stk_ostk")).isTrue();
    }

    @Test
    @DisplayName("자기주식 처분과 유상증자: 행이 읽힌다. 유상증자에는 결정일·발행가 필드가 없고 증자방식만 있다")
    void contractTreasuryDisposalAndCapitalIncrease() throws IOException {
        respond("tsstkDpDecsn", fixture("tsstkDpDecsn_samsung.json"));
        respond("piicDecsn", fixture("piicDecsn_hynix.json"));

        assertThat(client.list("tsstkDpDecsn", PARAMS)).hasSize(9);
        JsonNode increase = client.list("piicDecsn", PARAMS).get(0);
        assertThat(increase.path("ic_mthn").asText()).isEqualTo("제3자배정증자");
        assertThat(increase.has("bddd")).isFalse();
        assertThat(increase.has("pym_dd")).isFalse();
    }

    @Test
    @DisplayName("공시 목록: 보고서명 뒤에 공백이 붙어 온다(쓰는 쪽이 trim). 개인 이름은 저장소 픽스처에서 지웠다")
    void contractDisclosureList() throws IOException {
        respond("list", fixture("list_hynix_30days.json"));

        List<JsonNode> rows = client.list("list", Map.of("corp_code", "00164779"));

        assertThat(rows).hasSize(10);
        JsonNode first = rows.get(0);
        assertThat(first.path("report_nm").asText()).isEqualTo("풍문또는보도에대한해명(미확정)              ");
        assertThat(first.path("rcept_dt").asText()).matches("\\d{8}");
        assertThat(first.path("rcept_no").asText()).hasSize(14);
    }

    // ---------- status 처리 ----------

    @Test
    @DisplayName("013은 오류가 아니라 빈 목록이다")
    void noDataIsEmpty() {
        respond("alotMatter", status("013"));

        assertThat(client.list("alotMatter", PARAMS)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "020, OPENDART_LIMIT_EXCEEDED",
        "010, OPENDART_KEY_INVALID",
        "011, OPENDART_KEY_INVALID",
        "012, OPENDART_KEY_INVALID",
        "901, OPENDART_KEY_INVALID",
        "800, OPENDART_UNAVAILABLE",
        "900, OPENDART_UNAVAILABLE",
        "999, OPENDART_UNAVAILABLE"
    })
    @DisplayName("status별로 의도한 오류 종류로 바뀐다")
    void statusMapping(String code, ErrorType expected) {
        respond("alotMatter", status(code));

        assertThatThrownBy(() -> client.list("alotMatter", PARAMS))
                .isInstanceOfSatisfying(
                        AppException.class, e -> assertThat(e.getErrorType()).isEqualTo(expected));
    }

    @Test
    @DisplayName("깨진 JSON·빈 본문·HTTP 5xx·연결 오류는 모두 접근 불가 오류다")
    void brokenResponses() {
        respond("alotMatter", "<html>점검 중</html>");
        server.expect(anything()).andRespond(withServerError());
        server.expect(anything()).andRespond(withSuccess("", JSON_UTF8));
        server.expect(anything())
                .andRespond(
                        request -> {
                            throw new IOException("connect failed");
                        });

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(
                            () ->
                                    client.list(
                                            "alotMatter",
                                            Map.of("n", String.valueOf(System.nanoTime()))))
                    .isInstanceOfSatisfying(
                            AppException.class,
                            e ->
                                    assertThat(e.getErrorType())
                                            .isEqualTo(ErrorType.OPENDART_UNAVAILABLE));
        }
    }

    // ---------- 키 보호 ----------

    @Test
    @DisplayName("요청 주소에 인증키와 인자가 들어간다")
    void requestUrl() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/alotMatter.json?")))
                .andExpect(queryParam("crtfc_key", KEY))
                .andExpect(queryParam("corp_code", "00164779"))
                .andExpect(queryParam("bsns_year", "2025"))
                .andRespond(withSuccess(status("013"), JSON_UTF8));

        client.list("alotMatter", PARAMS);

        server.verify();
    }

    @Test
    @DisplayName("연결 오류 예외·로그 어디에도 인증키가 없고, 원인 예외를 잇지 않는다")
    void keyIsNeverExposed(CapturedOutput output) {
        server.expect(anything())
                .andRespond(
                        request -> {
                            throw new IOException(
                                    "I/O error on GET request for \"https://opendart.fss.or.kr/api/x.json?crtfc_key="
                                            + KEY
                                            + "&a=1\": timeout");
                        });
        server.expect(anything()).andRespond(withSuccess(status("012"), JSON_UTF8));

        AppException network = catchAppException(() -> client.list("alotMatter", Map.of("a", "1")));
        AppException keyError =
                catchAppException(() -> client.list("alotMatter", Map.of("a", "2")));

        assertThat(network.getCause()).isNull();
        assertThat(String.valueOf(network.getErrorData()))
                .doesNotContain(KEY)
                .contains("crtfc_key=***");
        assertThat(String.valueOf(keyError.getErrorData())).doesNotContain(KEY);
        assertThat(output.getAll()).doesNotContain(KEY);
    }

    @Test
    @DisplayName("sanitize는 crtfc_key 값만 가리고 다른 인자는 둔다")
    void sanitize() {
        assertThat(OpenDartClient.sanitize("GET x.json?crtfc_key=SECRET&corp_code=1 실패"))
                .isEqualTo("GET x.json?crtfc_key=***&corp_code=1 실패");
        assertThat(OpenDartClient.sanitize(null)).isEmpty();
    }

    @Test
    @DisplayName("키가 비어 있으면 비활성이고, 호출하면 HTTP 없이 키 오류다")
    void disabledWithoutKey() {
        properties.setApiKey("  ");
        OpenDartClient disabled = newClient();

        assertThat(disabled.isEnabled()).isFalse();
        assertThatThrownBy(() -> disabled.list("alotMatter", PARAMS))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.OPENDART_KEY_INVALID));
        server.verify(); // 기대한 요청이 없으니 HTTP가 나갔다면 실패
    }

    // ---------- 캐시 ----------

    private AtomicInteger countingServer(String body) {
        AtomicInteger calls = new AtomicInteger();
        server.expect(manyTimes(), anything())
                .andRespond(
                        request -> {
                            calls.incrementAndGet();
                            return MockRestResponseCreators.withSuccess(body, JSON_UTF8)
                                    .createResponse(request);
                        });
        return calls;
    }

    @Test
    @DisplayName("같은 호출은 정상 30분 동안 외부에 다시 묻지 않고, 30분이 지나면 다시 묻는다")
    void cacheOkForThirtyMinutes() {
        AtomicInteger calls =
                countingServer("{\"status\":\"000\",\"message\":\"정상\",\"list\":[{\"a\":1}]}");

        client.list("alotMatter", PARAMS);
        client.list("alotMatter", PARAMS);
        assertThat(calls).hasValue(1);

        clock.advance(Duration.ofMinutes(29));
        client.list("alotMatter", PARAMS);
        assertThat(calls).hasValue(1);

        clock.advance(Duration.ofMinutes(1));
        client.list("alotMatter", PARAMS);
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("013(빈 결과)은 10분만 캐시한다")
    void cacheEmptyForTenMinutes() {
        AtomicInteger calls = countingServer(status("013"));

        client.list("alotMatter", PARAMS);
        clock.advance(Duration.ofMinutes(9));
        client.list("alotMatter", PARAMS);
        assertThat(calls).hasValue(1);

        clock.advance(Duration.ofMinutes(1));
        client.list("alotMatter", PARAMS);
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("인자나 호출 이름이 다르면 다른 캐시 항목이고, 인자 순서는 상관없다")
    void cacheKeyIgnoresParamOrder() {
        AtomicInteger calls = countingServer(status("013"));

        client.list("alotMatter", new java.util.LinkedHashMap<>(Map.of("a", "1", "b", "2")));
        client.list(
                "alotMatter",
                new java.util.TreeMap<>(java.util.Comparator.reverseOrder()) {
                    {
                        put("a", "1");
                        put("b", "2");
                    }
                });
        assertThat(calls).hasValue(1);

        client.list("alotMatter", Map.of("a", "1", "b", "3"));
        client.list("list", Map.of("a", "1", "b", "2"));
        assertThat(calls).hasValue(3);
    }

    @Test
    @DisplayName("오류 응답은 캐시하지 않는다(다음 호출은 다시 묻는다)")
    void errorsAreNotCached() {
        server.expect(anything()).andRespond(withSuccess(status("800"), JSON_UTF8));
        server.expect(anything())
                .andRespond(withSuccess("{\"status\":\"000\",\"list\":[{\"a\":1}]}", JSON_UTF8));

        assertThatThrownBy(() -> client.list("alotMatter", PARAMS))
                .isInstanceOf(AppException.class);
        assertThat(client.list("alotMatter", PARAMS)).hasSize(1);
        server.verify();
    }

    @Test
    @DisplayName("캐시는 500개까지이고, 넘치면 가장 오래된 것부터 빠진다")
    void cacheEvictsOldest() {
        AtomicInteger calls = countingServer(status("013"));
        for (int i = 0; i <= OpenDartClient.CACHE_MAX; i++) { // 501개
            client.list("alotMatter", Map.of("n", String.valueOf(i)));
        }
        assertThat(calls).hasValue(OpenDartClient.CACHE_MAX + 1);

        client.list(
                "alotMatter",
                Map.of("n", String.valueOf(OpenDartClient.CACHE_MAX))); // 가장 최근: 캐시에 있음
        assertThat(calls).hasValue(OpenDartClient.CACHE_MAX + 1);
        client.list("alotMatter", Map.of("n", "0")); // 가장 오래된 것: 빠졌음
        assertThat(calls).hasValue(OpenDartClient.CACHE_MAX + 2);
    }

    private static AppException catchAppException(Runnable action) {
        try {
            action.run();
        } catch (AppException e) {
            return e;
        }
        throw new AssertionError("AppException이 나야 한다");
    }
}
