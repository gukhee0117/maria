package com.app.maria.domain.community.api;

import com.app.maria.domain.community.dto.request.InvestorTypePublicRequestDTO;
import com.app.maria.domain.community.dto.response.InvestorTypePublicResponseDTO;
import com.app.maria.domain.community.service.CommunityProfileService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/customer/profile")
public class CommunityProfileApi {
    private final CommunityProfileService communityProfileService;

    @PutMapping("/investor-type-public")
    @Operation(summary = "커뮤니티에 내 투자성향 이름을 보여줄지 정하기 ")
    public ApiResponseDTO<InvestorTypePublicResponseDTO> changeInvestorTypePublic(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @Valid @RequestBody InvestorTypePublicRequestDTO request) {
        return ApiResponseDTO.of(
                "성향 공개 설정 저장",
                new InvestorTypePublicResponseDTO(
                        communityProfileService.changeInvestorTypePublic(
                                principal.customerId(), request.investorTypePublic())));
    }
}
