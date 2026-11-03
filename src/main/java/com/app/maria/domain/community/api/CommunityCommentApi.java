package com.app.maria.domain.community.api;

import com.app.maria.domain.community.dto.request.CommentWriteRequestDTO;
import com.app.maria.domain.community.dto.response.CommentPageResponseDTO;
import com.app.maria.domain.community.dto.response.CommentResponseDTO;
import com.app.maria.domain.community.service.CommunityCommentService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 커뮤니티 댓글 API. 고객은 로그인한 본인뿐이고 요청에서 고객 ID를 받지 않는다. */
@RestController
@RequestMapping("/api/customer/community")
@RequiredArgsConstructor
public class CommunityCommentApi {
    private final CommunityCommentService commentService;

    @GetMapping("/posts/{postId}/comments")
    @Operation(summary = "글 안의 댓글 목록(오래된 순 커서)")
    public ApiResponseDTO<CommentPageResponseDTO> list(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive Long postId,
            @RequestParam(required = false) @Min(0) Long cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return ApiResponseDTO.of(
                "댓글 목록 조회 성공", commentService.list(principal.customerId(), postId, cursor, size));
    }

    @PostMapping("/posts/{postId}/comments")
    @Operation(summary = "댓글 쓰기 (CSRF 필수). replyToCommentId로 답하는 대상을 정하고, @끼루가 있으면 끼루를 부른다")
    public ApiResponseDTO<CommentResponseDTO> write(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive Long postId,
            @Valid @RequestBody CommentWriteRequestDTO request) {
        return ApiResponseDTO.of(
                "댓글 작성 완료", commentService.write(principal.customerId(), postId, request));
    }

    @PostMapping("/comments/{commentId}/kkiru/retry")
    @Operation(summary = "내 댓글의 끼루 호출 다시 묻기 (CSRF 필수). 상태가 FAILED인 본인 댓글만")
    public ApiResponseDTO<CommentResponseDTO> retryKkiru(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive Long commentId) {
        return ApiResponseDTO.of(
                "끼루에게 다시 물었습니다", commentService.retryKkiru(principal.customerId(), commentId));
    }

    @DeleteMapping("/comments/{commentId}")
    @Operation(summary = "본인 댓글 삭제 (CSRF 필수)")
    public ApiResponseDTO<Void> delete(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive Long commentId) {
        commentService.delete(principal.customerId(), commentId);
        return ApiResponseDTO.of("댓글 삭제 완료");
    }
}
