package com.app.maria.domain.domestic.service;

import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.domestic.dto.DomesticProductDTO;
import com.app.maria.domain.domestic.dto.DomesticTradeHistoryDTO;
import com.app.maria.domain.domestic.dto.response.CustomerDomesticTradeResponseDTO;
import com.app.maria.domain.domestic.mapper.DomesticProductMapper;
import com.app.maria.global.client.domestictrade.DomesticTradeClient;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.response.PageParam;
import com.app.maria.global.response.PageResponseDTO;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 트랜잭션을 두지 않는다: 증권사 HTTP 호출 동안 DB 커넥션을 쥐지 않기 위해서다. 조회는 모두 읽기 전용 한 문장이다. */
@Service
@RequiredArgsConstructor
public class CustomerDomesticTradeServiceImpl implements CustomerDomesticTradeService {

    private final AccountMapper accountMapper;
    private final DomesticProductMapper domesticProductMapper;
    private final DomesticTradeClient domesticTradeClient;

    @Override
    public PageResponseDTO<CustomerDomesticTradeResponseDTO> getTrades(
            Long customerId, int page, int size) {
        // RIA 계좌가 있는 고객만 조회한다(이 확인이 유일한 가드이고 계좌 상태는 보지 않는다. 해지 계좌도 이력은 본다).
        accountMapper
                .selectByCustomerId(customerId)
                .orElseThrow(() -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, customerId));
        String ciHash =
                accountMapper
                        .selectCiHashByCustomerId(customerId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorType.ACCOUNT_CUSTOMER_IDENTITY_NOT_FOUND,
                                                customerId));

        // ponytail: 전체 내역을 받아 메모리에서 자른다. 계좌당 수천 건을 넘으면 증권사 API에 page·size를 추가한다.
        List<DomesticTradeHistoryDTO> all = domesticTradeClient.findByCiHash(ciHash);

        PageParam paging = PageParam.of(page, size);
        int from = (int) Math.min(paging.offset(), all.size());
        int to = Math.min(from + paging.size(), all.size());
        List<DomesticTradeHistoryDTO> slice = all.subList(from, to);

        Map<String, String> names = namesOf(slice);
        List<CustomerDomesticTradeResponseDTO> content =
                slice.stream()
                        .map(
                                t ->
                                        new CustomerDomesticTradeResponseDTO(
                                                t.getExecutedAt(),
                                                t.getStockCode(),
                                                names.get(t.getStockCode()),
                                                t.getTradeType(),
                                                t.getQty(),
                                                t.getPrice(),
                                                amountOf(t)))
                        .toList();
        return PageResponseDTO.of(content, all.size(), paging.page(), paging.size());
    }

    private static BigDecimal amountOf(DomesticTradeHistoryDTO t) {
        return t.getQty() == null || t.getPrice() == null
                ? null
                : t.getQty().multiply(t.getPrice());
    }

    private Map<String, String> namesOf(List<DomesticTradeHistoryDTO> trades) {
        Set<String> tickers =
                trades.stream()
                        .map(DomesticTradeHistoryDTO::getStockCode)
                        .collect(Collectors.toSet());
        if (tickers.isEmpty()) {
            return Map.of();
        }
        return domesticProductMapper.selectByTickers(tickers).stream()
                .collect(
                        Collectors.toMap(
                                DomesticProductDTO::getTicker,
                                DomesticProductDTO::getName,
                                (first, second) -> first));
    }
}
