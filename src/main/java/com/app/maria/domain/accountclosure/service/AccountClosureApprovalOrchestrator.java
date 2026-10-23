package com.app.maria.domain.accountclosure.service;

import com.app.maria.domain.withdrawal.dto.WithdrawalFailureContext;
import com.app.maria.domain.withdrawal.service.ClosureWithdrawalContextHolder;
import com.app.maria.domain.withdrawal.service.WithdrawalFailureService;
import com.app.maria.global.clock.service.BusinessClockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 해지 승인의 논트랜잭셔널 진입점.
 *
 * <p>AccountClosureServiceImpl.approveClosure()는 ACCOUNT 행을 FOR UPDATE로 잠근 채 인출을 수행하므로, 인출 실패
 * 기록(REQUIRES_NEW)을 같은 트랜잭션 안에서 시도하면 FK 잠금 충돌이 발생한다. 이 클래스는 트랜잭션 없이 동작하면서 transactional
 * approveClosure가 롤백된 뒤에 FAILED 행을 기록한다.
 *
 * <p>WithdrawalServiceImpl.withdrawForClosure()가 잠금 획득 후 ClosureWithdrawalContextHolder에 컨텍스트를
 * 저장한다. 이 클래스는 롤백 후 그 값을 꺼낸다. 값이 null이면 인출 시도 전에 실패한 것이므로 FAILED 행을 남기지 않는다.
 *
 * <p>한계: 해지 경로의 목적계좌 검증(verifyDestinationAccount)은 현재 ACCOUNT FOR UPDATE 잠금 안에서
 * 실행된다. 외부 HTTP 호출을 트랜잭션 밖으로 분리하는 작업은 별도 이슈로 다룬다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountClosureApprovalOrchestrator {

    private final AccountClosureService accountClosureService;
    private final ClosureWithdrawalContextHolder closureWithdrawalContextHolder;
    private final WithdrawalFailureService withdrawalFailureService;
    private final BusinessClockService businessClockService;

    /** 트랜잭셔널 해지 승인을 실행하고, 실패 시 FAILED 인출 이력을 기록한다. */
    public void approveClosure(Long adminId, Long closureRequestId) {
        // 이전 호출에서 ThreadLocal이 남아있을 경우를 방어한다.
        closureWithdrawalContextHolder.clear();
        try {
            accountClosureService.approveClosure(adminId, closureRequestId);
        } catch (Exception e) {
            // approveClosure 트랜잭션이 롤백되어 ACCOUNT 잠금이 해제된 시점.
            // FK 충돌 없이 FAILED 행을 INSERT할 수 있다.
            ClosureWithdrawalContextHolder.Attempt attempt =
                    closureWithdrawalContextHolder.getAndClear();
            if (attempt != null) {
                recordWithdrawalFailure(attempt);
            }
            throw e;
        } finally {
            // 성공 경로: ThreadLocal 잔여값 정리
            closureWithdrawalContextHolder.clear();
        }
    }

    private void recordWithdrawalFailure(ClosureWithdrawalContextHolder.Attempt attempt) {
        try {
            withdrawalFailureService.record(
                    new WithdrawalFailureContext(
                            attempt.accountId(),
                            attempt.requestedAmount(),
                            businessClockService.now(),
                            attempt.destinationAccountNo(),
                            attempt.destinationGeneralAccountId()));
        } catch (Exception e) {
            log.warn("해지 인출 FAILED 행 저장 실패 — accountId={}", attempt.accountId(), e);
        }
    }
}
