package com.app.maria.domain.sellorder.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.sellorder.dto.response.CustomerSellOrderResponseDTO;
import com.app.maria.domain.sellorder.mapper.CustomerSellOrderMapper;
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
public class CustomerSellOrderServiceImpl implements CustomerSellOrderService {

    private final AccountMapper accountMapper;
    private final CustomerSellOrderMapper customerSellOrderMapper;

    @Override
    public PageResponseDTO<CustomerSellOrderResponseDTO> getSellOrders(
            Long customerId, int page, int size) {
        AccountDTO account =
                accountMapper
                        .selectByCustomerId(customerId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, customerId));
        PageParam paging = PageParam.of(page, size);

        List<CustomerSellOrderResponseDTO> content =
                customerSellOrderMapper.selectByAccountId(
                        account.getAccountId(), paging.offset(), paging.size());
        int total = customerSellOrderMapper.countByAccountId(account.getAccountId());
        return PageResponseDTO.of(content, total, paging.page(), paging.size());
    }
}
