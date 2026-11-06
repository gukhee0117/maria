package com.app.maria.domain.news.client;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.app.maria.domain.news.config.NewsProperties;
import com.app.maria.global.exception.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class NaverNewsSearchClientTest {
    @Test
    void rawItemsAndOrderArePreservedWithApiHubHeadersAndQuery() throws Exception {
        var properties = new NewsProperties();
        properties.setNaverClientId("id");
        properties.setNaverClientSecret("secret");
        var builder = RestClient.builder().baseUrl("https://naverapihub.apigw.ntruss.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        String raw =
                """
                {"total":2,"items":[{"title":"B <b>뉴스</b>","link":"https://b"},{"title":"A 뉴스","link":"https://a"}]}
                """;
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/search/v1/news?")))
                .andExpect(queryParam("query", "%EC%82%BC%EC%84%B1%EC%A0%84%EC%9E%90"))
                .andExpect(queryParam("sort", "sim"))
                .andExpect(header("X-NCP-APIGW-API-KEY-ID", "id"))
                .andExpect(header("X-NCP-APIGW-API-KEY", "secret"))
                .andRespond(withSuccess(raw, MediaType.APPLICATION_JSON));
        assertThat(new NaverNewsSearchClient(properties, builder.build()).search("삼성전자"))
                .isEqualTo(new ObjectMapper().readTree(raw));
        server.verify();
    }

    @Test
    void missingCredentialsAndUpstreamFailureAreExplicitNotMockNews() {
        var properties = new NewsProperties();
        assertThatThrownBy(() -> new NaverNewsSearchClient(properties).search("종목"))
                .isInstanceOf(AppException.class);
        properties.setNaverClientId("id");
        properties.setNaverClientSecret("secret");
        var builder = RestClient.builder().baseUrl("https://example.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withServerError());
        assertThatThrownBy(
                        () -> new NaverNewsSearchClient(properties, builder.build()).search("종목"))
                .isInstanceOf(AppException.class);
    }
}
