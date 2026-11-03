package com.app.maria.domain.community.dto.request;

import com.app.maria.domain.community.type.ReportAction;
import jakarta.validation.constraints.NotNull;

public record ReportActionRequestDTO(@NotNull(message = "처리 방법을 선택해 주세요.") ReportAction action) {}
