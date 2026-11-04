package com.app.maria.domain.settlement.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.settlement.dto.response.CustomerSettlementResponseDTO;
import com.app.maria.domain.settlement.mapper.CustomerSettlementMapper;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.response.PageParam;
import com.app.maria.global.response.PageResponseDTO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerSettlementServiceImpl implements CustomerSettlementService {

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
        PageParam paging = PageParam.of(page, size);

        List<CustomerSettlementResponseDTO> content =
                customerSettlementMapper.selectByAccountId(
                        account.getAccountId(), paging.offset(), paging.size());
        int total = customerSettlementMapper.countByAccountId(account.getAccountId());
        return PageResponseDTO.of(content, total, paging.page(), paging.size());
    }
}
