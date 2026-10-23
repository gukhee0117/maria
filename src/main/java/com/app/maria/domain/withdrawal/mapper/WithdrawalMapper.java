package com.app.maria.domain.withdrawal.mapper;

import com.app.maria.domain.withdrawal.dto.LeftAmountDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalAllocationDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalAllocationHistoryDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalHistoryDTO;
import com.app.maria.domain.withdrawal.type.WithdrawalStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WithdrawalMapper {

    // 인출 기록 한 건 저장
    public int insertWithdrawal(WithdrawalDTO withdrawalDTO);

    // 인출 요청의 status를 변경
    public int updateWithdrawalStatus(
            @Param("withdrawalId") Long withdrawalId,
            @Param("status") WithdrawalStatus withdrawalStatus);

    // 인출내역 배분 저장
    public int insertWithdrawalAllocation(WithdrawalAllocationDTO withdrawalAllocationDTO);

    // 인출 후 남은 원금 차감 기능
    public int deductLeftAmount(
            @Param("leftAmountId") Long leftAmountId,
            @Param("deductAmount") BigDecimal deductAmount);

    // 인출 후 계좌 잔액 차감 기능
    public int deductAccountAmount(
            @Param("accountId") Long accountId,
            @Param("requestedAmount") BigDecimal requestedAmount);

    // 계좌 상태 검증
    public List<LeftAmountDTO> selectAvailableLeftAmountsByAccountId(Long accountId);

    // 완료된 인출에서 실제 배분된 미경과 원금 합계 조회
    BigDecimal selectImmatureAllocatedAmountByWithdrawalId(Long withdrawalId);

    List<WithdrawalHistoryDTO> selectWithdrawalHistories(@Param("status") WithdrawalStatus status);

    List<WithdrawalHistoryDTO> selectWithdrawalHistoriesByAccountId(Long accountId);

    Optional<WithdrawalHistoryDTO> selectWithdrawalHistoryById(Long withdrawalId);

    Optional<WithdrawalHistoryDTO> selectWithdrawalHistoryByIdAndAccountId(
            @Param("withdrawalId") Long withdrawalId, @Param("accountId") Long accountId);

    List<WithdrawalAllocationHistoryDTO> selectAllocationHistoriesByWithdrawalId(Long withdrawalId);

    BigDecimal selectProvisionalAmountByAccountId(Long accountId);
}
