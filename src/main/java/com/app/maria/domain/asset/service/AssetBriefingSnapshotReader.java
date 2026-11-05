package com.app.maria.domain.asset.service;

import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.type.Status;
import com.app.maria.domain.asset.dto.AssetHoldingDTO;
import com.app.maria.domain.asset.dto.AssetMonthlyPurchaseDTO;
import com.app.maria.domain.asset.mapper.AssetBriefingMapper;
import com.app.maria.domain.tax.dto.HeldLotDTO;
import com.app.maria.domain.tax.mapper.TaxMapper;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** DB 조회 트랜잭션이 끝난 뒤 가격 provider를 호출하도록 저장 데이터만 모은다. */
@Component
@RequiredArgsConstructor
public class AssetBriefingSnapshotReader {
    private final AccountMapper accountMapper;
    private final AssetBriefingMapper mapper;
    private final BusinessClockService clock;
    private final TaxMapper taxMapper;

    @Transactional(readOnly = true)
    public Snapshot read(Long customerId) {
        var account =
                accountMapper
                        .selectByCustomerId(customerId)
                        .filter(value -> value.getStatus() == Status.OPENED)
                        .orElseThrow(() -> new AppException(ErrorType.ACCOUNT_NOT_FOUND));
        LocalDateTime now = clock.now();
        Long accountId = account.getAccountId();
        BigDecimal cost = mapper.sumHoldingCost(accountId);
        BigDecimal inflow = mapper.sumFinalizedInflow(accountId, now);
        var holdings = mapper.selectHoldings(accountId);
        var monthly = mapper.selectMonthlyPurchases(accountId, now);
        var foreignLots = taxMapper.selectHeldLotsByAccountId(accountId);
        return new Snapshot(
                accountId,
                account.getAmount(),
                cost,
                inflow,
                List.copyOf(holdings),
                List.copyOf(foreignLots),
                List.copyOf(monthly),
                now);
    }

    public record Snapshot(
            Long accountId,
            BigDecimal cash,
            BigDecimal cost,
            BigDecimal inflow,
            List<AssetHoldingDTO> holdings,
            List<HeldLotDTO> foreignLots,
            List<AssetMonthlyPurchaseDTO> monthly,
            LocalDateTime calculatedAt) {}
}
