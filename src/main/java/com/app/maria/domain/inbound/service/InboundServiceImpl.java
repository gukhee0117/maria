package com.app.maria.domain.inbound.service;

import com.app.maria.domain.account.exception.AccountNotFoundException;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.foreignproduct.dto.ForeignProductDTO;
import com.app.maria.domain.foreignproduct.exception.ForeignProductNotFoundException;
import com.app.maria.domain.foreignproduct.mapper.ForeignProductMapper;
import com.app.maria.domain.inbound.dto.*;
import com.app.maria.domain.inbound.dto.request.InboundRequestDTO;
import com.app.maria.domain.inbound.dto.response.*;
import com.app.maria.domain.inbound.exception.InboundNotFoundException;
import com.app.maria.domain.inbound.mapper.InboundMapper;
import com.app.maria.domain.inbound.type.InboundZeroApprovalReason;
import com.app.maria.domain.registrablestock.dto.RegistrableStockResponseDTO;
import com.app.maria.domain.sellorder.dto.SellOrderDTO;
import com.app.maria.domain.sellorder.mapper.SellOrderMapper;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.response.PageResponseDTO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

@Service
@Transactional(rollbackFor = Exception.class)
public class InboundServiceImpl implements InboundService {

    private final InboundMapper inboundMapper;
    private final ForeignProductMapper foreignProductMapper;
    private final AccountMapper accountMapper;
    private final SellOrderMapper sellOrderMapper;
    private final RestClient restClient;
    private final BusinessClockService businessClockService;

    public InboundServiceImpl(
            InboundMapper inboundMapper,
            ForeignProductMapper foreignProductMapper,
            AccountMapper accountMapper,
            SellOrderMapper sellOrderMapper,
            @Qualifier("returnSecuritiesRestClient") RestClient restClient,
            BusinessClockService businessClockService) {
        this.inboundMapper = inboundMapper;
        this.foreignProductMapper = foreignProductMapper;
        this.accountMapper = accountMapper;
        this.sellOrderMapper = sellOrderMapper;
        this.restClient = restClient;
        this.businessClockService = businessClockService;
    }

