package com.app.maria.domain.fundflow.mapper;

import com.app.maria.domain.fundflow.dto.FundFlowCustomerReliefDTO;
import com.app.maria.domain.fundflow.dto.FundFlowCustomerSummaryDTO;
import com.app.maria.domain.fundflow.dto.FundFlowFilterDTO;
import com.app.maria.domain.fundflow.dto.FundFlowFunnelStageDTO;
import com.app.maria.domain.fundflow.dto.FundFlowLeakageCategoryDTO;
import com.app.maria.domain.fundflow.dto.FundFlowMonthDTO;
import com.app.maria.domain.fundflow.dto.FundFlowNetBuyDTO;
import com.app.maria.domain.fundflow.dto.FundFlowOverviewDTO;
import com.app.maria.domain.fundflow.dto.FundFlowProductCostDTO;
import com.app.maria.domain.fundflow.dto.FundFlowReliefDTO;
import com.app.maria.domain.fundflow.dto.FundFlowSegmentRowDTO;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FundFlowMapper {

    List<FundFlowFunnelStageDTO> selectFunnel(FundFlowFilterDTO filter);

    FundFlowOverviewDTO selectOverview(FundFlowFilterDTO filter);

    List<FundFlowLeakageCategoryDTO> selectLeakage();

    LocalDateTime selectSnapshotCalculatedAt();

    /** fund_flow_monthly 기반의 완료된 달(monthStart 이전). */
    List<FundFlowMonthDTO> selectMonthlyClosed(FundFlowFilterDTO filter);

    /** 업무시계가 속한 진행 중인 달을 원천 테이블에서 직접 집계한다. */
    FundFlowMonthDTO selectMonthlyPartial(FundFlowFilterDTO filter);

    List<FundFlowSegmentRowDTO> selectSegments(FundFlowFilterDTO filter);

    FundFlowReliefDTO selectRelief(FundFlowFilterDTO filter);

    List<FundFlowProductCostDTO> selectProductCosts();

    List<FundFlowNetBuyDTO> selectTopNetBuy(FundFlowFilterDTO filter);

    /** 개설된 계좌가 없으면 null. */
    FundFlowCustomerSummaryDTO selectCustomerSummary(
            @Param("customerId") Long customerId, @Param("clockDate") LocalDate clockDate);

    List<FundFlowMonthDTO> selectCustomerMonthly(@Param("customerId") Long customerId);

    /** 고객 본인 계좌의 보유 종목별 매입금액 */
    List<FundFlowProductCostDTO> selectCustomerProductCosts(@Param("customerId") Long customerId);

    /** 개설된 계좌가 없으면 null. 스냅샷이 없으면 스냅샷 컬럼만 null. */
    FundFlowCustomerReliefDTO selectCustomerRelief(
            @Param("customerId") Long customerId, @Param("asOfDate") LocalDate asOfDate);
}
