package com.app.maria.domain.inbound.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.foreignproduct.dto.ForeignProductDTO;
import com.app.maria.domain.foreignproduct.mapper.ForeignProductMapper;
import com.app.maria.domain.inbound.dto.InboundDTO;
import com.app.maria.domain.inbound.dto.InboundDetailDTO;
import com.app.maria.domain.inbound.dto.InboundListDTO;
import com.app.maria.domain.inbound.dto.response.CustomerForeignProductResponseDTO;
import com.app.maria.domain.inbound.dto.response.CustomerInboundCandidateResponseDTO;
import com.app.maria.domain.inbound.dto.response.CustomerInboundCandidatesResponseDTO;
import com.app.maria.domain.inbound.dto.response.CustomerInboundDetailResponseDTO;
import com.app.maria.domain.inbound.dto.response.CustomerInboundHistoryResponseDTO;
import com.app.maria.domain.inbound.mapper.InboundMapper;
import com.app.maria.domain.registrablestock.dto.RegistrableStockResponseDTO;
import com.app.maria.global.client.registrablestock.RegistrableStockClient;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.response.PageResponseDTO;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerInboundQueryServiceImpl implements CustomerInboundQueryService {

    private final AccountMapper accountMapper;
    private final ForeignProductMapper foreignProductMapper;
    private final InboundMapper inboundMapper;
    private final RegistrableStockClient registrableStockClient;

    @Override
    public List<CustomerForeignProductResponseDTO> getForeignProducts(Long customerId) {
        String ciHash = getCiHash(customerId);
        List<RegistrableStockResponseDTO> lots = registrableStockClient.findCustomerLots(ciHash);

        Set<Long> productIds =
                lots.stream()
                        .map(RegistrableStockResponseDTO::getForeignProductId)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        if (productIds.isEmpty()) {
            return List.of();
        }
        return foreignProductMapper.selectByIdsOrderedByTickerAndId(productIds).stream()
                .map(CustomerForeignProductResponseDTO::new)
                .toList();
    }

    @Override
    public CustomerInboundCandidatesResponseDTO getCandidates(
            Long customerId, Long foreignProductId) {
        AccountDTO account = getAccount(customerId);
        String ciHash = getCiHash(customerId);

        List<RegistrableStockResponseDTO> lots =
                registrableStockClient.findCustomerLots(ciHash).stream()
                        .filter(lot -> foreignProductId.equals(lot.getForeignProductId()))
                        .toList();

        ForeignProductDTO product =
                foreignProductMapper
                        .selectByIdsOrderedByTickerAndId(List.of(foreignProductId))
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorType.CUSTOMER_REGISTRABLE_STOCK_NOT_FOUND,
                                                foreignProductId));

        Map<Long, BigDecimal> snapshotByAccount =
                lots.stream()
                        .collect(
                                Collectors.groupingBy(
                                        RegistrableStockResponseDTO::getGeneralAccountId,
                                        Collectors.reducing(
                                                BigDecimal.ZERO,
                                                RegistrableStockResponseDTO::getHeldQty,
                                                BigDecimal::add)));

        Map<Long, RegistrableStockResponseDTO> firstLotByAccount =
                lots.stream()
                        .collect(
                                Collectors.toMap(
                                        RegistrableStockResponseDTO::getGeneralAccountId,
                                        l -> l,
                                        (a, b) -> a));

        List<CustomerInboundCandidateResponseDTO> candidates =
                snapshotByAccount.entrySet().stream()
                        .map(
                                entry -> {
                                    Long sourceAccountId = entry.getKey();
                                    BigDecimal snapshotQty = entry.getValue();
                                    // 재입고를 지원하기 위해 net locked 기준으로 가용 수량을 계산한다.
                                    BigDecimal alreadyInbounded =
                                            inboundMapper.sumApprovedQtyByAccountAndProduct(
                                                    account.getAccountId(), foreignProductId);
                                    BigDecimal alreadyOutbounded =
                                            inboundMapper.sumOutboundQtyByAccountAndProduct(
                                                    account.getAccountId(), foreignProductId);
                                    BigDecimal available =
                                            snapshotQty
                                                    .subtract(
                                                            alreadyInbounded.subtract(
                                                                    alreadyOutbounded))
                                                    .max(BigDecimal.ZERO);
                                    RegistrableStockResponseDTO rep =
                                            firstLotByAccount.get(sourceAccountId);
                                    return CustomerInboundCandidateResponseDTO.builder()
                                            .sourceGeneralAccountId(sourceAccountId)
                                            .accountNo(rep.getAccountNo())
                                            .brokerName(rep.getSourceBroker())
                                            .snapshotQty(snapshotQty)
                                            .alreadyApprovedQty(
                                                    alreadyInbounded.subtract(alreadyOutbounded))
                                            .estimatedAvailableQty(available)
                                            .build();
                                })
                        .filter(c -> c.getEstimatedAvailableQty().compareTo(BigDecimal.ZERO) > 0)
                        .toList();

        return new CustomerInboundCandidatesResponseDTO(
                foreignProductId, product.getTicker(), product.getName(), candidates);
    }

    @Override
    public PageResponseDTO<CustomerInboundHistoryResponseDTO> getHistory(
            Long customerId, int page, int size) {
        AccountDTO account = getAccount(customerId);
        int offset = page * size;

        List<InboundListDTO> rows =
                inboundMapper.selectInboundsByAccountId(account.getAccountId(), offset, size);
        int total = inboundMapper.countInboundsByAccountId(account.getAccountId());

        List<CustomerInboundHistoryResponseDTO> content =
                rows.stream().map(CustomerInboundHistoryResponseDTO::new).toList();

        return PageResponseDTO.of(content, (long) total, page, size);
    }

    @Override
    public CustomerInboundDetailResponseDTO getDetail(Long customerId, Long inboundId) {
        AccountDTO account = getAccount(customerId);

        InboundDTO inbound =
                inboundMapper
                        .selectInboundByIdAndAccountId(inboundId, account.getAccountId())
                        .orElseThrow(
                                () -> new AppException(ErrorType.INBOUND_NOT_FOUND, inboundId));

        List<InboundDetailDTO> details = inboundMapper.selectDetailsByInboundId(inboundId);

        ForeignProductDTO product =
                details.isEmpty()
                        ? null
                        : foreignProductMapper
                                .selectById(details.get(0).getForeignProductId())
                                .orElse(null);

        return new CustomerInboundDetailResponseDTO(inbound, product, details);
    }

    private AccountDTO getAccount(Long customerId) {
        return accountMapper
                .selectByCustomerId(customerId)
                .orElseThrow(() -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, customerId));
    }

    private String getCiHash(Long customerId) {
        return accountMapper
                .selectCiHashByCustomerId(customerId)
                .orElseThrow(() -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, customerId));
    }
}
