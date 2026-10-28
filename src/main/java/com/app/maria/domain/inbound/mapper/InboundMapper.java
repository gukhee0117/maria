package com.app.maria.domain.inbound.mapper;

import com.app.maria.domain.inbound.dto.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface InboundMapper {
    void insertInbound(InboundDTO inboundDTO);

    void insertInboundDetail(InboundDetailDTO inboundDetailDTO);

    void insertInboundMin(InboundMinDTO inboundMinDTO);

    BigDecimal sumApprovedQtyByAccountAndProduct(
            @Param("accountId") Long accountId, @Param("foreignProductId") Long foreignProductId);

    Optional<InboundDetailDTO> selectInboundDetailById(Long inboundDetailId);

    int decreaseCurrentQty(
            @Param("inboundDetailId") Long inboundDetailId, @Param("qty") BigDecimal qty);

    List<InboundDetailDTO> selectFifoLots(
            @Param("accountId") Long accountId, @Param("foreignProductId") Long foreignProductId);

    List<SourceLotApprovedQtyDTO> sumApprovedQtyBySourceGeneralAccount(
            @Param("accountId") Long accountId, @Param("foreignProductId") Long foreignProductId);

    List<InboundHoldingDTO> selectHoldingsByAccount(@Param("accountId") Long accountId);

    List<InboundListDTO> selectInbounds(@Param("offset") int offset, @Param("size") int size);

    List<InboundLotDTO> selectLotsByInboundIds(@Param("inboundIds") List<Long> inboundIds);

    int countInbounds();

    InboundSummaryDTO selectTodaySummary(
            @Param("today") LocalDate today, @Param("tomorrow") LocalDate tomorrow);

    List<InboundAccountSummaryDTO> selectAccountsWithInbounds(
            @Param("offset") int offset, @Param("size") int size, @Param("keyword") String keyword);

    int countAccountsWithInbounds(@Param("keyword") String keyword);

    List<InboundListDTO> selectInboundsByAccountId(
            @Param("accountId") Long accountId,
            @Param("offset") int offset,
            @Param("size") int size);

    int countInboundsByAccountId(@Param("accountId") Long accountId);

    List<InboundPriorApprovalDTO> selectPriorApprovals(@Param("inboundId") Long inboundId);

    BigDecimal sumApprovedQtyByAccountAndProductAndSourceGeneralAccount(
            @Param("accountId") Long accountId,
            @Param("foreignProductId") Long foreignProductId,
            @Param("sourceGeneralAccountId") Long sourceGeneralAccountId);

    BigDecimal sumApprovedQtyByAccountAndRegistrableStockId(
            @Param("accountId") Long accountId,
            @Param("registrableStockId") Long registrableStockId);

    boolean existsNullOriginByAccountAndProductAndSourceGeneralAccount(
            @Param("accountId") Long accountId,
            @Param("foreignProductId") Long foreignProductId,
            @Param("sourceGeneralAccountId") Long sourceGeneralAccountId);

    Optional<InboundDTO> selectInboundByIdAndAccountId(
            @Param("inboundId") Long inboundId, @Param("accountId") Long accountId);

    List<InboundDetailDTO> selectDetailsByInboundId(@Param("inboundId") Long inboundId);

    BigDecimal sumOutboundQtyByAccountAndProduct(
            @Param("accountId") Long accountId, @Param("foreignProductId") Long foreignProductId);
}
