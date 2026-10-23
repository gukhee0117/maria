package com.app.maria.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import org.junit.jupiter.api.Test;

class AppExceptionTest {

    @Test
    void detailOf_errorData가_있으면_errorData만_돌려준다() {
        AppException e =
                new AppException(ErrorType.SETTLEMENT_STATE_CONFLICT, "batchId=3, pending=2");

        assertThat(AppException.detailOf(e)).isEqualTo("batchId=3, pending=2");
    }

    @Test
    void detailOf_errorData가_없으면_고정_메시지를_돌려준다() {
        AppException e = new AppException(ErrorType.SETTLEMENT_STATE_CONFLICT);

        assertThat(AppException.detailOf(e))
                .isEqualTo(ErrorType.SETTLEMENT_STATE_CONFLICT.getMessage());
    }

    @Test
    void detailOf_AppException이_아니면_getMessage를_돌려준다() {
        assertThat(AppException.detailOf(new IllegalStateException("launch failed")))
                .isEqualTo("launch failed");
    }
}
