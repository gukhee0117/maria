package com.app.maria.domain.accountclosure.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.accountclosure.dto.AccountClosureDTO;
import com.app.maria.domain.accountclosure.mapper.AccountClosureMapper;
import com.app.maria.domain.withdrawal.dto.WithdrawalFailureContext;
import com.app.maria.domain.withdrawal.service.WithdrawalFailureService;
import com.app.maria.global.client.generalaccount.GeneralAccountClient;
import com.app.maria.global.client.generalaccount.dto.request.GeneralAccountRequestDTO;
import com.app.maria.global.client.generalaccount.dto.response.GeneralAccountResponseDTO;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 해지 승인의 논트랜잭셔널 진입점.
 *
 * <p>AccountClosureServiceImpl.approveClosure()는 ACCOUNT 행을 FOR UPDATE로 잠근 채 인출을
 * 수행하므로, 인출 실패 기록(REQUIRES_NEW)을 같은 트랜잭션 안에서 시도하면 FK 잠금 충돌이 발생한다.
 * 이 클래스는 트랜잭션 없이 동작하면서 transactional approveClosure가 롤백된 뒤에 FAILED 행을
 * 기록한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountClosureApprovalOrchestrator {

    private final AccountClosureService accountClosureService;
    private final AccountClosureMapper accountClosureMapper;
    private final AccountMapper accountMapper;
    private final WithdrawalFailureService withdrawalFailureService;
    private final BusinessClockService businessClockService;
    private final GeneralAccountClient generalAccountClient;

    /** 트랜잭셔널 해지 승인을 실행하고, 실패 시 FAILED 인출 이력을 기록한다. */
    public void approveClosure(Long adminId, Long closureRequestId) {
        FailureContext ctx = prefetchForFailureRecording(closureRequestId);

        try {
            accountClosureService.approveClosure(adminId, closureRequestId);
        } catch (Exception e) {
            // approveClosure 트랜잭션이 롤백되어 ACCOUNT 잠금이 해제된 시점.
            // FK 충돌 없이 FAILED 행을 INSERT할 수 있다.
            if (ctx != null) {
                recordWithdrawalFailure(ctx);
            }
            throw e;
        }
    }

    /**
     * 실패 기록에 필요한 컨텍스트를 트랜잭션 밖에서 미리 조회한다.
     *
     * <p>인출 시도가 없는 경우(잔액 0)나 목적 계좌 정보를 가져올 수 없는 경우에는 null을 반환해
     * 실패 기록을 건너뛴다. 베스트 에퍼트이므로 조회 실패가 승인 흐름을 막지 않는다.
     */
    private FailureContext prefetchForFailureRecording(Long closureRequestId) {
        try {
            AccountClosureDTO closure =
                    accountClosureMapper
                            .selectById(closureRequestId)
                            .orElseThrow(
                                    () ->
                                            new AppException(
                                                    ErrorType.ACCOUNT_CLOSURE_NOT_FOUND,
                                                    closureRequestId));
            AccountDTO account =
                    accountMapper
                            .selectByAccountId(closure.getAccountId())
                            .orElseThrow(
                                    () ->
                                            new AppException(
                                                    ErrorType.ACCOUNT_NOT_FOUND,
                                                    closure.getAccountId()));

            if (account.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                return null; // 인출 시도가 없으므로 FAILED 기록 불필요
            }

            String ciHash =
                    accountMapper
                            .selectCiHashByCustomerId(account.getCustomerId())
                            .orElseThrow(
                                    () ->
                                            new AppException(
                                                    ErrorType.ACCOUNT_CUSTOMER_IDENTITY_NOT_FOUND,
                                                    account.getCustomerId()));
            GeneralAccountResponseDTO destination =
                    generalAccountClient.verifyGeneralAccount(
                            GeneralAccountRequestDTO.builder()
                                    .ciHash(ciHash)
                                    .generalAccountId(closure.getDestinationGeneralAccountId())
                                    .build());

            return new FailureContext(
                    closure.getAccountId(),
                    account.getAmount(),
                    destination.getAccountNo(),
                    closure.getDestinationGeneralAccountId());

        } catch (Exception e) {
            log.warn(
                    "해지 승인 실패 기록 컨텍스트 조회 실패 — closureRequestId={}, 실패 시 FAILED 행이 남지 않음",
                    closureRequestId,
                    e);
            return null;
        }
    }

    private void recordWithdrawalFailure(FailureContext ctx) {
        try {
            withdrawalFailureService.record(
                    new WithdrawalFailureContext(
                            ctx.accountId(),
                            ctx.amount(),
                            businessClockService.now(),
                            ctx.destinationAccountNo(),
                            ctx.destinationGeneralAccountId()));
        } catch (Exception e) {
            log.warn(
                    "해지 인출 FAILED 행 저장 실패 — accountId={}", ctx.accountId(), e);
        }
    }

    private record FailureContext(
            Long accountId,
            BigDecimal amount,
            String destinationAccountNo,
            Long destinationGeneralAccountId) {}
}
