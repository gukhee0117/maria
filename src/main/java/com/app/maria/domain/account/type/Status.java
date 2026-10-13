package com.app.maria.domain.account.type;

import org.apache.ibatis.type.Alias;

@Alias("AccountStatus")
public enum Status {
    APPLIED,
    OPENED,
    CLOSURE_REQUESTED,
    CLOSED,
    REJECTED;

    public boolean canTrade() {
        return this == OPENED;
    }
}
