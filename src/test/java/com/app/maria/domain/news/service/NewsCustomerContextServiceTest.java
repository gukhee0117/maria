package com.app.maria.domain.news.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.domestic.dto.DomesticHoldingDTO;
import com.app.maria.domain.domestic.mapper.DomesticStockBalanceMapper;
import com.app.maria.domain.domestic.type.*;
import com.app.maria.domain.investorassessment.dto.response.InvestorAssessmentStatusResponseDTO;
import com.app.maria.domain.investorassessment.service.InvestorAssessmentService;
import com.app.maria.domain.news.dto.NewsContract.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class NewsCustomerContextServiceTest {
    AccountMapper accounts = mock(AccountMapper.class);
    DomesticStockBalanceMapper holdings = mock(DomesticStockBalanceMapper.class);
    InvestorAssessmentService assessment = mock(InvestorAssessmentService.class);
    com.app.maria.domain.news.mapper.NewsInventoryMapper news =
            mock(com.app.maria.domain.news.mapper.NewsInventoryMapper.class);
    NewsCustomerContextService service =
            new NewsCustomerContextService(accounts, holdings, assessment, news);

    DomesticHoldingDTO holding(String ticker, Type type, String qty, DomesticStockStatus state) {
        return DomesticHoldingDTO.builder()
                .ticker(ticker)
                .sectorName("반도체")
                .type(type)
                .qty(new BigDecimal(qty))
                .status(state)
                .build();
    }

    @Test
    void onlyOwnedPositiveDomesticStocksIncludingRestrictedOnesAreUsed() {
        when(accounts.selectByCustomerId(7L))
                .thenReturn(Optional.of(AccountDTO.builder().accountId(80L).build()));
        when(holdings.selectHoldingsByAccountId(80L))
                .thenReturn(
                        List.of(
                                holding(
                                        "005930",
                                        Type.STOCK,
                                        "1",
                                        DomesticStockStatus.TRADE_RESTRICTED),
                                holding(
                                        "000660",
                                        Type.STOCK,
                                        "2",
                                        DomesticStockStatus.TRADE_SUSPENDED),
                                holding("035420", Type.FUND, "1", DomesticStockStatus.HOLDING),
                                holding("035720", Type.STOCK, "0", DomesticStockStatus.HOLDING),
                                holding("005380", Type.STOCK, "1", DomesticStockStatus.SOLD_OUT)));
        when(assessment.getMine(7L))
                .thenReturn(InvestorAssessmentStatusResponseDTO.withoutRecord(InvestorType.ACTIVE));
        var context = service.load(7L);
        assertThat(context.tickers()).containsExactlyInAnyOrder("005930", "000660");
        assertThat(context.stockSectors()).containsOnlyKeys("005930", "000660");
        assertThat(context.stockSectors().get("005930")).isEqualTo("반도체");
        verify(accounts).selectByCustomerId(7L);
        verify(holdings).selectHoldingsByAccountId(80L);
        verifyNoMoreInteractions(accounts, holdings);
    }

    @Test
    void expiredOrUnassignedUsesNeutralWithoutGuessingExpiry() {
        when(accounts.selectByCustomerId(1L)).thenReturn(Optional.empty());
        when(assessment.getMine(1L)).thenReturn(InvestorAssessmentStatusResponseDTO.unassigned());
        assertThat(service.load(1L).personalizationStatus())
                .isEqualTo(PersonalizationStatus.UNASSIGNED);
        when(assessment.getMine(1L))
                .thenReturn(
                        InvestorAssessmentStatusResponseDTO.of(
                                InvestorType.ACTIVE, null, null, true));
        assertThat(service.load(1L).investorType()).isEqualTo(InvestorType.NEUTRAL);
        assertThat(service.load(1L).personalizationStatus())
                .isEqualTo(PersonalizationStatus.EXPIRED);
        verifyNoInteractions(holdings);
    }

    @Test
    void themeAndArticleLoadsAvoidWindowQueryButSelectionLoadsConsistentRevision() {
        when(accounts.selectByCustomerId(1L)).thenReturn(Optional.empty());
        when(assessment.getMine(1L)).thenReturn(InvestorAssessmentStatusResponseDTO.unassigned());
        service.loadHoldings(1L);
        verifyNoInteractions(news);
        service.load(1L);
        verify(news).selectLatestOrderId(1L);
        verify(news, never()).selectHoldingThemePriorities(anyLong());
        clearInvocations(news);
        when(news.selectLatestOrderId(1L)).thenReturn(99L);
        when(news.selectHoldingThemePriorities(1L))
                .thenReturn(
                        List.of(new com.app.maria.domain.news.dto.NewsThemePriority("chip", 3L)));
        var full = service.loadForSelection(1L);
        assertThat(full.themePriorities()).containsEntry("chip", 3L);
        assertThat(full.orderRevision()).isEqualTo(99L);
        verify(news).selectHoldingThemePriorities(1L);
    }

    @Test
    void inactiveBalanceStatusChangeInvalidatesScopeWithoutNewOrdersOrHeldTickerChanges() {
        when(accounts.selectByCustomerId(7L))
                .thenReturn(Optional.of(AccountDTO.builder().accountId(80L).build()));
        when(assessment.getMine(7L)).thenReturn(InvestorAssessmentStatusResponseDTO.unassigned());
        var active = holding("005930", Type.STOCK, "1", DomesticStockStatus.HOLDING);
        var old = holding("000660", Type.STOCK, "0", DomesticStockStatus.SOLD_OUT);
        when(holdings.selectHoldingsByAccountId(80L)).thenReturn(List.of(active, old));
        var before = service.load(7L);
        old.setStatus(DomesticStockStatus.TERMINATED);
        var after = service.load(7L);
        assertThat(after.tickers()).isEqualTo(before.tickers());
        assertThat(after.orderRevision()).isEqualTo(before.orderRevision());
        assertThat(NewsCacheIdentity.portfolio(after))
                .isNotEqualTo(NewsCacheIdentity.portfolio(before));
        verify(news, never()).selectHoldingThemePriorities(anyLong());
    }
}
