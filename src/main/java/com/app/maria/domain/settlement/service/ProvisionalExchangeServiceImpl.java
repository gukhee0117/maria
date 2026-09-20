package com.app.maria.domain.settlement.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.service.AccountTransactionalService;
import com.app.maria.domain.sellorder.dto.SellOrderDTO;
import com.app.maria.domain.settlement.component.ProvisionalExchangeCalculator;
import com.app.maria.domain.settlement.component.SettlementBusinessDayCalculator;
import com.app.maria.domain.settlement.dto.KrwExchangeDTO;
import com.app.maria.domain.settlement.exception.ProvisionalException;
import com.app.maria.domain.settlement.mapper.KrwExchangeMapper;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProvisionalExchangeServiceImpl implements ProvisionalExchangeService {
    private final ProvisionalExchangeCalculator provisionalExchangeCalculator;
    private final SettlementBusinessDayCalculator settlementBusinessDayCalculator;
    private final KrwExchangeMapper krwExchangeMapper;
    private final BusinessClockService businessClockService;
    private final AccountTransactionalService accountTransactionalService;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void createProvisionalExchange(SellOrderDTO sellOrderDTO) {
        BigDecimal provisionalAmount =
                provisionalExchangeCalculator.calculate(
                        sellOrderDTO.getSellQty(), sellOrderDTO.getBasePrice());
        var provisionalAt = businessClockService.now();
        KrwExchangeDTO krwExchangeDTO =
                KrwExchangeDTO.builder()
                        .accountId(sellOrderDTO.getAccountId())
                        .orderId(sellOrderDTO.getOrderId())
                        .provisionalAmount(provisionalAmount)
                        .provisionalAt(provisionalAt)
                        .finalAt(settlementBusinessDayCalculator.calculateFinalAt(provisionalAt))
                        .build();
        if (krwExchangeMapper.insertProvisional(krwExchangeDTO) != 1) {
            throw new ProvisionalException("가환전 저장 실패");
        }
        AccountDTO newAmountAccount =
                AccountDTO.builder()
                        .accountId(sellOrderDTO.getAccountId())
                        .amount(provisionalAmount)
                        .build();
        accountTransactionalService.updateAmount(newAmountAccount);
    }
}
