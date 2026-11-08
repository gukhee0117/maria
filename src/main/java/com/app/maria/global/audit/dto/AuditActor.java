package com.app.maria.global.audit.dto;

import java.util.Objects;

/** Audit identity never infers customer/system actions from a missing admin ID. */
public record AuditActor(Type type, Long id) {
    public enum Type {
        ADMIN,
        CUSTOMER,
        SYSTEM
    }

    public AuditActor {
        Objects.requireNonNull(type, "audit actor type");
        if (type != Type.SYSTEM) Objects.requireNonNull(id, "audit actor id");
        if (type == Type.SYSTEM && id != null)
            throw new IllegalArgumentException("system actor has no id");
    }

    public static AuditActor admin(Long id) {
        return new AuditActor(Type.ADMIN, id);
    }

    public static AuditActor customer(Long id) {
        return new AuditActor(Type.CUSTOMER, id);
    }
}
