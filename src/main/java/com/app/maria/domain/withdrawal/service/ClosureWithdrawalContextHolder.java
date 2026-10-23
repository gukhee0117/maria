package com.app.maria.domain.withdrawal.service;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * 해지 승인 중 강제인출이 실제로 시도된 시점의 컨텍스트를 스레드 로컬로 보관한다.
 *
 * <p>WithdrawalProcessor.processWithdrawal()이 계좌 상태 검사 통과 후 set()을 호출하고,
 * AccountClosureApprovalOrchestrator가 트랜잭션 롤백 후 꺼내 FAILED 행을 기록한다.
 * 상태 검사 단계에서 실패하면 set()이 호출되지 않으므로 null이 반환되어 불필요한 FAILED 행이 생기지 않는다.
 *
 * <p>현재 동기 호출 구조에서 메서드 시그니처 변경을 최소화하기 위해 ThreadLocal을 선택했다.
 * 인자 전달 또는 예외에 컨텍스트를 싣는 방식으로 대체할 수 있다.
 */
@Component
public class ClosureWithdrawalContextHolder {

    private static final ThreadLocal<Attempt> holder = new ThreadLocal<>();

    public void set(
            Long accountId,
            BigDecimal requestedAmount,
            String destinationAccountNo,
            Long destinationGeneralAccountId) {
        holder.set(
                new Attempt(
                        accountId,
                        requestedAmount,
                        destinationAccountNo,
                        destinationGeneralAccountId));
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
