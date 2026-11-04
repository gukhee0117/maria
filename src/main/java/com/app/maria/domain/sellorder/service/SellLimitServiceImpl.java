package com.app.maria.domain.sellorder.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.sellorder.dto.response.SellLimitStatusDTO;
import com.app.maria.domain.sellorder.mapper.SellLimitMapper;
import com.app.maria.global.client.mydata.MydataClient;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class SellLimitServiceImpl implements SellLimitService {

    private static final BigDecimal MAX_TOTAL_SELL_LIMIT = BigDecimal.valueOf(50_000_000);

    private final SellLimitMapper sellLimitMapper;
    private final MydataClient mydataClient;
    private final AccountMapper accountMapper;

    @Override
    public boolean isWithinSellLimit(Long accountId, BigDecimal orderAmount) {
        // ciHash 조회 → myData HTTP 호출(락 밖) → 행 락 취득 순서로 처리.
        // HTTP 호출을 락 안에서 하면 myData 응답 지연이 그대로 락 점유 시간이 되어
        // 동시 매도 요청이 몰릴 때 병목이 생긴다.
        String ciHash =
                sellLimitMapper
                        .selectCiHashByAccountId(accountId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorType.SELL_ORDER_CUSTOMER_NOT_FOUND,
                                                accountId));

        BigDecimal externalSum = mydataClient.getExternalSellTotal(ciHash);

        BigDecimal limitAmount =
                sellLimitMapper
                        .selectAccountLimitForUpdate(accountId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorType.SELL_ORDER_ACCOUNT_NOT_FOUND, accountId));

        BigDecimal usedAmount = sellLimitMapper.sumUsedAmount(accountId);

        BigDecimal localTotal = usedAmount.add(orderAmount);
        BigDecimal globalTotal = localTotal.add(externalSum);

        boolean withinAccountLimit = localTotal.compareTo(limitAmount) <= 0;
        boolean withinGlobalCap = globalTotal.compareTo(MAX_TOTAL_SELL_LIMIT) <= 0;

        return withinAccountLimit && withinGlobalCap;
    }

    // 조회 전용이고 락이 없다. myData HTTP 호출 동안 DB 커넥션을 쥐지 않도록 트랜잭션 밖에서 실행한다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Override
    public BigDecimal getRemainingLimit(Long accountId) {
        AccountDTO account =
                accountMapper
                        .selectByAccountId(accountId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, accountId));
        return statusOf(account).remainingLimit();
    }

    // 조회 전용이고 락이 없다. myData HTTP 호출 동안 DB 커넥션을 쥐지 않도록 트랜잭션 밖에서 실행한다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Override
    public SellLimitStatusDTO getStatusByCustomerId(Long customerId) {
        AccountDTO account =
                accountMapper
                        .selectByCustomerId(customerId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, customerId));
        if (!account.getStatus().canTrade()) {
            throw new AppException(ErrorType.ACCOUNT_STATE_NOT_ALLOWED, account.getStatus());
        }
        return statusOf(account);
    }

    private SellLimitStatusDTO statusOf(AccountDTO account) {
        Long accountId = account.getAccountId();
        String ciHash =
                sellLimitMapper
                        .selectCiHashByAccountId(accountId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorType.SELL_ORDER_CUSTOMER_NOT_FOUND,
                                                accountId));

        // 조회 실패 시 AppException 전파 — 한도 불명 상태에서 추천 반환하지 않음
        BigDecimal externalSum = mydataClient.getExternalSellTotal(ciHash);

        BigDecimal limitAmount = account.getLimitAmount();
        BigDecimal usedAmount = sellLimitMapper.sumUsedAmount(accountId);

        BigDecimal remaining =
                MAX_TOTAL_SELL_LIMIT
                        .subtract(usedAmount)
                        .subtract(externalSum)
                        .min(limitAmount.subtract(usedAmount))
                        .max(BigDecimal.ZERO);
        return new SellLimitStatusDTO(limitAmount, usedAmount, remaining);
    }
}
