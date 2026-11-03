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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestTemplate;

/** 종목코드 → 고유번호 대응표: 파싱(실제 파일에서 뽑은 표본), 하루 1회 갱신, 갱신 실패, 동시 요청. */
class OpenDartCorpCodeTest {
    private static final String KEY = "abcd1234abcd1234abcd1234abcd1234abcd1234";

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

    private final RestTemplate downloadTemplate = new RestTemplate();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(downloadTemplate).build();
    private final MovableClock clock = new MovableClock();
    private final OpenDartProperties properties = new OpenDartProperties();
    private final OpenDartClient client;

    OpenDartCorpCodeTest() {
        properties.setApiKey(KEY);
        client =
                new OpenDartClient(
                        new RestTemplate(),
                        downloadTemplate,
                        properties,
                        new ObjectMapper(),
                        clock);
    }

    private static byte[] zipOf(String xml) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("CORPCODE.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static String sampleXml() throws IOException {
        return Files.readString(
                Path.of("src/test/resources/opendart/CORPCODE_sample.xml"), StandardCharsets.UTF_8);
    }

    private AtomicInteger serveZip(byte[] zip) {
        AtomicInteger calls = new AtomicInteger();
        server.expect(manyTimes(), anything())
                .andRespond(
                        request -> {
                            calls.incrementAndGet();
                            return MockRestResponseCreators.withSuccess(
                                            zip, MediaType.APPLICATION_OCTET_STREAM)
                                    .createResponse(request);
                        });
        return calls;
    }

    @Test
    @DisplayName("실제 파일에서 뽑은 표본: 상장사만 종목코드 → 고유번호로 담고, 비상장(종목코드가 공백 한 칸)은 뺀다")
    void parsesListedCompaniesOnly() throws IOException {
        Map<String, OpenDartClient.Corp> table =
                OpenDartClient.parseCorpCodes(new ByteArrayInputStream(zipOf(sampleXml())));

        assertThat(table).containsOnlyKeys("000660", "005930", "105560");
        assertThat(table.get("000660")).isEqualTo(new OpenDartClient.Corp("00164779", "SK하이닉스"));
        assertThat(table.get("005930")).isEqualTo(new OpenDartClient.Corp("00126380", "삼성전자"));
        assertThat(table.get("105560").code()).hasSize(8);
        assertThat(table.get("105560").name()).isNotBlank();
    }

