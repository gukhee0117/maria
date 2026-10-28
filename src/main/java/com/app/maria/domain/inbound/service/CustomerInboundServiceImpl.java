package com.app.maria.domain.inbound.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.foreignproduct.dto.ForeignProductDTO;
import com.app.maria.domain.foreignproduct.mapper.ForeignProductMapper;
import com.app.maria.domain.inbound.dto.InboundDTO;
import com.app.maria.domain.inbound.dto.InboundDetailDTO;
import com.app.maria.domain.inbound.dto.InboundMinDTO;
import com.app.maria.domain.inbound.dto.request.CustomerInboundSubmitRequestDTO;
import com.app.maria.domain.inbound.dto.response.CustomerInboundSubmitResponseDTO;
import com.app.maria.domain.inbound.mapper.InboundMapper;
import com.app.maria.domain.registrablestock.dto.RegistrableStockResponseDTO;
import com.app.maria.global.client.registrablestock.RegistrableStockClient;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class CustomerInboundServiceImpl implements CustomerInboundService {

    private final AccountMapper accountMapper;
    private final ForeignProductMapper foreignProductMapper;
    private final InboundMapper inboundMapper;
    private final RegistrableStockClient registrableStockClient;
    private final BusinessClockService businessClockService;

    @Override
    public CustomerInboundSubmitResponseDTO submit(
            Long customerId, CustomerInboundSubmitRequestDTO request) {
        // 동시 신청 직렬화 — 트랜잭션 최초 읽기를 FOR UPDATE로 실행해
        // REPEATABLE READ 스냅샷 기준을 현재 커밋 상태로 고정한다.
        // 이후의 일반 SELECT(누적 승인 수량 등)도 이 시점 이후 커밋된 데이터를 읽는다.
        AccountDTO account =
                accountMapper
                        .selectByCustomerIdForUpdate(customerId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, customerId));

        if (!account.getStatus().canTrade()) {
            throw new AppException(ErrorType.ACCOUNT_STATE_NOT_ALLOWED, account.getStatus());
        }

        Long foreignProductId = request.getForeignProductId();
        Long sourceGeneralAccountId = request.getSourceGeneralAccountId();
        BigDecimal requestedQty = request.getRequestedQty();

        String ciHash =
                accountMapper
                        .selectCiHashByCustomerId(customerId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, customerId));

        List<RegistrableStockResponseDTO> matchingLots =
                registrableStockClient.findCustomerLots(ciHash).stream()
                        .filter(
                                lot ->
                                        foreignProductId.equals(lot.getForeignProductId())
                                                && sourceGeneralAccountId.equals(
                                                        lot.getGeneralAccountId()))
                        .toList();

        if (matchingLots.isEmpty()) {
            throw new AppException(
                    ErrorType.CUSTOMER_REGISTRABLE_STOCK_NOT_FOUND, foreignProductId);
        }

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

        // 기존 NULL 원본 이력이 있으면 lot 배분 불가
        if (inboundMapper.existsNullOriginByAccountAndProductAndSourceGeneralAccount(
                account.getAccountId(), foreignProductId, sourceGeneralAccountId)) {
            throw new AppException(ErrorType.INBOUND_LOT_ORIGIN_UNRESOLVABLE, foreignProductId);
        }

        // 계좌 단위 스냅샷 합산
        BigDecimal snapshotQty =
                matchingLots.stream()
                        .map(RegistrableStockResponseDTO::getHeldQty)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 재입고를 지원하기 위해 net locked 기준으로 가용 수량을 계산한다.
        // netLocked = 총 입고 - 총 출고 = RIA에 실제로 묶인 수량
        // available  = snapshotQty - netLocked
        BigDecimal alreadyInbounded =
                inboundMapper.sumApprovedQtyByAccountAndProduct(
                        account.getAccountId(), foreignProductId);
        BigDecimal alreadyOutbounded =
                inboundMapper.sumOutboundQtyByAccountAndProduct(
                        account.getAccountId(), foreignProductId);
        BigDecimal netLocked = alreadyInbounded.subtract(alreadyOutbounded);

        BigDecimal available = snapshotQty.subtract(netLocked).max(BigDecimal.ZERO);
        BigDecimal approvedQty = requestedQty.min(available);

        if (approvedQty.compareTo(BigDecimal.ZERO) == 0) {
            throw new AppException(ErrorType.CUSTOMER_INBOUND_QTY_ZERO, foreignProductId);
        }

        LocalDateTime now = businessClockService.now();

        InboundDTO inbound =
                InboundDTO.builder()
                        .accountId(account.getAccountId())
                        .requestedQty(requestedQty)
                        .approvedQty(approvedQty)
                        .processedAt(now)
                        .build();
        inboundMapper.insertInbound(inbound);

        // FIFO lot 배분
        List<InboundDetailDTO> savedDetails = new ArrayList<>();
        BigDecimal remaining = approvedQty;

        for (RegistrableStockResponseDTO lot : matchingLots) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;

            BigDecimal lotAlreadyApproved =
                    inboundMapper.sumApprovedQtyByAccountAndRegistrableStockId(
                            account.getAccountId(), lot.getRegistrableStockId());
            BigDecimal lotAvailable =
                    lot.getHeldQty().subtract(lotAlreadyApproved).max(BigDecimal.ZERO);

            if (lotAvailable.compareTo(BigDecimal.ZERO) == 0) continue;

            BigDecimal lotApproved = remaining.min(lotAvailable);
            remaining = remaining.subtract(lotApproved);

            InboundDetailDTO detail =
                    InboundDetailDTO.builder()
                            .inboundId(inbound.getInboundId())
                            .foreignProductId(foreignProductId)
                            .registrableStockId(lot.getRegistrableStockId())
                            .sourceGeneralAccountId(lot.getGeneralAccountId())
                            .accountType(lot.getAccountType())
                            .sourceBroker(lot.getSourceBroker())
                            .recordedAt(now)
                            .purchaseDate(lot.getPurchaseDate())
                            .purchasePrice(lot.getPurchasePrice())
                            .purchaseCurrency(lot.getPurchaseCurrency())
                            .purchaseFxRate(lot.getPurchaseFxRate())
                            .qty(lotApproved)
                            .currentQty(lotApproved)
                            .build();
            inboundMapper.insertInboundDetail(detail);

            inboundMapper.insertInboundMin(
                    InboundMinDTO.of(
                            detail.getInboundDetailId(),
                            requestedQty,
                            lotApproved,
                            lot.getHeldQty()));

            savedDetails.add(detail);
        }

        // 배분 완료 검증 — 불일치 시 전체 롤백
        if (remaining.compareTo(BigDecimal.ZERO) != 0) {
            throw new AppException(ErrorType.INBOUND_ALLOCATION_INTEGRITY_FAILED, approvedQty);
        }
        BigDecimal detailSum =
                savedDetails.stream()
                        .map(InboundDetailDTO::getQty)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (detailSum.compareTo(approvedQty) != 0) {
            throw new AppException(ErrorType.INBOUND_ALLOCATION_INTEGRITY_FAILED, approvedQty);
        }

        return new CustomerInboundSubmitResponseDTO(inbound, product, savedDetails);
    }
}
