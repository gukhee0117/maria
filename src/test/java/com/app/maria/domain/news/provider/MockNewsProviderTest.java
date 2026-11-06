package com.app.maria.domain.news.provider;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

class MockNewsProviderTest {
    @Test
    void selfAuthoredBodiesContainEveryFactAndRiskAndHaveUniqueStableIds() throws Exception {
        var provider =
                new MockNewsProvider(new ObjectMapper().registerModule(new JavaTimeModule()));
        assertThat(provider.articles()).hasSize(10);
        assertThat(provider.articles()).extracting(a -> a.id()).doesNotHaveDuplicates();
        provider.articles()
                .forEach(
                        a -> {
                            assertThat(a.source()).contains("시연");
                            assertThat(a.url()).isNull();
                            a.facts().forEach(f -> assertThat(a.body()).contains(f));
                            a.risks().forEach(r -> assertThat(a.body()).contains(r));
                        });
    }
}
