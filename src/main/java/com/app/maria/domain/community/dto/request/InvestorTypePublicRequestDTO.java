package com.app.maria.domain.community.dto.request;

import jakarta.validation.constraints.NotNull;

public record InvestorTypePublicRequestDTO(
        @NotNull(message = "공개 여부를 선택해 주세요") Boolean investorTypePublic) {}
