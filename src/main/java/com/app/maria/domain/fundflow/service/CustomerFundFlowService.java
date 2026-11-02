package com.app.maria.domain.fundflow.service;

import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerAllocationResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerReliefResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerSummaryResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowMonthlyResponseDTO;

public interface CustomerFundFlowService {

    FundFlowCustomerSummaryResponseDTO getSummary(Long customerId);

    FundFlowMonthlyResponseDTO getMonthly(Long customerId);

    FundFlowCustomerReliefResponseDTO getRelief(Long customerId);

    FundFlowCustomerAllocationResponseDTO getAllocation(Long customerId);
}