    @Override
    public InboundResponseDTO processInbound(InboundRequestDTO request) {

        Long accountId = request.getAccountId();
        Long foreignProductId = request.getForeignProductId();
        BigDecimal requestedQty = request.getRequestedQty();
        BigDecimal currentHoldingAtRequest = request.getCurrentHoldingAtRequest();

        Long customerId =
                accountMapper
                        .selectByAccountId(accountId)
                        .orElseThrow(() -> new AccountNotFoundException("입고 대상 계좌가 존재하지 않습니다."))
                        .getCustomerId();

        String ciHash =
                accountMapper
                        .selectCiHashByCustomerId(customerId)
                        .orElseThrow(
                                () -> new AccountNotFoundException("입고 계좌의 고객 식별정보를 찾을 수 없습니다."));

        ApiResponseDTO<RegistrableStockResponseDTO> apiResponse =
                restClient
                        .get()
                        .uri(
                                "/api/registrable-stocks?ciHash={ciHash}&foreignProductId={foreignProductId}",
                                ciHash,
                                foreignProductId)
                        .retrieve()
                        .body(
                                new ParameterizedTypeReference<
                                        ApiResponseDTO<RegistrableStockResponseDTO>>() {});

        if (apiResponse == null || apiResponse.getData() == null) {
            throw new InboundNotFoundException("등록가능 보유수량 조회 실패");
        }

        RegistrableStockResponseDTO registrableStock = apiResponse.getData();
        BigDecimal snapshotQty = registrableStock.getHeldQty();

        BigDecimal alreadyApprovedQty =
                inboundMapper.sumApprovedQtyByAccountAndProduct(accountId, foreignProductId);
        BigDecimal availableQty = snapshotQty.subtract(alreadyApprovedQty).max(BigDecimal.ZERO);

        BigDecimal approvedQty = requestedQty.min(availableQty);
        if (currentHoldingAtRequest != null) {
            approvedQty = approvedQty.min(currentHoldingAtRequest);
        }

        InboundZeroApprovalReason zeroApprovalReason = null;
        if (approvedQty.compareTo(BigDecimal.ZERO) == 0) {
            if (requestedQty.compareTo(BigDecimal.ZERO) == 0) {
                zeroApprovalReason = InboundZeroApprovalReason.REQUESTED_ZERO;
            } else if (availableQty.compareTo(BigDecimal.ZERO) <= 0) {
                zeroApprovalReason = InboundZeroApprovalReason.SNAPSHOT_QUANTITY_EXHAUSTED;
            } else if (currentHoldingAtRequest != null
                    && currentHoldingAtRequest.compareTo(BigDecimal.ZERO) <= 0) {
                zeroApprovalReason = InboundZeroApprovalReason.CURRENT_HOLDING_INSUFFICIENT;
            }
        }

        InboundDTO inboundDTO =
                InboundDTO.builder()
                        .accountId(accountId)
                        .requestedQty(requestedQty)
                        .currentHoldingAtRequest(currentHoldingAtRequest)
                        .approvedQty(approvedQty)
                        .processedAt(businessClockService.now())
                        .build();
        inboundMapper.insertInbound(inboundDTO);

        List<RegistrableStockResponseDTO> lots =
                fetchRegistrableStockLots(ciHash, foreignProductId);
        Map<Long, BigDecimal> remainingAlreadyUsedByAccount =
                inboundMapper
                        .sumApprovedQtyBySourceGeneralAccount(accountId, foreignProductId)
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        SourceLotApprovedQtyDTO::getGeneralAccountId,
                                        SourceLotApprovedQtyDTO::getApprovedQty));

        BigDecimal remaining = approvedQty;
        boolean anyDetailCreated = false;
        for (RegistrableStockResponseDTO lot : lots) {
            Long generalAccountId = lot.getGeneralAccountId();
            BigDecimal alreadyUsed =
                    remainingAlreadyUsedByAccount.getOrDefault(generalAccountId, BigDecimal.ZERO);
            // 같은 general_account의 lot이 여러 개일 때 alreadyUsed를 lot마다 중복
            // 차감하지 않도록, 이번 lot에서 실제로 소진한 만큼만 계좌 잔여분에서 빼고
            // 남은 alreadyUsed는 다음 lot으로 이월한다.
            BigDecimal consumedByThisLot = alreadyUsed.min(lot.getHeldQty());
            remainingAlreadyUsedByAccount.put(
                    generalAccountId, alreadyUsed.subtract(consumedByThisLot));
            BigDecimal lotAvailable = lot.getHeldQty().subtract(consumedByThisLot);

            BigDecimal lotQty = BigDecimal.ZERO;
            if (remaining.compareTo(BigDecimal.ZERO) > 0
                    && lotAvailable.compareTo(BigDecimal.ZERO) > 0) {
                lotQty = remaining.min(lotAvailable);
            }

            if (lotQty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            insertInboundDetailAndMin(
                    inboundDTO.getInboundId(),
                    foreignProductId,
                    lot,
                    lotQty,
                    requestedQty,
                    snapshotQty);
            anyDetailCreated = true;
            remaining = remaining.subtract(lotQty);
        }

        if (!anyDetailCreated) {
            RegistrableStockResponseDTO fallbackLot = lots.isEmpty() ? null : lots.get(0);
            insertInboundDetailAndMin(
                    inboundDTO.getInboundId(),
                    foreignProductId,
                    fallbackLot,
                    BigDecimal.ZERO,
                    requestedQty,
                    snapshotQty);
        }

        return InboundResponseDTO.of(inboundDTO, snapshotQty, zeroApprovalReason);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountHoldingResponseDTO> getHoldings(Long accountId) {
        return inboundMapper.selectHoldingsByAccount(accountId).stream()
                .map(
                        holding -> {
                            ForeignProductDTO product =
                                    foreignProductMapper
                                            .selectById(holding.getForeignProductId())
                                            .orElseThrow(
                                                    () ->
                                                            new ForeignProductNotFoundException(
                                                                    "종목 정보를 찾을 수 없습니다."));
                            return new AccountHoldingResponseDTO(product, holding.getCurrentQty());
                        })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public InboundSummaryResponseDTO getSummary() {
        LocalDate today = businessClockService.now().toLocalDate();
        LocalDate tomorrow = today.plusDays(1);
        return new InboundSummaryResponseDTO(inboundMapper.selectTodaySummary(today, tomorrow));
    }

    @Override
    @Transactional(readOnly = true)
    public InboundPageDTO getInbounds(int page, int size) {
        int offset = page * size;
        List<InboundListDTO> content = inboundMapper.selectInbounds(offset, size);
        attachLots(content);

        long totalElements = inboundMapper.countInbounds();
        int totalPages = (int) Math.ceil((double) totalElements / size);

        return InboundPageDTO.builder()
                .content(content)
                .page(page)
                .size(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponseDTO<InboundAccountSummaryResponseDTO> getAccountsWithInbounds(
            int page, int size, String keyword) {
        int offset = page * size;
        List<InboundAccountSummaryResponseDTO> content =
                inboundMapper.selectAccountsWithInbounds(offset, size, keyword).stream()
                        .map(InboundAccountSummaryResponseDTO::new)
                        .toList();
        int totalCount = inboundMapper.countAccountsWithInbounds(keyword);
        return PageResponseDTO.of(content, totalCount, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public InboundPageDTO getInboundsByAccount(Long accountId, int page, int size) {
        int offset = page * size;
        List<InboundListDTO> content =
                inboundMapper.selectInboundsByAccountId(accountId, offset, size);
        attachLots(content);

        long totalElements = inboundMapper.countInboundsByAccountId(accountId);
        int totalPages = (int) Math.ceil((double) totalElements / size);

        return InboundPageDTO.builder()
                .content(content)
                .page(page)
                .size(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<InboundPriorApprovalResponseDTO> getPriorApprovals(Long inboundId) {
        return inboundMapper.selectPriorApprovals(inboundId).stream()
                .map(InboundPriorApprovalResponseDTO::new)
                .toList();
    }

    private void attachLots(List<InboundListDTO> content) {
        List<Long> inboundIds = content.stream().map(InboundListDTO::getInboundId).toList();
        List<InboundLotDTO> lots =
                inboundIds.isEmpty() ? List.of() : inboundMapper.selectLotsByInboundIds(inboundIds);

        List<Long> inboundDetailIds = lots.stream().map(InboundLotDTO::getInboundDetailId).toList();
        Map<Long, List<InboundSellHistoryDTO>> sellHistoryByDetailId =
                inboundDetailIds.isEmpty()
                        ? Map.of()
                        : sellOrderMapper
                                .selectSellOrdersByInboundDetailIds(inboundDetailIds)
                                .stream()
                                .map(this::toSellHistoryDTO)
                                .collect(
                                        Collectors.groupingBy(
                                                InboundSellHistoryDTO::getInboundDetailId));
        lots.forEach(
                lot ->
                        lot.setSellHistory(
                                sellHistoryByDetailId.getOrDefault(
                                        lot.getInboundDetailId(), List.of())));

        Map<Long, List<InboundLotDTO>> lotsByInboundId =
                lots.stream().collect(Collectors.groupingBy(InboundLotDTO::getInboundId));
        content.forEach(
                item -> item.setLots(lotsByInboundId.getOrDefault(item.getInboundId(), List.of())));
    }

    private InboundSellHistoryDTO toSellHistoryDTO(SellOrderDTO sellOrderDTO) {
        return InboundSellHistoryDTO.builder()
                .inboundDetailId(sellOrderDTO.getInboundDetailId())
                .sellQty(sellOrderDTO.getSellQty())
                .basePrice(sellOrderDTO.getBasePrice())
                .status(sellOrderDTO.getStatus())
                .processedAt(sellOrderDTO.getProcessedAt())
                .build();
    }

    private List<RegistrableStockResponseDTO> fetchRegistrableStockLots(
            String ciHash, Long foreignProductId) {
        ApiResponseDTO<List<RegistrableStockResponseDTO>> apiResponse =
                restClient
                        .get()
                        .uri(
                                "/api/registrable-stocks/lots?ciHash={ciHash}&foreignProductId={foreignProductId}",
                                ciHash,
                                foreignProductId)
                        .retrieve()
                        .body(
                                new ParameterizedTypeReference<
                                        ApiResponseDTO<List<RegistrableStockResponseDTO>>>() {});
        if (apiResponse == null || apiResponse.getData() == null) {
            throw new InboundNotFoundException("등록가능 보유수량 lot 조회 실패");
        }
        return apiResponse.getData();
    }

    private void insertInboundDetailAndMin(
            Long inboundId,
            Long foreignProductId,
            RegistrableStockResponseDTO lot,
            BigDecimal lotQty,
            BigDecimal requestedQty,
            BigDecimal snapshotQty) {
        InboundDetailDTO inboundDetailDTO =
                InboundDetailDTO.builder()
                        .inboundId(inboundId)
                        .foreignProductId(foreignProductId)
                        .qty(lotQty)
                        .currentQty(lotQty)
                        .recordedAt(businessClockService.now())
                        .accountType(lot != null ? lot.getAccountType() : null)
                        .purchaseDate(lot != null ? lot.getPurchaseDate() : null)
                        .purchasePrice(lot != null ? lot.getPurchasePrice() : null)
                        .purchaseCurrency(lot != null ? lot.getPurchaseCurrency() : null)
                        .purchaseFxRate(lot != null ? lot.getPurchaseFxRate() : null)
                        .sourceBroker(lot != null ? lot.getSourceBroker() : null)
                        .sourceGeneralAccountId(lot != null ? lot.getGeneralAccountId() : null)
                        .build();
        inboundMapper.insertInboundDetail(inboundDetailDTO);

        InboundMinDTO inboundMinDTO =
                InboundMinDTO.of(
                        inboundDetailDTO.getInboundDetailId(), requestedQty, lotQty, snapshotQty);
        inboundMapper.insertInboundMin(inboundMinDTO);
    }
}