    @Test
    @DisplayName("ZIP이 아닌 응답(인증키 오류 등 짧은 오류 본문)은 읽지 않고 접근 불가 오류다")
    void nonZipBody() {
        server.expect(anything())
                .andRespond(
                        withSuccess(
                                "{\"status\":\"010\",\"message\":\"등록되지 않은 키\"}",
                                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.corpCode("000660"))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.OPENDART_UNAVAILABLE));
    }

    @Test
    @DisplayName("대응표 요청 주소에 인증키가 들어가고, 회사 이름도 찾을 수 있고, 없는 종목은 empty다")
    void lookup() throws IOException {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/corpCode.xml?")))
                .andExpect(queryParam("crtfc_key", KEY))
                .andRespond(withSuccess(zipOf(sampleXml()), MediaType.APPLICATION_OCTET_STREAM));

        assertThat(client.corpCode("000660")).contains("00164779");
        assertThat(client.corp("005930")).contains(new OpenDartClient.Corp("00126380", "삼성전자"));
        assertThat(client.corpCode("999999")).isEmpty();
        assertThat(client.corpCode("900001")).isEmpty(); // 비상장 표본 회사도 표에 없다
        server.verify();
    }

    @Test
    @DisplayName("한 번 받은 표는 24시간 동안 다시 받지 않고, 24시간이 지나면 갱신한다")
    void refreshesEveryDay() throws IOException {
        AtomicInteger calls = serveZip(zipOf(sampleXml()));

        client.corpCode("000660");
        client.corpCode("005930");
        assertThat(calls).hasValue(1);

        clock.advance(Duration.ofHours(23).plusMinutes(59));
        client.corpCode("000660");
        assertThat(calls).hasValue(1);

        clock.advance(Duration.ofMinutes(1));
        client.corpCode("000660");
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("갱신이 실패하면 기존 표를 계속 쓰고, 10분이 지나기 전에는 다운로드를 다시 시도하지 않는다")
    void refreshFailureKeepsOldTable() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        server.expect(manyTimes(), anything())
                .andRespond(
                        request -> {
                            if (calls.incrementAndGet() == 1) {
                                return withSuccess(
                                                zipOf(sampleXml()),
                                                MediaType.APPLICATION_OCTET_STREAM)
                                        .createResponse(request);
                            }
                            return withServerError().createResponse(request);
                        });
        client.corpCode("000660");

        clock.advance(Duration.ofHours(24));
        assertThat(client.corpCode("000660")).contains("00164779"); // 갱신 실패, 기존 표
        assertThat(calls).hasValue(2);

        clock.advance(Duration.ofMinutes(9));
        assertThat(client.corpCode("005930")).contains("00126380");
        assertThat(calls).hasValue(2); // 아직 재시도 안 함

        clock.advance(Duration.ofMinutes(1));
        assertThat(client.corpCode("005930")).contains("00126380");
        assertThat(calls).hasValue(3); // 10분 뒤 재시도(또 실패, 기존 표)
    }

    @Test
    @DisplayName("처음부터 못 받으면 오류를 던지고, 다음 호출이 다시 시도한다")
    void initialFailureThrows() throws IOException {
        server.expect(anything()).andRespond(withServerError());
        server.expect(anything())
                .andRespond(withSuccess(zipOf(sampleXml()), MediaType.APPLICATION_OCTET_STREAM));

        assertThatThrownBy(() -> client.corpCode("000660"))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.OPENDART_UNAVAILABLE));
        assertThat(client.corpCode("000660")).contains("00164779");
    }

    @Test
    @DisplayName("빈 표(상장사 0개)는 받은 것으로 치지 않는다")
    void emptyTableIsFailure() throws IOException {
        server.expect(anything())
                .andRespond(
                        withSuccess(
                                zipOf("<?xml version=\"1.0\"?><result></result>"),
                                MediaType.APPLICATION_OCTET_STREAM));

        assertThatThrownBy(() -> client.corpCode("000660")).isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("동시에 여러 요청이 와도 다운로드는 한 번만 한다")
    void singleDownloadUnderConcurrency() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        byte[] zip = zipOf(sampleXml());
        server.expect(manyTimes(), anything())
                .andRespond(
                        request -> {
                            calls.incrementAndGet();
                            try {
                                Thread.sleep(300); // 다른 스레드들이 몰려오도록 느리게
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return withSuccess(zip, MediaType.APPLICATION_OCTET_STREAM)
                                    .createResponse(request);
                        });
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(
                    pool.submit(
                            () -> {
                                go.await();
                                return client.corpCode("000660").orElse("없음");
                            }));
        }
        go.countDown();
        for (Future<String> result : results) {
            assertThat(result.get()).isEqualTo("00164779");
        }
        pool.shutdown();

        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("키가 비어 있으면 대응표도 HTTP 없이 키 오류다")
    void disabledWithoutKey() {
        properties.setApiKey("");

        assertThatThrownBy(() -> client.corpCode("000660"))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.OPENDART_KEY_INVALID));
        server.verify();
    }

    @Test
    @DisplayName("앱 시작 직후 예열: 대응표를 미리 받아 두고, 이후 첫 조회는 다운로드를 하지 않는다")
    void warmUpDownloadsOnce() throws Exception {
        AtomicInteger calls = serveZip(zipOf(sampleXml()));

        client.onApplicationReady();
        client.warmUp().join();
        assertThat(client.corpCode("000660")).contains("00164779");

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("예열이 실패해도 예외가 나가지 않고, 키가 비어 있으면 예열하지 않는다")
    void warmUpFailureIsHarmless() throws Exception {
        server.expect(anything()).andRespond(withServerError());

        client.warmUp().join(); // 예외 없음

        properties.setApiKey("");
        client.onApplicationReady(); // 비활성: 아무 일도 없음(HTTP 없음)
        server.verify();
    }
}
