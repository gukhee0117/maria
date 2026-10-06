package com.app.maria.domain.sellorder.service;

import com.app.maria.domain.sellorder.mapper.SellLimitMapper;
import com.app.maria.global.client.mydata.MydataClient;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class SellLimitServiceImpl implements SellLimitService {

    private static final BigDecimal MAX_TOTAL_SELL_LIMIT = BigDecimal.valueOf(50_000_000);

    private final SellLimitMapper sellLimitMapper;
    private final MydataClient mydataClient;

    @Override
    public boolean isWithinSellLimit(Long accountId, BigDecimal orderAmount) {
        BigDecimal limitAmount =
                sellLimitMapper
                        .selectAccountLimitForUpdate(accountId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorType.SELL_ORDER_ACCOUNT_NOT_FOUND, accountId));

        BigDecimal usedAmount = sellLimitMapper.sumUsedAmount(accountId);

        String ciHash =
                sellLimitMapper
                        .selectCiHashByAccountId(accountId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorType.SELL_ORDER_CUSTOMER_NOT_FOUND,
                                                accountId));

        BigDecimal externalSum = mydataClient.getExternalSellTotal(ciHash);

        BigDecimal localTotal = usedAmount.add(orderAmount);
        BigDecimal globalTotal = localTotal.add(externalSum);

        boolean withinAccountLimit = localTotal.compareTo(limitAmount) <= 0;
        boolean withinGlobalCap = globalTotal.compareTo(MAX_TOTAL_SELL_LIMIT) <= 0;

        return withinAccountLimit && withinGlobalCap;
    }
}
