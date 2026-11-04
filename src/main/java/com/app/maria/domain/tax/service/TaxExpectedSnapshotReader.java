package com.app.maria.domain.tax.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.tax.dto.ExternalBuyDTO;
import com.app.maria.domain.tax.dto.HeldLotDTO;
import com.app.maria.domain.tax.dto.SellLotDTO;
import com.app.maria.domain.tax.dto.TaxRuleDTO;
import com.app.maria.domain.tax.mapper.TaxMapper;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.properties.RiaTaxProperties;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 고객 합산 예상 계산에 필요한 DB 자료만 동일 스냅샷으로 읽는다. 외부 시세·환율은 호출하지 않는다. */
@Component
@RequiredArgsConstructor
public class TaxExpectedSnapshotReader {
    private final AccountMapper accountMapper;
    private final TaxMapper taxMapper;
    private final BusinessClockService clock;
    private final RiaTaxProperties properties;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot readCustomer(Long customerId) {
        AccountDTO account =
                accountMapper
                        .selectByCustomerId(customerId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, customerId));
        LocalDateTime asOf = clock.now();
        List<Long> accounts = List.of(account.getAccountId());
        int year = properties.getYear();
        var held = taxMapper.selectHeldLotsByAccountId(account.getAccountId());
        var finalized = taxMapper.selectFinalizedLotsByAccountIdsAndYear(accounts, year, asOf);
        var rules = taxMapper.selectTaxRules();
        var external = taxMapper.selectExternalBuysByAccountIdsAndYear(accounts, year, asOf);
        return new Snapshot(
                account,
                asOf,
                List.copyOf(held),
                List.copyOf(finalized),
                List.copyOf(rules),
                List.copyOf(external));
    }

    public record Snapshot(
            AccountDTO account,
            LocalDateTime asOf,
            List<HeldLotDTO> heldLots,
            List<SellLotDTO> finalizedLots,
            List<TaxRuleDTO> rules,
            List<ExternalBuyDTO> externalTrades) {}
}
