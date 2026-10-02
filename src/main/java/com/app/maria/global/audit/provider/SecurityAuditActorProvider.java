package com.app.maria.global.audit.provider;

import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SecurityAuditActorProvider implements AuditActorProvider {

    @Override
    public Long getCurrentAdminId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Long adminId)) {
            throw new AppException(ErrorType.AUDIT_LOG_ACTOR_NOT_FOUND);
        }
        return adminId;
    }
}
