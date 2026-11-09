package com.app.maria.domain.asset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.type.Status;
import com.app.maria.domain.asset.dto.AssetHoldingDTO;
import com.app.maria.domain.asset.mapper.AssetBriefingMapper;
import com.app.maria.domain.asset.price.AssetPriceProvider;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AssetBriefingTransactionTest {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new DataSourceTransactionManager(
                    new UnpooledDataSource(
                            "org.h2.Driver", "jdbc:h2:mem:asset_transaction", "sa", ""));
        }
    }

    @Test
    void dbReadsShareReadOnlyTransactionButPriceLookupRunsAfterItEnds() {
        var accountMapper = mock(AccountMapper.class);
        var mapper = mock(AssetBriefingMapper.class);
        var clock = mock(BusinessClockService.class);
        var provider = mock(AssetPriceProvider.class);
        var now = LocalDateTime.of(2026, 11, 2, 10, 30);
        var holding = new AssetHoldingDTO();
        holding.setTicker("005930");
        holding.setQty(new BigDecimal("2"));
        when(accountMapper.selectByCustomerId(7L))
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isTrue();
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isCurrentTransactionReadOnly())
                                    .isTrue();
                            return Optional.of(
                                    AccountDTO.builder()
                                            .accountId(11L)
                                            .status(Status.OPENED)
                                            .amount(BigDecimal.TEN)
                                            .build());
                        });
        when(clock.now()).thenReturn(now);
        when(mapper.sumHoldingCost(11L)).thenReturn(new BigDecimal("200"));
        when(mapper.sumFinalizedInflow(11L, now)).thenReturn(new BigDecimal("1000"));
        when(mapper.selectHoldings(11L)).thenReturn(List.of(holding));
        when(mapper.selectMonthlyPurchases(11L, now)).thenReturn(List.of());
        when(provider.getPrices(anySet()))
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return Map.of(
                                    "005930",
                                    new AssetPriceProvider.Quote(new BigDecimal("120"), now));
                        });
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AccountMapper.class, () -> accountMapper);
            context.registerBean(AssetBriefingMapper.class, () -> mapper);
            context.registerBean(BusinessClockService.class, () -> clock);
            context.registerBean(AssetPriceProvider.class, () -> provider);
            context.registerBean(
                    com.app.maria.domain.tax.mapper.TaxMapper.class,
                    () -> mock(com.app.maria.domain.tax.mapper.TaxMapper.class));
            context.registerBean(
                    com.app.maria.global.client.kis.KisDomesticPriceClient.class,
                    () -> {
                        var kis =
                                mock(com.app.maria.global.client.kis.KisDomesticPriceClient.class);
                        when(kis.getQuote(any()))
                                .thenThrow(
                                        new com.app.maria.global.exception.AppException(
                                                com.app.maria.global.exception.ErrorType
                                                        .KIS_PRICE_NOT_FOUND,
                                                "x"));
                        return kis;
                    });
            context.registerBean(
                    com.app.maria.global.client.kis.KisPriceClient.class,
                    () -> mock(com.app.maria.global.client.kis.KisPriceClient.class));
            context.registerBean(
                    com.app.maria.global.client.exchange.ExchangeRateClient.class,
                    () -> mock(com.app.maria.global.client.exchange.ExchangeRateClient.class));
            context.register(
                    Transactions.class,
                    AssetBriefingSnapshotReader.class,
                    AssetBriefingServiceImpl.class);
            context.refresh();
            var result = context.getBean(AssetBriefingService.class).getBriefing(7L);
            assertThat(result.getDomesticValuationAmount()).isEqualByComparingTo("240");
            assertThat(result.getValuationProfit()).isEqualByComparingTo("40");
            verify(provider).getPrices(java.util.Set.of("005930"));
        }
    }
}
