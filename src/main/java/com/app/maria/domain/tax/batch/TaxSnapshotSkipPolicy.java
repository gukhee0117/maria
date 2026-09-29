package com.app.maria.domain.tax.batch;

import com.app.maria.global.error.AppException;
import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.core.step.skip.SkipPolicy;
import org.springframework.stereotype.Component;

@Component
public class TaxSnapshotSkipPolicy implements SkipPolicy {

    private static final int SKIP_LIMIT = 100;

    @Override
    public boolean shouldSkip(Throwable t, long skipCount) throws SkipLimitExceededException {
        if (!(t instanceof AppException e) || !isTaxError(e)) {
            return false;
        }
        if (skipCount >= SKIP_LIMIT) {
            throw new SkipLimitExceededException(SKIP_LIMIT, e);
        }
        return true;
    }

    // AppException은 전역 공통 예외라 errorType으로 세액 관련 건만 골라 skip한다.
    // 다른 도메인이 이 배치 스텝(reader/processor/writer)에서 AppException을 던지게 되더라도
    // 그 건은 여기서 걸러지지 않고 배치를 실패시킨다.
    private boolean isTaxError(AppException e) {
        return e.getErrorType().name().startsWith("TAX_");
    }
}
