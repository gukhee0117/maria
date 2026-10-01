package com.app.maria.global.audit.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class SecurityAuditActorProviderTest {

    private final SecurityAuditActorProvider auditActorProvider = new SecurityAuditActorProvider();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsAuthenticatedAdminId() {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(99L, null, List.of()));

        assertThat(auditActorProvider.getCurrentAdminId()).isEqualTo(99L);
    }

    @Test
    void throwsWhenNoAdminIsAuthenticated() {
        assertThatThrownBy(auditActorProvider::getCurrentAdminId)
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.AUDIT_LOG_ACTOR_NOT_FOUND.getMessage());
    }

    @Test
    void throwsWhenAuthenticatedPrincipalIsNotAnAdminId() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken("admin", null, List.of()));

        assertThatThrownBy(auditActorProvider::getCurrentAdminId)
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.AUDIT_LOG_ACTOR_NOT_FOUND.getMessage());
    }
}
