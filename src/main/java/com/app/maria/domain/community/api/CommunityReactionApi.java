package com.app.maria.domain.community.api;

import com.app.maria.domain.community.dto.response.LikeResponseDTO;
import com.app.maria.domain.community.service.CommunityReactionService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 글·댓글 좋아요. 켜기(PUT)와 끄기(DELETE)는 몇 번 보내도 결과가 같다. */
@RestController
@RequestMapping("/api/customer/community")
@RequiredArgsConstructor
public class CommunityReactionApi {
    private final CommunityReactionService reactionService;

    @PutMapping("/posts/{postId}/like")
    @Operation(summary = "글 좋아요 켜기 (CSRF 필수)")
    public ApiResponseDTO<LikeResponseDTO> likePost(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive Long postId) {
        return ApiResponseDTO.of(
                "좋아요 처리 완료", reactionService.likePost(principal.customerId(), postId));
    }

    @DeleteMapping("/posts/{postId}/like")
    @Operation(summary = "글 좋아요 끄기 (CSRF 필수)")
    public ApiResponseDTO<LikeResponseDTO> unlikePost(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive Long postId) {
        return ApiResponseDTO.of(
                "좋아요 취소", reactionService.unlikePost(principal.customerId(), postId));
    }

    @PutMapping("/comments/{commentId}/like")
    @Operation(summary = "댓글 좋아요 켜기 (CSRF 필수)")
    public ApiResponseDTO<LikeResponseDTO> likeComment(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive Long commentId) {
        return ApiResponseDTO.of(
                "좋아요 처리 완료", reactionService.likeComment(principal.customerId(), commentId));
    }

    @DeleteMapping("/comments/{commentId}/like")
    @Operation(summary = "댓글 좋아요 끄기 (CSRF 필수)")
    public ApiResponseDTO<LikeResponseDTO> unlikeComment(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive Long commentId) {
        return ApiResponseDTO.of(
                "좋아요 취소", reactionService.unlikeComment(principal.customerId(), commentId));
    }
}
