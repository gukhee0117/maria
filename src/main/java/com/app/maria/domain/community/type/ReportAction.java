package com.app.maria.domain.community.type;

/** 관리자가 신고를 처리하는 방법. HIDE·DELETE는 신고를 인정하고, RESTORE는 신고를 기각하고 되살린다. */
public enum ReportAction {
    HIDE,
    DELETE,
    RESTORE
}
