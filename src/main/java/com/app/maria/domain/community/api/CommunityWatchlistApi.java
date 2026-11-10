package com.app.maria.domain.community.api;

import com.app.maria.domain.community.service.CommunityWatchlistService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/customer/community/watchlist")
public class CommunityWatchlistApi {
    private final CommunityWatchlistService service;

    @GetMapping
    @Operation(summary = "내 관심종목 코드 목록")
    public ApiResponseDTO<List<String>> list(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ApiResponseDTO.of("관심종목 조회 성공", service.list(principal.customerId()));
    }

    @PutMapping("/{ticker}")
    @Operation(summary = "관심종목 추가 (중복 요청은 기존 등록 유지)")
    public ApiResponseDTO<Void> add(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable String ticker) {
        service.add(principal.customerId(), ticker);
        return ApiResponseDTO.of("관심종목 추가 성공");
    }

    @DeleteMapping("/{ticker}")
    @Operation(summary = "관심종목 삭제 (없는 항목도 성공)")
    public ApiResponseDTO<Void> remove(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable String ticker) {
        service.remove(principal.customerId(), ticker);
        return ApiResponseDTO.of("관심종목 삭제 성공");
    }
}
