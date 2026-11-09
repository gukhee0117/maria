package com.app.maria.domain.account.service;

import com.app.maria.domain.account.dto.request.AccountLimitUpdateRequestDTO;
import com.app.maria.domain.account.dto.request.AccountReapplyRequestDTO;
import com.app.maria.domain.account.dto.request.AccountRequestDTO;
import com.app.maria.domain.account.dto.request.AccountSearchRequestDTO;
import com.app.maria.domain.account.dto.response.AccountJoinResponseDTO;
import com.app.maria.domain.account.dto.response.AccountLimitUsageResponseDTO;
import com.app.maria.domain.account.dto.response.AccountLogResponseDTO;
import com.app.maria.domain.account.dto.response.AccountManagementDetailResponseDTO;
import com.app.maria.domain.account.dto.response.AccountResponseDTO;
import java.math.BigDecimal;
import java.util.List;

public interface AccountService {
    // 관리자 목록 조회
    List<AccountJoinResponseDTO> findAll();

    // 고객의 전 금융회사 RIA 납입한도를 반영한 현재 설정 가능 최대 한도
    BigDecimal getAvailableLimit(Long customerId);

    // 고객 마이페이지용 한도 변경: APPLIED, OPENED 상태에서만 허용
    AccountResponseDTO updateAccountLimit(AccountLimitUpdateRequestDTO requestDTO);

    // 관리자 대리 신청
    AccountResponseDTO applyAccount(AccountRequestDTO requestDTO);

    // 고객 본인 신청: 인증된 세션의 customerId만 전달한다.
    AccountResponseDTO applyCustomerAccount(
            Long customerId, BigDecimal limitAmount, boolean mydataConsent);

    /** 고객 본인의 한도 조회. 마이데이터 조회 동의가 있어야 한다(관리자 조회는 {@link #getAvailableLimit}). */
    BigDecimal getCustomerAvailableLimit(Long customerId);

    // 관리자 기능
    AccountResponseDTO approveAccount(Long accountId);

    AccountResponseDTO rejectAccount(Long accountId, String reason);

    AccountResponseDTO reapplyAccountByAccountId(
            Long accountId, AccountReapplyRequestDTO requestDTO);

    AccountResponseDTO getAccountByAccountId(Long accountId);

    List<AccountLogResponseDTO> getStatusLogsByAccountId(Long accountId);

    AccountManagementDetailResponseDTO getManagementDetail(Long accountId);

    AccountResponseDTO overrideAccount(Long accountId, String reason);

    int getAppliedAccountCount();

    int getAccountsRequiringActionCount();

    List<AccountLimitUsageResponseDTO> selectAccountLimitUsage();

    List<AccountLimitUsageResponseDTO> getAppliedAccounts();

    List<AccountLimitUsageResponseDTO> searchAccounts(AccountSearchRequestDTO request);
}
