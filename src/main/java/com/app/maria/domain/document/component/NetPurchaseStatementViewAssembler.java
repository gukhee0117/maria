package com.app.maria.domain.document.component;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.tax.dto.TaxBreakdownDTO;
import com.app.maria.domain.tax.dto.TaxCalculationDTO;
import com.app.maria.domain.tax.dto.TaxExternalTradeDetailDTO;
import com.app.maria.domain.tax.dto.TaxPeriodBreakdownDTO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 구간별 내역(breakdown)의 RIA 외 계좌 순매수를 PDF용 문자열로 바꾼다. 가중 합계는 저장된 확정 세액(calc)의 값을 그대로 쓴다. */
@Component
public class NetPurchaseStatementViewAssembler {

    public NetPurchaseStatementView assemble(
            int taxYear,
            String issueNo,
            LocalDateTime issuedAt,
            String holderName,
            AccountDTO account,
            TaxCalculationDTO calc,
            TaxBreakdownDTO breakdown) {
        List<NetPurchaseStatementView.Period> periods = new ArrayList<>();
        BigDecimal totalNetBuy = BigDecimal.ZERO;
        BigDecimal weightedNetBuy = BigDecimal.ZERO;
        for (TaxPeriodBreakdownDTO p : breakdown.getPeriodBreakdown()) {
            periods.add(
                    new NetPurchaseStatementView.Period(
                            DocumentFormats.label(p.getValidFrom(), p.getValidTo()),
                            DocumentFormats.won(p.getExternalNetBuyAmount()),
                            DocumentFormats.percent(p.getWeight()),
                            DocumentFormats.won(
                                    p.getExternalNetBuyAmount().multiply(p.getWeight()))));
            totalNetBuy = totalNetBuy.add(p.getExternalNetBuyAmount());
            weightedNetBuy =
                    weightedNetBuy.add(p.getExternalNetBuyAmount().multiply(p.getWeight()));
        }

        List<NetPurchaseStatementView.Trade> trades = new ArrayList<>();
        for (TaxExternalTradeDetailDTO t : breakdown.getExternalTradeDetails()) {
            trades.add(
                    new NetPurchaseStatementView.Trade(
                            t.getTradeDate().toString(),
                            t.getProductLabel(),
                            DocumentFormats.won(t.getNetBuyAmount())));
        }

        return NetPurchaseStatementView.builder()
                .taxYear(taxYear)
                .issueNo(issueNo)
                .issuedDate(issuedAt.toLocalDate().toString())
                .holderName(holderName)
                .accountNo(account.getAccountNo())
                .periods(periods)
                .totalNetBuy(DocumentFormats.won(totalNetBuy))
                .totalWeightedNetBuy(DocumentFormats.won(calc.getWeightedExternalAmount()))
                .clampedToZero(weightedNetBuy.signum() < 0)
                .trades(trades)
                .build();
    }
}
