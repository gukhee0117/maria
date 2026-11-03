package com.app.maria.global.client.opendart;

import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * OpenDART(금융감독원 전자공시) 조회 클라이언트. 호출 이름과 인자를 받아 {@code list} 배열의 행을 돌려주고, 종목코드 → 고유번호 대응표를 맡는다.
 *
 * <ul>
 *   <li>응답 status: 000 정상, 013 빈 결과(오류가 아님), 020 한도 초과, 010·011·012·901 키 문제, 그 밖(800 점검·900 등)과
 *       네트워크·JSON 오류는 접근 불가.
 *   <li>인증키는 로그·예외 메시지에 남기지 않는다. 예외는 원인을 이어 붙이지 않는다(원인의 메시지에 URL이 들어 있다).
 *   <li>짧은 메모리 캐시: 정상 30분, 013은 10분, 최대 500개(오래된 것부터 제거). 오류는 캐시하지 않는다.
 *   <li>키가 비어 있으면 {@link #isEnabled()}가 false다. OpenDART 도구는 이때 꺼진다.
 * </ul>
 */
@Slf4j
@Component
public class OpenDartClient {
    static final Duration OK_TTL = Duration.ofMinutes(30);
    static final Duration EMPTY_TTL = Duration.ofMinutes(10);
    static final int CACHE_MAX = 500;
    static final Duration TABLE_REFRESH = Duration.ofHours(24);
    // 대응표 갱신이 실패하면 기존 표를 계속 쓰되, 매 요청마다 다운로드를 다시 시도하지 않도록 이 시간 뒤에 재시도한다.
    static final Duration TABLE_RETRY_AFTER_FAILURE = Duration.ofMinutes(10);

    private static final Pattern KEY_PARAM = Pattern.compile("crtfc_key=[^&\\s\"]*");

    /** 한 번의 조회 결과: list의 행과 전체 건수(total_count, 공시 목록처럼 일부만 오는 응답에서 의미가 있다). */
    public record Page(List<JsonNode> rows, int totalCount) {}

    private record CacheEntry(Page page, Instant expiresAt) {}

    /** 상장사 하나: OpenDART 고유번호와 회사 이름(공시 문장에 쓴다). */
    public record Corp(String code, String name) {}

    private record CorpTable(Map<String, Corp> corpByTicker, Instant refreshDueAt) {}

    private final RestTemplate restTemplate;
    private final RestTemplate downloadTemplate;
    private final OpenDartProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // 삽입 순서를 기억하는 맵: 가득 차면 맨 앞(가장 오래된 것)을 뺀다. 접근은 cache 객체로 잠근다.
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>();
    private final Object tableLock = new Object();
    private volatile CorpTable table;

    public OpenDartClient(
            @Qualifier("openDartRestTemplate") RestTemplate restTemplate,
            @Qualifier("openDartDownloadRestTemplate") RestTemplate downloadTemplate,
            OpenDartProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.restTemplate = restTemplate;
        this.downloadTemplate = downloadTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public boolean isEnabled() {
        return properties.hasKey();
    }

    /**
     * 앱이 뜬 직후 대응표를 미리 받아 둔다. 첫 다운로드가 느려서(실측 약 10초) 첫 질문이 도구 제한 시간에 걸리지 않게 하려는 것이다. 키가 비어 있으면 경고만
     * 남긴다.
     */
    @EventListener(ApplicationReadyEvent.class)
    void onApplicationReady() {
        if (!isEnabled()) {
            log.warn("[OPENDART_DISABLED] OPENDART_API_KEY가 비어 있어 OpenDART 도구를 쓰지 않습니다.");
            return;
        }
        warmUp();
    }

    /** 대응표 예열(비동기). 실패해도 앱에는 영향이 없고, 첫 사용 때 다시 시도한다. */
    CompletableFuture<Void> warmUp() {
        return CompletableFuture.runAsync(
                () -> {
                    try {
                        corpTable();
                    } catch (AppException e) {
                        log.warn("[OPENDART_WARMUP_FAILED] 첫 사용 때 다시 받습니다.");
                    }
                });
    }

    /** {@link #page}의 행만 돌려준다. */
    public List<JsonNode> list(String api, Map<String, String> params) {
        return page(api, params).rows();
    }

    /**
     * 호출 이름(예: {@code alotMatter})과 인자로 조회한다. 자료가 없으면(013) 빈 결과다.
     *
     * @throws AppException 한도·키·접근 불가
     */
    public Page page(String api, Map<String, String> params) {
        String cacheKey = cacheKey(api, params);
        Page cached = cached(cacheKey);
        if (cached != null) {
            return cached;
        }
        JsonNode root = fetch(api, params);
        String status = root.path("status").asText("");
        switch (status) {
            case "000" -> {
                List<JsonNode> rows = new ArrayList<>();
                root.path("list").forEach(rows::add);
                Page page = new Page(rows, root.path("total_count").asInt(rows.size()));
                remember(cacheKey, page, OK_TTL);
                return page;
            }
            case "013" -> {
                Page empty = new Page(List.of(), 0);
                remember(cacheKey, empty, EMPTY_TTL);
                return empty;
            }
            case "020" -> throw new AppException(ErrorType.OPENDART_LIMIT_EXCEEDED, "status=020");
            case "010", "011", "012", "901" -> {
                // 설정 오류(키 무효·IP 제한·사용 정지): 원인 파악용으로 status와 message만 따로 남긴다.
                log.error(
                        "[OPENDART_KEY_INVALID] api={} status={} message={}",
                        api,
                        status,
                        sanitize(root.path("message").asText("")));
                throw new AppException(ErrorType.OPENDART_KEY_INVALID, "status=" + status);
            }
            default ->
                    throw new AppException(
                            ErrorType.OPENDART_UNAVAILABLE,
                            "status=" + status + " " + sanitize(root.path("message").asText("")));
        }
    }

    /** 종목코드로 OpenDART 회사(고유번호·이름)를 찾는다. 대응표에 없는 종목(해외·비상장)은 empty다. */
    public Optional<Corp> corp(String ticker) {
        return Optional.ofNullable(corpTable().corpByTicker().get(ticker));
    }

    /** 종목코드로 OpenDART 고유번호만 찾는다. */
    public Optional<String> corpCode(String ticker) {
        return corp(ticker).map(Corp::code);
    }

    // ---------- 호출 ----------

    private JsonNode fetch(String api, Map<String, String> params) {
        if (!isEnabled()) {
            throw new AppException(ErrorType.OPENDART_KEY_INVALID, "키가 비어 있음");
        }
        URI uri = uri(api + ".json", params);
        try {
            String body = restTemplate.getForObject(uri, String.class);
            return objectMapper.readTree(body);
        } catch (RestClientException | JsonProcessingException | IllegalArgumentException e) {
            throw unavailable(api, e);
        }
    }

    private URI uri(String path, Map<String, String> params) {
        UriComponentsBuilder builder =
                UriComponentsBuilder.fromUriString(properties.getUrl())
                        .pathSegment(path)
                        .queryParam("crtfc_key", properties.getApiKey());
        new TreeMap<>(params).forEach(builder::queryParam);
        return builder.build().encode().toUri();
    }

    private AppException unavailable(String what, Exception cause) {
        // 원인을 잇지 않는다: 원인 메시지에 키가 든 URL이 있다.
        String detail = cause.getClass().getSimpleName() + " " + sanitize(cause.getMessage());
        log.warn("[OPENDART_UNAVAILABLE] {} {}", what, detail);
        return new AppException(ErrorType.OPENDART_UNAVAILABLE, detail);
    }

    /** 문자열 속 인증키 값을 가린다. */
    static String sanitize(String text) {
        return text == null ? "" : KEY_PARAM.matcher(text).replaceAll("crtfc_key=***");
    }

    // ---------- 캐시 ----------

    private static String cacheKey(String api, Map<String, String> params) {
        return api + "?" + new TreeMap<>(params);
    }

    private Page cached(String key) {
        synchronized (cache) {
            CacheEntry entry = cache.get(key);
            if (entry == null) {
                return null;
            }
            if (!entry.expiresAt().isAfter(clock.instant())) {
                cache.remove(key);
                return null;
            }
            return entry.page();
        }
    }

    private void remember(String key, Page page, Duration ttl) {
        synchronized (cache) {
            cache.remove(key); // 다시 넣으면 가장 최근으로
            while (cache.size() >= CACHE_MAX) {
                Iterator<String> oldest = cache.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
            cache.put(key, new CacheEntry(page, clock.instant().plus(ttl)));
        }
    }

    // ---------- 고유번호 대응표 ----------

    private CorpTable corpTable() {
        CorpTable current = table;
        if (current != null && !isDue(current)) {
            return current;
        }
        synchronized (tableLock) { // 동시에 여러 요청이 와도 다운로드는 한 번만
            current = table;
            if (current != null && !isDue(current)) {
                return current;
            }
            try {
                table = new CorpTable(downloadCorpCodes(), clock.instant().plus(TABLE_REFRESH));
            } catch (AppException e) {
                if (current == null) {
                    throw e; // 처음부터 못 받으면 도구는 오류를 낸다
                }
                log.warn("[OPENDART_CORPCODE_REFRESH_FAILED] 기존 대응표를 계속 씁니다.");
                table =
                        new CorpTable(
                                current.corpByTicker(),
                                clock.instant().plus(TABLE_RETRY_AFTER_FAILURE));
            }
            return table;
        }
    }

    private boolean isDue(CorpTable t) {
        return !t.refreshDueAt().isAfter(clock.instant());
    }

    private Map<String, Corp> downloadCorpCodes() {
        if (!isEnabled()) {
            throw new AppException(ErrorType.OPENDART_KEY_INVALID, "키가 비어 있음");
        }
        URI uri = uri("corpCode.xml", Map.of());
        try {
            Map<String, Corp> map =
                    downloadTemplate.execute(
                            uri,
                            HttpMethod.GET,
                            null,
                            response -> parseCorpCodes(response.getBody()));
            if (map == null || map.isEmpty()) {
                throw new AppException(ErrorType.OPENDART_UNAVAILABLE, "대응표가 비어 있음");
            }
            return map;
        } catch (RestClientException | IllegalStateException e) {
            throw unavailable("corpCode", e);
        }
    }

    /** ZIP 안의 XML(약 30MB)을 StAX로 흘려 읽는다. 상장사(종목코드가 있는 회사)만 담는다. */
    static Map<String, Corp> parseCorpCodes(InputStream body) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(body)) {
            ZipEntry entry = zip.getNextEntry();
            if (entry == null) {
                // 인증키 오류 등은 ZIP이 아니라 짧은 오류 본문이 온다.
                throw new IllegalStateException("대응표 응답이 ZIP이 아님");
            }
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader reader = factory.createXMLStreamReader(zip, "UTF-8");
            Map<String, Corp> byTicker = new HashMap<>();
            String corpCode = null;
            String corpName = null;
            String stockCode = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "list" -> {
                            corpCode = null;
                            corpName = null;
                            stockCode = null;
                        }
                        case "corp_code" -> corpCode = reader.getElementText().trim();
                        case "corp_name" -> corpName = reader.getElementText().trim();
                        case "stock_code" -> stockCode = reader.getElementText().trim();
                        default -> {}
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && "list".equals(reader.getLocalName())
                        && corpCode != null
                        && stockCode != null
                        && !stockCode.isEmpty()) {
                    byTicker.put(
                            stockCode,
                            new Corp(
                                    corpCode,
                                    corpName == null
                                            ? stockCode
                                            : corpName)); // 비상장의 stock_code는 공백 한 칸이라 trim 후 비어 있다
                }
            }
            return byTicker;
        } catch (XMLStreamException e) {
            throw new IllegalStateException("대응표 XML을 읽지 못함", e);
        }
    }
}
