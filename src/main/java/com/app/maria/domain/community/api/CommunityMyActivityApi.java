package com.app.maria.domain.community.api;

import com.app.maria.domain.community.dto.response.CommentPageResponseDTO;
import com.app.maria.domain.community.dto.response.MySummaryResponseDTO;
import com.app.maria.domain.community.dto.response.PostPageResponseDTO;
import com.app.maria.domain.community.service.CommunityMyActivityService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 내가 쓴 글·댓글과 프로필 카드. ticker를 주면 그 종목만, 비우면 전 종목이다. */
@RestController
@RequestMapping("/api/customer/community/me")
@RequiredArgsConstructor
public class CommunityMyActivityApi {
    private final CommunityMyActivityService myActivityService;

    @GetMapping("/posts")
    @Operation(summary = "내가 쓴 글(최신순 커서)")
    public ApiResponseDTO<PostPageResponseDTO> myPosts(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @RequestParam(required = false) @Size(max = 20) String ticker,
            @RequestParam(required = false) @Positive Long cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return ApiResponseDTO.of(
                "내가 쓴 글 조회 성공",
                myActivityService.myPosts(principal.customerId(), ticker, cursor, size));
    }

    @GetMapping("/comments")
    @Operation(summary = "내가 쓴 댓글(최신순 커서)")
    public ApiResponseDTO<CommentPageResponseDTO> myComments(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @RequestParam(required = false) @Size(max = 20) String ticker,
            @RequestParam(required = false) @Positive Long cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return ApiResponseDTO.of(
                "내가 쓴 댓글 조회 성공",
                myActivityService.myComments(principal.customerId(), ticker, cursor, size));
    }

    @GetMapping("/summary")
    @Operation(summary = "프로필 카드(닉네임, 글·댓글 수, 성향 공개 여부)")
    public ApiResponseDTO<MySummaryResponseDTO> summary(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ApiResponseDTO.of("프로필 조회 성공", myActivityService.summary(principal.customerId()));
    }
}
