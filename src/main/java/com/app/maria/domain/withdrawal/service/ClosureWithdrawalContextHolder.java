package com.app.maria.domain.withdrawal.service;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * 해지 승인 중 강제인출이 실제로 시도된 시점의 컨텍스트를 스레드 로컬로 보관한다.
 *
 * <p>WithdrawalServiceImpl.withdrawForClosure()가 잠금 후 확정된 금액과 목적계좌 정보를 저장하고,
 * AccountClosureApprovalOrchestrator가 트랜잭션 롤백 후 꺼내 FAILED 행을 기록한다.
 * 인출 시도 전에 실패한 경우(상태 검사·검증 단계)는 set()이 호출되지 않으므로 null이 반환되어
 * 불필요한 FAILED 행이 생기지 않는다.
 */
@Component
public class ClosureWithdrawalContextHolder {

    private static final ThreadLocal<Attempt> holder = new ThreadLocal<>();

    public void set(
            Long accountId,
            BigDecimal requestedAmount,
            String destinationAccountNo,
            Long destinationGeneralAccountId) {
        holder.set(new Attempt(accountId, requestedAmount, destinationAccountNo, destinationGeneralAccountId));
    }

    /** 값을 꺼내고 동시에 초기화한다. */
    public Attempt getAndClear() {
        Attempt attempt = holder.get();
        holder.remove();
        return attempt;
    }

    /** 값이 남아있으면 초기화한다(성공 경로 정리용). */
    public void clear() {
        holder.remove();
    }

    public record Attempt(
            Long accountId,
            BigDecimal requestedAmount,
            String destinationAccountNo,
            Long destinationGeneralAccountId) {}
}
