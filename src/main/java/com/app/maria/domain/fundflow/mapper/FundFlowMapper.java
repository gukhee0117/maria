package com.app.maria.domain.fundflow.mapper;

import com.app.maria.domain.fundflow.dto.FundFlowAccountFlowRowDTO;
import com.app.maria.domain.fundflow.dto.FundFlowCustomerReliefDTO;
import com.app.maria.domain.fundflow.dto.FundFlowCustomerSummaryDTO;
import com.app.maria.domain.fundflow.dto.FundFlowFilterDTO;
import com.app.maria.domain.fundflow.dto.FundFlowFunnelStageDTO;
import com.app.maria.domain.fundflow.dto.FundFlowLeakageCategoryDTO;
import com.app.maria.domain.fundflow.dto.FundFlowMonthDTO;
import com.app.maria.domain.fundflow.dto.FundFlowNetBuyDTO;
import com.app.maria.domain.fundflow.dto.FundFlowOverviewDTO;
import com.app.maria.domain.fundflow.dto.FundFlowProductCostDTO;
import com.app.maria.domain.fundflow.dto.FundFlowRatioBinDTO;
import com.app.maria.domain.fundflow.dto.FundFlowReliefDTO;
import com.app.maria.domain.fundflow.dto.FundFlowRetentionLotRowDTO;
import com.app.maria.domain.fundflow.dto.FundFlowSegmentRowDTO;
import com.app.maria.domain.fundflow.dto.FundFlowTransactionRowDTO;
import com.app.maria.domain.fundflow.dto.FundFlowWithdrawalLotRowDTO;
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

    /** 매도한 계좌(weighted_sell > 0)의 조정비율 구간별 계좌 수. 코드는 AdjustRatioBin 이름. */
    List<FundFlowRatioBinDTO> selectRatioBins();

    LocalDateTime selectSnapshotCalculatedAt();

    /** fund_flow_monthly 기반의 완료된 달(monthStart 이전). */
    List<FundFlowMonthDTO> selectMonthlyClosed(FundFlowFilterDTO filter);

    /** 업무시계가 속한 진행 중인 달을 원천 테이블에서 직접 집계한다. */
    FundFlowMonthDTO selectMonthlyPartial(FundFlowFilterDTO filter);

    List<FundFlowSegmentRowDTO> selectSegments(FundFlowFilterDTO filter);

    FundFlowReliefDTO selectRelief(FundFlowFilterDTO filter);

    /** 계좌별 기간 내 확정 유입·국내 매수 체결금액(연령대 포함). 개설된 계좌만. */
    List<FundFlowAccountFlowRowDTO> selectAccountFlows(FundFlowFilterDTO filter);

    /** 감면액이 0보다 큰 계좌의 감면액, 오름차순. */
    List<java.math.BigDecimal> selectReliefAmounts(FundFlowFilterDTO filter);

    /** 확정된 환전 건별 경과일과 사건(1년 미경과 원금 인출) 여부, 계좌 순. */
    List<FundFlowRetentionLotRowDTO> selectRetentionLots(FundFlowFilterDTO filter);

    List<FundFlowProductCostDTO> selectProductCosts();

    List<FundFlowNetBuyDTO> selectTopNetBuy(FundFlowFilterDTO filter);

    /** 개설된 계좌가 없으면 null. */
    FundFlowCustomerSummaryDTO selectCustomerSummary(
            @Param("customerId") Long customerId, @Param("clockDate") LocalDate clockDate);

    /** 고객 본인 계좌의 완료된 달(monthStart 이전)만 낸다. */
    List<FundFlowMonthDTO> selectCustomerMonthly(
            @Param("customerId") Long customerId, @Param("monthStart") LocalDate monthStart);

    /** 고객 본인 계좌의 진행 중인 달 집계. 개설된 계좌가 없으면 null. */
    FundFlowMonthDTO selectCustomerMonthlyPartial(
            @Param("customerId") Long customerId,
            @Param("monthStart") LocalDate monthStart,
            @Param("clockDate") LocalDate clockDate);

    /** 고객 본인 계좌의 보유 종목별 매입금액 */
    List<FundFlowProductCostDTO> selectCustomerProductCosts(@Param("customerId") Long customerId);

    /** 고객 본인의 개설된 계좌가 있는지. */
    boolean existsCustomerOpenedAccount(@Param("customerId") Long customerId);

    /**
     * 고객 본인 계좌의 거래 원천 행(원화 확정·국내 매수·국내 매도·인출). 업무시계 날짜 미만만 낸다. 국내 주문은 패키지 주문이 아니어도, 채널과 상관없이 모두
     * 포함한다.
     */
    List<FundFlowTransactionRowDTO> selectCustomerTransactionRows(
            @Param("customerId") Long customerId, @Param("clockDate") LocalDate clockDate);

    /**
     * 고객 본인 계좌의 납입건(확정된 원화 환전)별 처음 납입액·남은 원금·인출 배분액. 전량 인출된 건도 포함한다. 읽기 전용이며 인출 패키지의 쿼리와 같은
     * 조건(확정·결제일 있음, left_amount 있음)을 쓴다.
     */
    List<FundFlowWithdrawalLotRowDTO> selectCustomerWithdrawalLots(
            @Param("customerId") Long customerId);

    /** 개설된 계좌가 없으면 null. 스냅샷이 없으면 스냅샷 컬럼만 null. */
    FundFlowCustomerReliefDTO selectCustomerRelief(
            @Param("customerId") Long customerId, @Param("asOfDate") LocalDate asOfDate);
}
