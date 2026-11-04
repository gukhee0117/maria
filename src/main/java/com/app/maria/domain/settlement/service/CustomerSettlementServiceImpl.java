package com.app.maria.domain.settlement.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.settlement.dto.response.CustomerSettlementResponseDTO;
import com.app.maria.domain.settlement.mapper.CustomerSettlementMapper;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.response.PageResponseDTO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerSettlementServiceImpl implements CustomerSettlementService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AccountMapper accountMapper;
    private final CustomerSettlementMapper customerSettlementMapper;

    @Override
    public PageResponseDTO<CustomerSettlementResponseDTO> getSettlements(
            Long customerId, int page, int size) {
        AccountDTO account =
                accountMapper
                        .selectByCustomerId(customerId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, customerId));
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        List<CustomerSettlementResponseDTO> content =
                customerSettlementMapper.selectByAccountId(
                        account.getAccountId(), (long) safePage * safeSize, safeSize);
        int total = customerSettlementMapper.countByAccountId(account.getAccountId());
        return PageResponseDTO.of(content, total, safePage, safeSize);
    }
}
