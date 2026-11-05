package com.app.maria.domain.fundflow.service;

import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerAllocationResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerReliefResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerSummaryResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowMonthlyResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowTransactionsResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowWithdrawalScheduleResponseDTO;

public interface CustomerFundFlowService {

    FundFlowCustomerSummaryResponseDTO getSummary(Long customerId);

    FundFlowMonthlyResponseDTO getMonthly(Long customerId);

    FundFlowCustomerReliefResponseDTO getRelief(Long customerId);

    FundFlowCustomerAllocationResponseDTO getAllocation(Long customerId);

    /** 본인 계좌의 자금 이동 내역(원화 확정·국내 매수·국내 매도·인출). 오래된 순, /summary 와 같은 기준일까지. */
    FundFlowTransactionsResponseDTO getTransactions(Long customerId);

    FundFlowWithdrawalScheduleResponseDTO getWithdrawalSchedule(Long customerId);
}
