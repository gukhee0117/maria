package com.app.maria.domain.fundflow.dto.response;

import com.app.maria.domain.fundflow.dto.FundFlowTransactionDTO;
import com.app.maria.domain.fundflow.dto.FundFlowTransactionRowDTO;
import com.app.maria.domain.fundflow.dto.FundFlowTransactionsDTO;
import com.app.maria.domain.fundflow.type.FundFlowTransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
public class FundFlowTransactionsResponseDTO {

    private static final String BASIS =
            "amount는 예수금 변동액(국내 매수는 수수료 포함, 국내 매도는 수수료·거래세 제외). 같은 날은 원화 확정, 국내 매도, 국내 매수, 인출 순서. 오래된 순";

    private LocalDate asOfDate;
    private boolean hasAccount;
    private List<Item> items;
    private String basis;

    public FundFlowTransactionsResponseDTO(FundFlowTransactionsDTO vo) {
        this.asOfDate = vo.getAsOfDate();
        this.hasAccount = vo.isHasAccount();
        this.items = vo.getItems().stream().map(Item::new).toList();
        this.basis = BASIS;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    @ToString
    public static class Item {
        private LocalDate date;
        private FundFlowTransactionType type;
        private BigDecimal amount;
        private BigDecimal cashAfter;
        private String title;
        private List<Detail> details;

        public Item(FundFlowTransactionDTO vo) {
            this.date = vo.getDate();
            this.type = vo.getType();
            this.amount = vo.getAmount();
            this.cashAfter = vo.getCashAfter();
            this.title = vo.getTitle();
            this.details = vo.getDetails().stream().map(Detail::new).toList();
        }
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    @ToString
    public static class Detail {
        private String name;
        private BigDecimal qty;
        private BigDecimal amount;
        private BigDecimal feeAmount;
        private BigDecimal taxAmount;

        public Detail(FundFlowTransactionRowDTO vo) {
            this.name = vo.getProductName();
            this.qty = vo.getQty();
            this.amount = vo.getAmount();
            this.feeAmount = vo.getFeeAmount();
            this.taxAmount = vo.getTaxAmount();
        }
    }
}
