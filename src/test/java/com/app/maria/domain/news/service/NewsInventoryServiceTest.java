package com.app.maria.domain.news.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.news.dto.NewsHoldingRow;
import com.app.maria.domain.news.mapper.NewsInventoryMapper;
import java.util.*;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.junit.jupiter.api.Test;

class NewsInventoryServiceTest {
    @Test
    void dynamicPortfoliosAreGroupedWithoutKeepingCustomerIdsInOutput() {
        var mapper = mock(NewsInventoryMapper.class);
        doAnswer(
                        invocation -> {
                            ResultHandler<NewsHoldingRow> handler = invocation.getArgument(0);
                            for (var row :
                                    List.of(
                                            new NewsHoldingRow(1L, "123456", "새종목"),
                                            new NewsHoldingRow(2L, "123456", "새종목"),
                                            new NewsHoldingRow(3L, "654321", "다른새종목"))) {
                                var result = new DefaultResultContext<NewsHoldingRow>();
                                result.nextResultObject(row);
                                handler.handleResult(result);
                            }
                            return null;
                        })
                .when(mapper)
                .scanHoldings(any());
        var inventoryService = new NewsInventoryService(mapper);
        var inventory = inventoryService.load();
        assertThat(inventory.stockNames()).containsOnlyKeys("123456", "654321");
        assertThat(inventory.groups()).hasSize(2);
        assertThat(inventory.groups().get(0).customers()).isEqualTo(2);
        inventoryService.load();
        verify(mapper, times(1)).scanHoldings(any());
    }
}
