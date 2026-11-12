package com.app.maria.domain.fundflow.service;

import com.app.maria.domain.fundflow.dto.request.FundFlowMonthlyRequestDTO;
import com.app.maria.domain.fundflow.dto.request.FundFlowPeriodRequestDTO;
import com.app.maria.domain.fundflow.dto.request.FundFlowReliefRequestDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowAllocationResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowConcentrationResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowEarlyWithdrawalResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowFunnelResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowLeakageResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowMaturityResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowMonthlyResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowOverviewResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowReliefResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowRetentionResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowSegmentResponseDTO;

public interface AdminFundFlowService {

    FundFlowFunnelResponseDTO getFunnel(FundFlowPeriodRequestDTO request);

    FundFlowOverviewResponseDTO getOverview(FundFlowPeriodRequestDTO request);

    FundFlowLeakageResponseDTO getLeakage();

    FundFlowMonthlyResponseDTO getMonthly(FundFlowMonthlyRequestDTO request);

    FundFlowSegmentResponseDTO getSegments(FundFlowPeriodRequestDTO request);

    FundFlowReliefResponseDTO getRelief(FundFlowReliefRequestDTO request);

    FundFlowAllocationResponseDTO getAllocation(FundFlowPeriodRequestDTO request);

    FundFlowConcentrationResponseDTO getConcentration();

    FundFlowRetentionResponseDTO getRetention(FundFlowPeriodRequestDTO request);

    FundFlowMaturityResponseDTO getMaturity();

    FundFlowEarlyWithdrawalResponseDTO getEarlyWithdrawal();
}
