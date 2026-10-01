package com.app.maria.domain.withdrawal.service;

import com.app.maria.domain.withdrawal.dto.WithdrawalResultDTO;
import com.app.maria.domain.withdrawal.dto.request.WithdrawalRequestDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalDetailResponseDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalListResponseDTO;
import com.app.maria.domain.withdrawal.type.WithdrawalStatus;
import java.math.BigDecimal;
import java.util.List;

public interface WithdrawalService {

    WithdrawalResultDTO withdraw(WithdrawalRequestDTO requestDTO);

    WithdrawalResultDTO withdrawForClosure(WithdrawalRequestDTO requestDTO);

    List<WithdrawalListResponseDTO> getWithdrawals(WithdrawalStatus status);

    List<WithdrawalListResponseDTO> getWithdrawalsByAccountId(Long accountId);

    WithdrawalDetailResponseDTO getWithdrawal(Long withdrawalId);

    boolean hasImmaturePrincipal(Long accountId);

    BigDecimal getImmaturePrincipalAmount(Long accountId);

    BigDecimal getImmatureAllocatedAmount(Long withdrawalId);
}
