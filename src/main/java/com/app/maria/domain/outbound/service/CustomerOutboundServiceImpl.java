package com.app.maria.domain.outbound.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.inbound.dto.InboundDetailDTO;
import com.app.maria.domain.inbound.mapper.InboundMapper;
import com.app.maria.domain.outbound.dto.OutboundDTO;
import com.app.maria.domain.outbound.dto.request.CustomerOutboundRequestDTO;
import com.app.maria.domain.outbound.dto.response.CustomerOutboundResponseDTO;
import com.app.maria.domain.outbound.mapper.OutboundMapper;
import com.app.maria.domain.outbound.type.OutboundReason;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class CustomerOutboundServiceImpl implements CustomerOutboundService {

    private final AccountMapper accountMapper;
    private final InboundMapper inboundMapper;
    private final OutboundMapper outboundMapper;
    private final BusinessClockService businessClockService;

    @Override
    public CustomerOutboundResponseDTO submit(Long customerId, CustomerOutboundRequestDTO request) {
        // 계좌 FOR UPDATE 락 — 매도·출고 동시 요청 직렬화
        AccountDTO account =
                accountMapper
                        .selectByCustomerIdForUpdate(customerId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, customerId));

        if (!account.getStatus().canTrade()) {
            throw new AppException(ErrorType.ACCOUNT_STATE_NOT_ALLOWED, account.getStatus());
        }

        Long foreignProductId = request.getForeignProductId();

        // FIFO lot 조회 (current_qty > 0인 lot만)
        List<InboundDetailDTO> lots =
                inboundMapper.selectFifoLots(account.getAccountId(), foreignProductId);

        BigDecimal availableQty =
                lots.stream()
                        .map(InboundDetailDTO::getCurrentQty)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (availableQty.compareTo(BigDecimal.ZERO) == 0) {
            throw new AppException(ErrorType.OUTBOUND_NO_AVAILABLE_QTY, foreignProductId);
        }

        BigDecimal outboundQty = request.getQty().min(availableQty);
        LocalDateTime processedAt = businessClockService.now();

        BigDecimal remaining = outboundQty;
        int lotCount = 0;

        for (InboundDetailDTO lot : lots) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;

            BigDecimal fromThisLot = remaining.min(lot.getCurrentQty());

            int updated = inboundMapper.decreaseCurrentQty(lot.getInboundDetailId(), fromThisLot);
            if (updated == 0) {
                throw new AppException(
                        ErrorType.SELL_ORDER_CONCURRENT_CONFLICT, lot.getInboundDetailId());
            }

            outboundMapper.insertOutbound(
                    OutboundDTO.builder()
                            .inboundDetailId(lot.getInboundDetailId())
                            .qty(fromThisLot)
                            .reason(OutboundReason.WITHDRAWAL_OF_INTENT.name())
                            .processedAt(processedAt)
                            .build());

            remaining = remaining.subtract(fromThisLot);
            lotCount++;
        }

        return new CustomerOutboundResponseDTO(outboundQty, lotCount, processedAt);
    }
}
