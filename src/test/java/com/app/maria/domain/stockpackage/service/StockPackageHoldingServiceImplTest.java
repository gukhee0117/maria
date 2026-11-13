package com.app.maria.domain.stockpackage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.domestic.type.DomesticOrderSide;
import com.app.maria.domain.stockpackage.dto.response.StockPackageAllHoldingsResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockPackageHoldingResponseDTO;
import com.app.maria.domain.stockpackage.mapper.StockOrderMapper;
import com.app.maria.domain.stockpackage.mapper.StockPackageMapper;
import com.app.maria.domain.stockpackage.vo.StockOrderVO;
import com.app.maria.domain.stockpackage.vo.StockPackageItemVO;
import com.app.maria.domain.stockpackage.vo.StockPackageVO;
import com.app.maria.domain.stockpackage.vo.StockQuoteVO;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StockPackageHoldingServiceImplTest {
    private static final long CUSTOMER = 7L;
    private static final long PACKAGE = 5L;
    private static final long ACCOUNT = 70L;

    @Mock StockPackageMapper packageMapper;
    @Mock StockOrderMapper orderMapper;
    @Mock AccountMapper accountMapper;
    @Mock StockQuoteService quoteService;
    @InjectMocks StockPackageHoldingServiceImpl service;

    private static StockOrderVO buy(long product, String ticker, String qty, String price) {
        return StockOrderVO.builder()
                .domesticProductId(product)
                .ticker(ticker)
                .name("종목" + product)
                .side(DomesticOrderSide.BUY)
                .qty(new BigDecimal(qty))
                .price(new BigDecimal(price))
                .build();
    }

    private static StockQuoteVO quote(String ticker, String price) {
        return new StockQuoteVO(
                ticker,
                true,
                new BigDecimal(price),
                BigDecimal.ONE,
                BigDecimal.TEN,
                BigDecimal.ONE);
    }

    private void owned() {
        when(packageMapper.selectByIdAndCustomerId(PACKAGE, CUSTOMER))
                .thenReturn(Optional.of(StockPackageVO.builder().stockPackageId(PACKAGE).build()));
    }

    private void account(String amount, String provisional) {
        when(accountMapper.selectByCustomerId(CUSTOMER))
                .thenReturn(
                        Optional.of(
                                AccountDTO.builder()
                                        .accountId(ACCOUNT)
                                        .amount(new BigDecimal(amount))
                                        .build()));
        when(orderMapper.sumProvisionalAmount(ACCOUNT)).thenReturn(new BigDecimal(provisional));
    }

    @Test
    void 평가금액과_손익을_현재가로_계산하고_주문가능금액은_가정산을_뺀다() {
        owned();
        account("5000000", "1000000");
        when(orderMapper.selectExecutedByPackageAsc(PACKAGE))
                .thenReturn(
                        List.of(buy(1, "005930", "10", "100000"), buy(2, "000660", "2", "400000")));
        when(quoteService.findQuotes(List.of("005930", "000660")))
                .thenReturn(List.of(quote("005930", "110000"), quote("000660", "380000")));

        StockPackageHoldingResponseDTO r = service.getHoldings(CUSTOMER, PACKAGE);

        assertThat(r.items()).hasSize(2);
        StockPackageHoldingResponseDTO.Item a = r.items().get(0);
        assertThat(a.cost()).isEqualByComparingTo("1000000");
        assertThat(a.evalAmount()).isEqualByComparingTo("1100000");
        assertThat(a.profitAmount()).isEqualByComparingTo("100000");
        assertThat(a.profitRatePercent()).isEqualByComparingTo("10.00");
        assertThat(r.items().get(1).profitAmount()).isEqualByComparingTo("-40000");
        assertThat(r.totalCost()).isEqualByComparingTo("1800000");
        assertThat(r.totalEval()).isEqualByComparingTo("1860000");
        assertThat(r.totalProfit()).isEqualByComparingTo("60000");
        assertThat(r.accountAmount()).isEqualByComparingTo("5000000");
        assertThat(r.buyingPower()).isEqualByComparingTo("4000000");
    }

    @Test
    void 현재가를_못받은_종목이_있으면_그_종목과_합계_평가는_null이다() {
        owned();
        account("0", "0");
        when(orderMapper.selectExecutedByPackageAsc(PACKAGE))
                .thenReturn(
                        List.of(buy(1, "005930", "10", "100000"), buy(2, "000660", "2", "400000")));
        when(quoteService.findQuotes(List.of("005930", "000660")))
                .thenReturn(List.of(quote("005930", "110000"), StockQuoteVO.unavailable("000660")));

        StockPackageHoldingResponseDTO r = service.getHoldings(CUSTOMER, PACKAGE);

        assertThat(r.items().get(0).evalAmount()).isNotNull();
        assertThat(r.items().get(1).price()).isNull();
        assertThat(r.items().get(1).evalAmount()).isNull();
        assertThat(r.items().get(1).cost()).isEqualByComparingTo("800000");
        assertThat(r.totalCost()).isEqualByComparingTo("1800000");
        assertThat(r.totalEval()).isNull();
        assertThat(r.totalProfit()).isNull();
    }

    @Test
    void 보유가_없으면_현재가를_조회하지_않는다() {
        owned();
        account("100", "0");
        when(orderMapper.selectExecutedByPackageAsc(PACKAGE)).thenReturn(List.of());

        StockPackageHoldingResponseDTO r = service.getHoldings(CUSTOMER, PACKAGE);

        assertThat(r.items()).isEmpty();
        assertThat(r.totalEval()).isEqualByComparingTo("0");
        verify(quoteService, never()).findQuotes(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void 남의_패키지는_조회할_수_없다() {
        when(packageMapper.selectByIdAndCustomerId(PACKAGE, CUSTOMER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getHoldings(CUSTOMER, PACKAGE))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.STOCK_PACKAGE_NOT_FOUND));
        assertThatThrownBy(() -> service.getOrders(CUSTOMER, PACKAGE))
                .isInstanceOf(AppException.class);
        verify(orderMapper, never())
                .selectOrdersByCustomer(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void 전체_주문_내역은_패키지_소유_확인_없이_고객_기준으로_조회한다() {
        when(orderMapper.selectOrdersByCustomer(CUSTOMER, null, 200)).thenReturn(List.of());

        assertThat(service.getOrders(CUSTOMER, null)).isEmpty();
        verify(packageMapper, never())
                .selectByIdAndCustomerId(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong());
    }

    private static StockPackageItemVO pkgItem(long product, String ticker) {
        return StockPackageItemVO.builder()
                .domesticProductId(product)
                .ticker(ticker)
                .name("종목" + product)
                .build();
    }

    private static StockQuoteVO quote(String ticker, String price, String prev) {
        return new StockQuoteVO(
                ticker,
                true,
                new BigDecimal(price),
                new BigDecimal(prev),
                BigDecimal.TEN,
                BigDecimal.ONE);
    }

    @Test
    void 담았지만_안_산_종목은_수량_0과_현재가로_나오고_합계_평가를_null로_만들지_않는다() {
        owned();
        account("0", "0");
        when(packageMapper.selectItemsByPackageId(PACKAGE))
                .thenReturn(List.of(pkgItem(1, "005930"), pkgItem(2, "000660")));
        when(orderMapper.selectExecutedByPackageAsc(PACKAGE))
                .thenReturn(List.of(buy(1, "005930", "10", "100000")));
        when(quoteService.findQuotes(List.of("005930", "000660")))
                .thenReturn(
                        List.of(
                                quote("005930", "110000", "100000"),
                                StockQuoteVO.unavailable("000660")));

        StockPackageHoldingResponseDTO r = service.getHoldings(CUSTOMER, PACKAGE);

        assertThat(r.items())
                .extracting(StockPackageHoldingResponseDTO.Item::ticker)
                .containsExactly("005930", "000660");
        assertThat(r.items().get(0).changePercent()).isEqualByComparingTo("10.00");
        assertThat(r.items().get(1).qty()).isEqualByComparingTo("0");
        assertThat(r.items().get(1).price()).isNull();
        assertThat(r.totalEval()).isEqualByComparingTo("1100000"); // 안 산 종목의 시세 부재는 합계에 영향 없음
    }

    @Test
    void 모든_패키지_보유는_현재가를_종목_합집합으로_한번에_조회한다() {
        when(packageMapper.selectSummariesByCustomerId(CUSTOMER))
                .thenReturn(
                        List.of(
                                StockPackageVO.builder().stockPackageId(5L).build(),
                                StockPackageVO.builder().stockPackageId(6L).build()));
        when(packageMapper.selectItemsByPackageId(5L)).thenReturn(List.of(pkgItem(1, "005930")));
        when(packageMapper.selectItemsByPackageId(6L))
                .thenReturn(List.of(pkgItem(1, "005930"), pkgItem(2, "000660")));
        StockOrderVO a = buy(1, "005930", "10", "100000");
        a.setStockPackageId(5L);
        StockOrderVO b = buy(2, "000660", "2", "400000");
        b.setStockPackageId(6L);
        when(orderMapper.selectExecutedByCustomerAsc(CUSTOMER)).thenReturn(List.of(a, b));
        account("1000", "0");
        when(quoteService.findQuotes(List.of("005930", "000660")))
                .thenReturn(List.of(quote("005930", "110000"), quote("000660", "380000")));

        StockPackageAllHoldingsResponseDTO result = service.getAllHoldings(CUSTOMER);
        List<StockPackageHoldingResponseDTO> all = result.packages();

        assertThat(result.buyingPower()).isEqualByComparingTo("1000");
        assertThat(all)
                .extracting(StockPackageHoldingResponseDTO::stockPackageId)
                .containsExactly(5L, 6L);
        assertThat(all.get(0).items().get(0).qty()).isEqualByComparingTo("10");
        assertThat(all.get(1).items().get(0).qty())
                .isEqualByComparingTo("0"); // 5번에서 산 것은 6번 몫이 아니다
        assertThat(all.get(1).items().get(1).qty()).isEqualByComparingTo("2");
        verify(quoteService, org.mockito.Mockito.times(1))
                .findQuotes(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void 패키지가_없어도_예수금과_주문_가능_금액은_오고_현재가를_조회하지_않는다() {
        when(packageMapper.selectSummariesByCustomerId(CUSTOMER)).thenReturn(List.of());
        account("15000000", "1000000");

        StockPackageAllHoldingsResponseDTO result = service.getAllHoldings(CUSTOMER);

        assertThat(result.packages()).isEmpty();
        assertThat(result.accountAmount()).isEqualByComparingTo("15000000");
        assertThat(result.buyingPower()).isEqualByComparingTo("14000000");
        verify(quoteService, never()).findQuotes(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void 계좌가_없으면_예수금과_주문_가능_금액이_null이다() {
        when(packageMapper.selectSummariesByCustomerId(CUSTOMER)).thenReturn(List.of());
        when(accountMapper.selectByCustomerId(CUSTOMER)).thenReturn(Optional.empty());

        StockPackageAllHoldingsResponseDTO result = service.getAllHoldings(CUSTOMER);

        assertThat(result.packages()).isEmpty();
        assertThat(result.accountAmount()).isNull();
        assertThat(result.buyingPower()).isNull();
    }
}
