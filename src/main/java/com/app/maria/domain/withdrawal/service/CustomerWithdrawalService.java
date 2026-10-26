package com.app.maria.domain.withdrawal.service;

import com.app.maria.domain.withdrawal.dto.request.CustomerWithdrawalRequestDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalResultDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalDetailResponseDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalListResponseDTO;
import com.app.maria.global.client.generalaccount.dto.response.GeneralAccountResponseDTO;
import java.util.List;

public interface CustomerWithdrawalService {

    List<GeneralAccountResponseDTO> getDestinationAccounts(Long customerId);

    WithdrawalResultDTO withdraw(Long customerId, CustomerWithdrawalRequestDTO request);

    List<WithdrawalListResponseDTO> getWithdrawals(Long customerId);

    WithdrawalDetailResponseDTO getWithdrawal(Long customerId, Long withdrawalId);
}
