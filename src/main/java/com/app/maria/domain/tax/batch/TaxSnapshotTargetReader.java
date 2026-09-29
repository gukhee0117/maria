package com.app.maria.domain.tax.batch;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.tax.dto.ExternalBuyDTO;
import com.app.maria.domain.tax.dto.SellLotDTO;
import com.app.maria.domain.tax.dto.TaxSnapshotTargetDTO;
import com.app.maria.domain.tax.mapper.TaxMapper;
import com.app.maria.global.config.properties.RiaTaxProperties;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@StepScope
public class TaxSnapshotTargetReader implements ItemStreamReader<TaxSnapshotTargetDTO> {
    static final String LAST_ACCOUNT_ID = "tax.snapshot.lastAccountId";
    static final long INITIAL_ACCOUNT_ID = 0L;
    static final int PAGE_SIZE = 200;

    private final AccountMapper accountMapper;
    private final TaxMapper taxMapper;
    private final RiaTaxProperties riaTaxProperties;

    @Value("#{jobParameters['calculatedAt']}")
    private LocalDateTime calculatedAt;

    private final Deque<TaxSnapshotTargetDTO> buffer = new ArrayDeque<>();
    private long lastAccountId = INITIAL_ACCOUNT_ID;

    @Override
    public void open(ExecutionContext executionContext) {
        lastAccountId = executionContext.getLong(LAST_ACCOUNT_ID, INITIAL_ACCOUNT_ID);
        buffer.clear();
    }

    @Override
    public TaxSnapshotTargetDTO read() {
        if (buffer.isEmpty()) {
            fillBuffer();
        }
        TaxSnapshotTargetDTO target = buffer.poll();
        if (target == null) {
            return null;
        }

        lastAccountId = target.getAccount().getAccountId();
        return target;
    }

    private void fillBuffer() {
        List<AccountDTO> accounts =
                accountMapper.selectOpenedAccountsAfter(lastAccountId, PAGE_SIZE);
        if (accounts.isEmpty()) {
            return;
        }

        List<Long> accountIds = accounts.stream().map(AccountDTO::getAccountId).toList();
        int taxYear = riaTaxProperties.getYear();

        Map<Long, List<SellLotDTO>> sellLotsByAccount =
                taxMapper
                        .selectFinalizedLotsByAccountIdsAndYear(accountIds, taxYear, calculatedAt)
                        .stream()
                        .collect(Collectors.groupingBy(SellLotDTO::getAccountId));

        Map<Long, List<ExternalBuyDTO>> externalTradesByAccount =
                taxMapper
                        .selectExternalBuysByAccountIdsAndYear(accountIds, taxYear, calculatedAt)
                        .stream()
                        .collect(Collectors.groupingBy(ExternalBuyDTO::getAccountId));

        for (AccountDTO account : accounts) {
            Long accountId = account.getAccountId();
            buffer.add(
                    TaxSnapshotTargetDTO.of(
                            account,
                            sellLotsByAccount.getOrDefault(accountId, List.of()),
                            externalTradesByAccount.getOrDefault(accountId, List.of())));
        }
    }

    @Override
    public void update(ExecutionContext executionContext) {
        executionContext.putLong(LAST_ACCOUNT_ID, lastAccountId);
    }

    @Override
    public void close() {
        buffer.clear();
    }
}
