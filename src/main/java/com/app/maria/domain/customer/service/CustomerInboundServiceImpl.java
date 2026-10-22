package com.app.maria.domain.customer.service;

import com.app.maria.domain.account.entity.AccountEntity;
import com.app.maria.domain.account.repository.AccountRepository;
import com.app.maria.domain.customer.dto.request.CustomerInboundSubmitRequestDTO;
import com.app.maria.domain.customer.dto.response.CustomerInboundSubmitResponseDTO;
import com.app.maria.domain.foreignproduct.entity.ForeignProductEntity;
import com.app.maria.domain.foreignproduct.repository.ForeignProductRepository;
import com.app.maria.domain.inbound.entity.InboundDetailEntity;
import com.app.maria.domain.inbound.entity.InboundEntity;
import com.app.maria.domain.inbound.entity.InboundMinEntity;
import com.app.maria.domain.inbound.repository.InboundDetailRepository;
import com.app.maria.domain.inbound.repository.InboundMinRepository;
import com.app.maria.domain.inbound.repository.InboundRepository;
import com.app.maria.domain.registrablestock.dto.RegistrableStockResponseDTO;
import com.app.maria.global.client.registrablestock.RegistrableStockClient;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(transactionManager = "jpaTransactionManager")
public class CustomerInboundServiceImpl implements CustomerInboundService {

    private final AccountRepository accountRepository;
    private final ForeignProductRepository foreignProductRepository;
    private final InboundRepository inboundRepository;
    private final InboundDetailRepository inboundDetailRepository;
    private final InboundMinRepository inboundMinRepository;
    private final RegistrableStockClient registrableStockClient;
    private final BusinessClockService businessClockService;

    @Override
    public CustomerInboundSubmitResponseDTO submit(
            Long customerId, CustomerInboundSubmitRequestDTO request) {
        // 동시 신청 직렬화 — 계좌 행 잠금
        AccountEntity account = accountRepository
                .findByCustomer_CustomerIdForUpdate(customerId)
                .orElseThrow(() -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, customerId));

        if (!account.getStatus().canTrade()) {
            throw new AppException(ErrorType.ACCOUNT_STATE_NOT_ALLOWED, account.getStatus());
        }

        Long foreignProductId = request.getForeignProductId();
        Long sourceGeneralAccountId = request.getSourceGeneralAccountId();
        BigDecimal requestedQty = request.getRequestedQty();

        String ciHash = account.getCustomer().getCiHash();
        List<RegistrableStockResponseDTO> matchingLots = registrableStockClient
                .findCustomerLots(ciHash)
                .stream()
                .filter(lot -> foreignProductId.equals(lot.getForeignProductId())
                        && sourceGeneralAccountId.equals(lot.getGeneralAccountId()))
                .toList();

        if (matchingLots.isEmpty()) {
            throw new AppException(
                    ErrorType.CUSTOMER_REGISTRABLE_STOCK_NOT_FOUND, foreignProductId);
        }

        ForeignProductEntity product = foreignProductRepository
                .findByForeignProductIdInOrderByTickerAscForeignProductIdAsc(
                        List.of(foreignProductId))
                .stream()
                .findFirst()
                .orElseThrow(() -> new AppException(
                        ErrorType.CUSTOMER_REGISTRABLE_STOCK_NOT_FOUND, foreignProductId));

        // 기존 NULL 원본 이력이 있으면 lot 배분 불가 — 확인 대상으로 처리
        if (inboundDetailRepository.existsNullOriginByAccountAndProductAndSourceGeneralAccount(
                account.getAccountId(), foreignProductId, sourceGeneralAccountId)) {
            throw new AppException(
                    ErrorType.INBOUND_LOT_ORIGIN_UNRESOLVABLE, foreignProductId);
        }

        // 계좌 단위 스냅샷 합산
        BigDecimal snapshotQty = matchingLots.stream()
                .map(RegistrableStockResponseDTO::getHeldQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal alreadyApproved = inboundDetailRepository
                .sumApprovedQtyByAccountAndProductAndSourceGeneralAccount(
                        account.getAccountId(), foreignProductId, sourceGeneralAccountId);

        BigDecimal available = snapshotQty.subtract(alreadyApproved).max(BigDecimal.ZERO);
        BigDecimal approvedQty = requestedQty.min(available);

        if (approvedQty.compareTo(BigDecimal.ZERO) == 0) {
            throw new AppException(ErrorType.CUSTOMER_INBOUND_QTY_ZERO, foreignProductId);
        }

        LocalDateTime now = businessClockService.now();

        // FIFO lot 배분
        List<InboundDetailEntity> savedDetails = new ArrayList<>();
        BigDecimal remaining = approvedQty;

        InboundEntity inbound = inboundRepository.save(
                InboundEntity.create(account, requestedQty, approvedQty, now));

        for (RegistrableStockResponseDTO lot : matchingLots) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;

            BigDecimal lotAlreadyApproved = inboundDetailRepository
                    .sumApprovedQtyByAccountAndRegistrableStockId(
                            account.getAccountId(), lot.getRegistrableStockId());
            BigDecimal lotAvailable = lot.getHeldQty()
                    .subtract(lotAlreadyApproved)
                    .max(BigDecimal.ZERO);

            if (lotAvailable.compareTo(BigDecimal.ZERO) == 0) continue;

            BigDecimal lotApproved = remaining.min(lotAvailable);
            remaining = remaining.subtract(lotApproved);

            InboundDetailEntity detail = inboundDetailRepository.save(
                    InboundDetailEntity.create(
                            inbound,
                            product,
                            lot.getRegistrableStockId(),
                            lot.getGeneralAccountId(),
                            lot.getAccountType(),
                            lot.getSourceBroker(),
                            now,
                            lot.getPurchaseDate(),
                            lot.getPurchasePrice(),
                            lot.getPurchaseCurrency(),
                            lot.getPurchaseFxRate(),
                            lotApproved));

            inboundMinRepository.save(
                    InboundMinEntity.create(detail, requestedQty, lotApproved, lot.getHeldQty()));

            savedDetails.add(detail);
        }

        // 배분 완료 검증 — 불일치 시 전체 롤백
        if (remaining.compareTo(BigDecimal.ZERO) != 0) {
            throw new AppException(ErrorType.INBOUND_ALLOCATION_INTEGRITY_FAILED, approvedQty);
        }
        BigDecimal detailSum = savedDetails.stream()
                .map(InboundDetailEntity::getQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (detailSum.compareTo(approvedQty) != 0) {
            throw new AppException(ErrorType.INBOUND_ALLOCATION_INTEGRITY_FAILED, approvedQty);
        }

        return new CustomerInboundSubmitResponseDTO(inbound, product, savedDetails);
    }
}
