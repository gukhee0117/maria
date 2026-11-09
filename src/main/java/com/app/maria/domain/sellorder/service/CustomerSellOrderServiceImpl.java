package com.app.maria.domain.sellorder.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.sellorder.dto.request.CustomerSellOrderRequestDTO;
import com.app.maria.domain.sellorder.dto.request.SellOrderRequestDTO;
import com.app.maria.domain.sellorder.dto.response.CustomerSellOrderResponseDTO;
import com.app.maria.domain.sellorder.dto.response.CustomerSellOrderResultResponseDTO;
import com.app.maria.domain.sellorder.dto.response.SellOrderResponseDTO;
import com.app.maria.domain.sellorder.mapper.CustomerSellOrderMapper;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.response.PageParam;
import com.app.maria.global.response.PageResponseDTO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerSellOrderServiceImpl implements CustomerSellOrderService {

    private final AccountMapper accountMapper;
    private final CustomerSellOrderMapper customerSellOrderMapper;
    private final SellOrderService sellOrderService;

    /**
     * 트랜잭션은 SellOrderService.placeSellOrder가 연다(READ_COMMITTED, 계좌 행 락). 여기서는 계좌만 확인하고 기존 매도 로직(상태
     * 검사·한도·FIFO·가정산)을 그대로 쓴다. 감사 로그는 고객 행위자가 정해질 때까지 남기지 않는다(adminId null).
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CustomerSellOrderResultResponseDTO placeOrder(
            Long customerId, CustomerSellOrderRequestDTO request) {
        AccountDTO account =
                accountMapper
                        .selectByCustomerId(customerId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, customerId));
        List<SellOrderResponseDTO> placed =
                sellOrderService.placeSellOrder(
                        null,
                        SellOrderRequestDTO.builder()
                                .accountId(account.getAccountId())
                                .foreignProductId(request.getForeignProductId())
                                .sellQty(request.getSellQty())
                                .build());
        return CustomerSellOrderResultResponseDTO.of(placed);
    }

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
