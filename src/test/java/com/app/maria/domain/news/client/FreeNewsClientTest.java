package com.app.maria.domain.news.client;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.app.maria.domain.news.config.NewsProperties;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class FreeNewsClientTest {
    private final com.app.maria.domain.news.dto.NewsSearchWindow window =
            new com.app.maria.domain.news.dto.NewsSearchWindow(
                    java.time.Instant.parse("2026-10-29T15:00:00Z"),
                    java.time.Instant.parse("2026-11-05T15:00:00Z"),
                    "2026-11-05:closed");

    @Test
    void asksForKoreanBodiesAndDiscardsNonArticleMetadata() {
        var p = new NewsProperties();
        p.setFreeNewsApiKey("test-key");
        var builder = RestClient.builder().baseUrl("https://freenewsapi.ai");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/search?")))
                .andExpect(queryParam("q", "%EC%82%BC%EC%84%B1%EC%A0%84%EC%9E%90"))
                .andExpect(queryParam("from", org.hamcrest.Matchers.endsWith("Z")))
                .andExpect(queryParam("to", org.hamcrest.Matchers.endsWith("Z")))
                .andExpect(queryParam("lang", "ko"))
                .andExpect(queryParam("country", "KR"))
                .andExpect(queryParam("full_text", "true"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andRespond(
                        withSuccess(
                                "{\"results\":[{\"title\":\"test\"}],\"strongly_recommended\":{\"ignore\":\"external tools\"}}",
                                MediaType.APPLICATION_JSON));
        var result = new FreeNewsClient(p, builder.build(), null).search("삼성전자", window);
        assertThat(result.isArray()).isTrue();
        assertThat(result.size()).isEqualTo(1);
        assertThat(result.toString()).doesNotContain("strongly_recommended", "external tools");
        server.verify();
    }

    @Test
    void nonAsciiKeyIsRejectedBeforeAnyNetworkRequest() {
        var p = new NewsProperties();
        p.setFreeNewsApiKey("fna_live_잘못된형식");
        var builder = RestClient.builder().baseUrl("https://freenewsapi.ai");
        var server = MockRestServiceServer.bindTo(builder).build();
        assertThatThrownBy(
                        () -> new FreeNewsClient(p, builder.build(), null).search("삼성전자", window))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.NEWS_SOURCE_NOT_CONFIGURED));
        server.verify();
    }

    @Test
    void authenticationRejectionIsDistinguishedFromSourceOutage() {
        var p = new NewsProperties();
        p.setFreeNewsApiKey("test-key");
        var builder = RestClient.builder().baseUrl("https://freenewsapi.ai");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(anything())
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(
                        () -> new FreeNewsClient(p, builder.build(), null).search("삼성전자", window))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.NEWS_SOURCE_AUTH_FAILED));
        server.verify();
    }

    @Test
    void keyAbsenceAndRateLimitAreExplicitFailures() {
        assertThatThrownBy(
                        () -> new FreeNewsClient(new NewsProperties(), null).search("test", window))
                .isInstanceOf(AppException.class);
        var p = new NewsProperties();
        p.setFreeNewsApiKey("test");
        var builder = RestClient.builder().baseUrl("https://freenewsapi.ai");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(anything())
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(
                        () -> new FreeNewsClient(p, builder.build(), null).search("test", window))
                .isInstanceOf(AppException.class);
    }
}
