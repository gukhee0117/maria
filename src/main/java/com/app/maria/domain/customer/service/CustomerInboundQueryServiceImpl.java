package com.app.maria.domain.customer.service;

import com.app.maria.domain.account.entity.AccountEntity;
import com.app.maria.domain.account.repository.AccountRepository;
import com.app.maria.domain.customer.dto.response.CustomerForeignProductResponseDTO;
import com.app.maria.domain.customer.dto.response.CustomerInboundCandidateResponseDTO;
import com.app.maria.domain.customer.dto.response.CustomerInboundCandidatesResponseDTO;
import com.app.maria.domain.customer.dto.response.CustomerInboundDetailResponseDTO;
import com.app.maria.domain.customer.dto.response.CustomerInboundHistoryResponseDTO;
import com.app.maria.domain.foreignproduct.entity.ForeignProductEntity;
import com.app.maria.domain.foreignproduct.repository.ForeignProductRepository;
import com.app.maria.domain.inbound.entity.InboundDetailEntity;
import com.app.maria.domain.inbound.entity.InboundEntity;
import com.app.maria.domain.inbound.repository.InboundDetailRepository;
import com.app.maria.domain.inbound.repository.InboundRepository;
import com.app.maria.domain.registrablestock.dto.RegistrableStockResponseDTO;
import com.app.maria.global.client.registrablestock.RegistrableStockClient;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import com.app.maria.global.response.PageResponseDTO;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(transactionManager = "jpaTransactionManager", readOnly = true)
public class CustomerInboundQueryServiceImpl implements CustomerInboundQueryService {

    private final AccountRepository accountRepository;
    private final ForeignProductRepository foreignProductRepository;
    private final InboundRepository inboundRepository;
    private final InboundDetailRepository inboundDetailRepository;
    private final RegistrableStockClient registrableStockClient;

    @Override
    public List<CustomerForeignProductResponseDTO> getForeignProducts(Long customerId) {
        String ciHash = getAccount(customerId).getCustomer().getCiHash();
        List<RegistrableStockResponseDTO> lots = registrableStockClient.findCustomerLots(ciHash);

        Set<Long> productIds = lots.stream()
                .map(RegistrableStockResponseDTO::getForeignProductId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (productIds.isEmpty()) {
            return List.of();
        }
        return foreignProductRepository
                .findByForeignProductIdInOrderByTickerAscForeignProductIdAsc(productIds)
                .stream()
                .map(CustomerForeignProductResponseDTO::new)
                .toList();
    }

    @Override
    public CustomerInboundCandidatesResponseDTO getCandidates(Long customerId, Long foreignProductId) {
        AccountEntity account = getAccount(customerId);
        String ciHash = account.getCustomer().getCiHash();

        List<RegistrableStockResponseDTO> lots = registrableStockClient.findCustomerLots(ciHash)
                .stream()
                .filter(lot -> foreignProductId.equals(lot.getForeignProductId()))
                .toList();

        ForeignProductEntity product = foreignProductRepository
                .findByForeignProductIdInOrderByTickerAscForeignProductIdAsc(List.of(foreignProductId))
                .stream()
                .findFirst()
                .orElseThrow(() -> new AppException(ErrorType.CUSTOMER_REGISTRABLE_STOCK_NOT_FOUND, foreignProductId));

        // 계좌별 snapshot 합산
        Map<Long, BigDecimal> snapshotByAccount = lots.stream()
                .collect(Collectors.groupingBy(
                        RegistrableStockResponseDTO::getGeneralAccountId,
                        Collectors.reducing(BigDecimal.ZERO, RegistrableStockResponseDTO::getHeldQty, BigDecimal::add)));

        // 계좌별 대표 lot (accountNo, brokerName 확인용)
        Map<Long, RegistrableStockResponseDTO> firstLotByAccount = lots.stream()
                .collect(Collectors.toMap(
                        RegistrableStockResponseDTO::getGeneralAccountId,
                        l -> l,
                        (a, b) -> a));

        List<CustomerInboundCandidateResponseDTO> candidates = snapshotByAccount.entrySet().stream()
                .map(entry -> {
                    Long accountId = entry.getKey();
                    BigDecimal snapshotQty = entry.getValue();
                    BigDecimal alreadyApproved = inboundDetailRepository
                            .sumApprovedQtyByAccountAndProductAndSourceGeneralAccount(
                                    account.getAccountId(), foreignProductId, accountId);
                    BigDecimal available = snapshotQty.subtract(alreadyApproved).max(BigDecimal.ZERO);
                    RegistrableStockResponseDTO rep = firstLotByAccount.get(accountId);
                    return CustomerInboundCandidateResponseDTO.builder()
                            .sourceGeneralAccountId(accountId)
                            .accountNo(rep.getAccountNo())
                            .brokerName(rep.getSourceBroker())
                            .snapshotQty(snapshotQty)
                            .alreadyApprovedQty(alreadyApproved)
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
        Page<InboundEntity> inboundPage = inboundRepository
                .findByAccount_Customer_CustomerIdOrderByProcessedAtDescInboundIdDesc(
                        customerId, PageRequest.of(page, size));

        List<Long> inboundIds = inboundPage.stream()
                .map(InboundEntity::getInboundId)
                .toList();
        Map<Long, ForeignProductEntity> productByInboundId = inboundIds.isEmpty()
                ? Map.of()
                : inboundDetailRepository.findByInbound_InboundIdInOrderByInboundDetailIdAsc(inboundIds)
                        .stream()
                        .collect(Collectors.toMap(
                                d -> d.getInbound().getInboundId(),
                                InboundDetailEntity::getForeignProduct,
                                (a, b) -> a));

        List<CustomerInboundHistoryResponseDTO> content = inboundPage.stream()
                .map(inbound -> new CustomerInboundHistoryResponseDTO(
                        inbound, productByInboundId.get(inbound.getInboundId())))
                .toList();

        return PageResponseDTO.of(content, inboundPage.getTotalElements(), page, size);
    }

    @Override
    public CustomerInboundDetailResponseDTO getDetail(Long customerId, Long inboundId) {
        InboundEntity inbound = inboundRepository
                .findByInboundIdAndAccount_Customer_CustomerId(inboundId, customerId)
                .orElseThrow(() -> new AppException(ErrorType.INBOUND_NOT_FOUND, inboundId));

        List<InboundDetailEntity> details = inboundDetailRepository
                .findByInbound_InboundIdAndInbound_Account_Customer_CustomerIdOrderByInboundDetailIdAsc(
                        inboundId, customerId);

        ForeignProductEntity product = details.isEmpty() ? null : details.get(0).getForeignProduct();
        return new CustomerInboundDetailResponseDTO(inbound, product, details);
    }

    private AccountEntity getAccount(Long customerId) {
        return accountRepository
                .findByCustomer_CustomerId(customerId)
                .orElseThrow(() -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, customerId));
    }
}
