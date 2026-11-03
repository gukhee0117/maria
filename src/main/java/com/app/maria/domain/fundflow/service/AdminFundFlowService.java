package com.app.maria.domain.fundflow.service;

import com.app.maria.domain.fundflow.dto.request.FundFlowMonthlyRequestDTO;
import com.app.maria.domain.fundflow.dto.request.FundFlowReliefRequestDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowAllocationResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowFunnelResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowLeakageResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowMonthlyResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowOverviewResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowReliefResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowSegmentResponseDTO;

public interface AdminFundFlowService {

    FundFlowFunnelResponseDTO getFunnel();

    FundFlowOverviewResponseDTO getOverview();

    FundFlowLeakageResponseDTO getLeakage();

    FundFlowMonthlyResponseDTO getMonthly(FundFlowMonthlyRequestDTO request);

    FundFlowSegmentResponseDTO getSegments();

    FundFlowReliefResponseDTO getRelief(FundFlowReliefRequestDTO request);

    FundFlowAllocationResponseDTO getAllocation();
}
