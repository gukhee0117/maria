package com.app.maria.domain.tax.service;

import static com.app.maria.domain.tax.fixture.TaxFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.type.BenefitType;
import com.app.maria.domain.settlement.component.SettlementBusinessDayCalculator;
import com.app.maria.domain.tax.mapper.TaxMapper;
import com.app.maria.global.client.exchange.ExchangeRateClient;
import com.app.maria.global.client.kis.KisPriceClient;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.properties.RiaTaxProperties;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TaxExpectedSnapshotTransactionTest {
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions {
        @Bean
        DataSource dataSource() {
            return new UnpooledDataSource(
                    "org.h2.Driver", "jdbc:h2:mem:tax_expected_transaction", "sa", "");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void customerReadsShareRepeatableReadConnectionAndExternalCallsRunAfterCommit() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Transactions.class);
            Map<Class<?>, Object> dependencies = new HashMap<>();
            for (Class<?> type :
                    TaxCalculationServiceImpl.class.getConstructors()[0].getParameterTypes()) {
                if (type == TaxExpectedSnapshotReader.class) continue;
                Object bean = type == TaxCalculator.class ? new TaxCalculator() : mock(type);
                dependencies.put(type, bean);
                context.registerBean((Class) type, () -> bean);
            }
            context.registerBean(TaxExpectedSnapshotReader.class);
            context.registerBean(TaxCalculationServiceImpl.class);
            context.refresh();
            var accounts = (AccountMapper) dependencies.get(AccountMapper.class);
            var taxes = (TaxMapper) dependencies.get(TaxMapper.class);
            var clock = (BusinessClockService) dependencies.get(BusinessClockService.class);
            var properties = (RiaTaxProperties) dependencies.get(RiaTaxProperties.class);
            var settlement =
                    (SettlementBusinessDayCalculator)
                            dependencies.get(SettlementBusinessDayCalculator.class);
            var prices = (KisPriceClient) dependencies.get(KisPriceClient.class);
            var fx = (ExchangeRateClient) dependencies.get(ExchangeRateClient.class);
            var now = LocalDateTime.of(2026, 8, 4, 9, 0);
            var connection = new AtomicReference<Object>();
            Runnable assertSnapshot =
                    () -> {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                                .isTrue();
                        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                                .isTrue();
                        assertThat(
                                        TransactionSynchronizationManager
                                                .getCurrentTransactionIsolationLevel())
                                .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                        Object holder =
                                TransactionSynchronizationManager.getResource(
                                        context.getBean(DataSource.class));
                        assertThat(holder).isNotNull();
                        connection.compareAndSet(null, holder);
                        assertThat(holder).isSameAs(connection.get());
                    };
            when(accounts.selectByCustomerId(7L))
                    .thenAnswer(
                            i -> {
                                assertSnapshot.run();
                                return Optional.of(
                                        AccountDTO.builder()
                                                .accountId(1L)
                                                .benefit(BenefitType.POSSIBLE)
                                                .build());
                            });
            when(clock.now()).thenReturn(now);
            when(properties.getYear()).thenReturn(2026);
            when(taxes.selectHeldLotsByAccountId(1L))
                    .thenAnswer(
                            i -> {
                                assertSnapshot.run();
                                return List.of(
                                        heldLot("AAPL", "NASDAQ", "USD", "100", "100", "100"));
                            });
            when(taxes.selectFinalizedLotsByAccountIdsAndYear(List.of(1L), 2026, now))
                    .thenAnswer(
                            i -> {
                                assertSnapshot.run();
                                return List.of(
                                        lot(
                                                LocalDate.of(2026, 8, 1),
                                                "5000000",
                                                "100",
                                                "100",
                                                "100"));
                            });
            when(taxes.selectTaxRules())
                    .thenAnswer(
                            i -> {
                                assertSnapshot.run();
                                return allSeedRules();
                            });
            when(taxes.selectExternalBuysByAccountIdsAndYear(List.of(1L), 2026, now))
                    .thenAnswer(
                            i -> {
                                assertSnapshot.run();
                                return List.of();
                            });
            when(settlement.calculateFinalAt(now)).thenReturn(now.plusDays(2));
            when(prices.getPreviousClose("NAS", "AAPL"))
                    .thenAnswer(
                            i -> {
                                assertThat(
                                                TransactionSynchronizationManager
                                                        .isActualTransactionActive())
                                        .isFalse();
                                return new BigDecimal("150");
                            });
            when(fx.getBaseRate("USD"))
                    .thenAnswer(
                            i -> {
                                assertThat(
                                                TransactionSynchronizationManager
                                                        .isActualTransactionActive())
                                        .isFalse();
                                return new BigDecimal("200");
                            });
            var response =
                    context.getBean(TaxCalculationService.class).getCustomerExpectedTaxBriefing(7L);
            assertThat(response.getGeneralTax()).isEqualByComparingTo("770000");
            assertThat(response.getRiaTax()).isEqualByComparingTo("110000");
            verify(taxes).selectFinalizedLotsByAccountIdsAndYear(List.of(1L), 2026, now);
            verify(taxes).selectExternalBuysByAccountIdsAndYear(List.of(1L), 2026, now);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        }
    }
}
