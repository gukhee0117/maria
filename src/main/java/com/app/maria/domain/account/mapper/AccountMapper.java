package com.app.maria.domain.account.mapper;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.dto.AccountJoinDTO;
import com.app.maria.domain.account.dto.AccountLimitUsageDTO;
import com.app.maria.domain.account.dto.AccountSearchDTO;
import com.app.maria.domain.account.dto.response.AccountManagementDetailResponseDTO;
import com.app.maria.domain.account.type.BenefitType;
import com.app.maria.domain.account.type.Status;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AccountMapper {
    // 고객 존재 여부 확인
    boolean existsCustomerById(Long customerId);

    // account_id 기준 단건 조회
    Optional<AccountDTO> selectByAccountId(Long accountId);

    // account_id 기준 단건 조회 + 잠금
    Optional<AccountDTO> selectByAccountIdForUpdate(Long accountId);

    // customer_id 기준 단건 조회
    Optional<AccountDTO> selectByCustomerId(Long customerId);

    // customer_id 기준 단건 조회 + 잠금 (동시 신청 직렬화 — 트랜잭션 최초 읽기를 잠금 읽기로 만들어 REPEATABLE READ 스냅샷 기준을 현재 커밋 상태로 고정)
    Optional<AccountDTO> selectByCustomerIdForUpdate(Long customerId);

    // customer_id 기준 계좌 존재 여부
    boolean existsByCustomerId(Long customerId);

    // 최초 계좌 개설 신청
    int insertApplication(AccountDTO accountDTO);

    // 재신청
    int reapply(AccountDTO accountDTO);

    // APPLIED 또는 OPENED 계좌의 설정한도 변경
    int updateLimit(
            Long accountId,
            Status status,
            BigDecimal expectedCurrentLimit,
            BigDecimal newLimitAmount);

    BigDecimal selectOwnUsedAndReservedAmount(Long accountId);

    // 신청 승인
    // - 계좌번호는 승인 시점에 최초 1회 발급
    // - 수정 건수가 0이면 이미 처리됐거나 신청 상태가 아님
    int approve(AccountDTO accountDTO);

    // 신청 반려
    // - 반려 사유는 account_status_log에 별도로 저장
    // - 수정 건수가 0이면 이미 처리됐거나 신청 상태가 아님
    int reject(AccountDTO accountDTO);

    // 상태별 계좌 목록
    List<AccountDTO> selectAllAccount();

    List<AccountJoinDTO> selectAccountList();

    // 관리자 반려 판정 오버라이드
    int overrideToOpened(AccountDTO accountDTO);

    // 세제혜택 불가 변경
    int updateBenefitToImpossible(Long accountId);

    // 세제혜택 상태 변경(현재 상태가 expectedStatus일 때만). AccountBenefitService 전용
    int updateBenefit(
            @Param("accountId") Long accountId,
            @Param("newStatus") BenefitType newStatus,
            @Param("expectedStatus") BenefitType expectedStatus);

    // customerId를 통해 ci_hash 값 가져오기
    Optional<String> selectCiHashByCustomerId(Long customerId);

    // 계좌 잔액 변경

    /**
     * @param provisionalAmountDelta : AccountDTO.builder().accountId(업데이트 할 계좌ID).amount(더해줄
     *     amount값).build();
     * @return 성공시 1, 실패시 0
     */
    int updateProvisionalAmount(AccountDTO provisionalAmountDelta);

    // 심사대기 건수
    int countByStatus(Status status);

    int countAccountsRequiringAction();

    // 계좌별 한도 사용률
    List<AccountLimitUsageDTO> selectAccountLimitUsage();

    // applied 계좌 목록
    List<AccountLimitUsageDTO> selectAppliedAccounts();

    List<AccountLimitUsageDTO> searchAccounts(AccountSearchDTO condition);

    // 해지 신청 상태로 변경
    int requestClosure(Long accountId);

    // 해지 반려 시 계좌 상태 변경
    int reopenAfterClosureRejection(Long accountId);

    // 해지 완료 상태변경
    int completeClosure(Long accountId);

    List<AccountDTO> selectOpenedAccountsAfter(
            @Param("lastAccountId") long lastAccountId, @Param("pageSize") int pageSize);

    List<AccountManagementDetailResponseDTO.Holding> selectManagementHoldings(Long accountId);

    List<AccountManagementDetailResponseDTO.Inbound> selectManagementInbounds(Long accountId);

    Optional<AccountManagementDetailResponseDTO.Closure> selectLatestManagementClosure(
            Long accountId);

    List<AccountManagementDetailResponseDTO.Withdrawal> selectManagementWithdrawals(Long accountId);
}
