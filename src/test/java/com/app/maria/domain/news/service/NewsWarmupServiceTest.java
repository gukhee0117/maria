package com.app.maria.domain.news.service;

import static org.mockito.Mockito.*;

import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.news.config.NewsProperties;
import com.app.maria.domain.news.dto.NewsContract.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class NewsWarmupServiceTest {
    @Test
    void disabledByDefaultAndExplicitAccountsAreDeduplicatedAndBounded() {
        var service = mock(NewsBriefingService.class);
        var properties = new NewsProperties();
        var warmup = new NewsWarmupService(service, properties);
        try {
            warmup.start();
            verifyNoInteractions(service);
            properties.setWarmupCustomerIds(List.of(1L, 1L, 2L, 3L));
            when(service.briefing(anyLong()))
                    .thenReturn(
                            new Briefing(
                                    1L,
                                    InvestorType.STABLE,
                                    PersonalizationStatus.ACTIVE,
                                    "r1",
                                    List.of()));
            warmup.start();
            verify(service, timeout(2000)).briefing(1L);
            verify(service, timeout(2000)).briefing(2L);
            verify(service, never()).briefing(3L);
            verify(service, never()).summary(anyLong(), anyString(), anyString());
        } finally {
            warmup.close();
        }
    }

    @Test
    void sourceFailureDoesNotPreventNextConfiguredAccountFromWarming() {
        var service = mock(NewsBriefingService.class);
        var properties = new NewsProperties();
        properties.setWarmupCustomerIds(List.of(1L, 2L));
        when(service.briefing(1L)).thenThrow(new IllegalStateException());
        when(service.briefing(2L))
                .thenReturn(
                        new Briefing(
                                2L,
                                InvestorType.STABLE,
                                PersonalizationStatus.ACTIVE,
                                "r1",
                                List.of(
                                        new Selected(
                                                NewsSelectionServiceTest.article("a", 5, 5), 1))));
        var warmup = new NewsWarmupService(service, properties);
        try {
            warmup.start();
            verify(service, timeout(2000)).summary(2L, "a", "v1");
        } finally {
            warmup.close();
        }
    }
}
