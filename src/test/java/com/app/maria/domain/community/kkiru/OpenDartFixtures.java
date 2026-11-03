package com.app.maria.domain.community.kkiru;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.app.maria.global.client.opendart.OpenDartClient;
import com.app.maria.global.clock.service.BusinessClockService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** OpenDART 도구 테스트가 같이 쓰는 도우미: 실제 응답 픽스처 읽기, 가짜 클라이언트·업무시계. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class OpenDartFixtures {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 10, 0);
    static final OpenDartClient.Corp HYNIX = new OpenDartClient.Corp("00164779", "SK하이닉스");
    static final OpenDartClient.Corp SAMSUNG = new OpenDartClient.Corp("00126380", "삼성전자");
    static final OpenDartClient.Corp KB = new OpenDartClient.Corp("00688996", "KB금융");

    static JsonNode root(String file) {
        try {
            return MAPPER.readTree(Files.readString(Path.of("src/test/resources/opendart", file)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<JsonNode> rows(String file) {
        List<JsonNode> rows = new ArrayList<>();
        root(file).path("list").forEach(rows::add);
        return rows;
    }

    static List<JsonNode> rows(String file, Predicate<JsonNode> keep) {
        return rows(file).stream().filter(keep).toList();
    }

    static OpenDartClient.Page page(String file) {
        JsonNode root = root(file);
        return new OpenDartClient.Page(rows(file), root.path("total_count").asInt());
    }

    static JsonNode json(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 키가 있고(enabled), 삼성전자·SK하이닉스·KB금융만 대응표에 있는 가짜 클라이언트. */
    static OpenDartClient enabledClient() {
        OpenDartClient client = mock(OpenDartClient.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.corp("000660")).thenReturn(Optional.of(HYNIX));
        when(client.corp("005930")).thenReturn(Optional.of(SAMSUNG));
        when(client.corp("105560")).thenReturn(Optional.of(KB));
        return client;
    }

    static BusinessClockService clock() {
        BusinessClockService clock = mock(BusinessClockService.class);
        when(clock.now()).thenReturn(NOW);
        return clock;
    }

    static KkiruQuestion question(String ticker, String text) {
        return new KkiruQuestion(1L, null, ticker, text);
    }
}
