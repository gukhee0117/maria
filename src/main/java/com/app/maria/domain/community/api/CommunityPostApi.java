package com.app.maria.domain.community.api;

import com.app.maria.domain.community.dto.request.PostUpdateRequestDTO;
import com.app.maria.domain.community.dto.request.PostWriteRequestDTO;
import com.app.maria.domain.community.dto.response.PostPageResponseDTO;
import com.app.maria.domain.community.dto.response.PostResponseDTO;
import com.app.maria.domain.community.dto.response.TickerRankResponseDTO;
import com.app.maria.domain.community.dto.response.TickerResponseDTO;
import com.app.maria.domain.community.service.CommunityPostService;
import com.app.maria.domain.community.service.CommunityTickerService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 커뮤니티 글·종목 API. 고객은 로그인한 본인뿐이고 요청에서 고객 ID를 받지 않는다. */
@RestController
@RequestMapping("/api/customer/community")
@RequiredArgsConstructor
public class CommunityPostApi {
    private final CommunityPostService postService;
    private final CommunityTickerService tickerService;

    @GetMapping("/tickers")
    @Operation(summary = "종목 드롭박스(국내+해외)")
    public ApiResponseDTO<List<TickerResponseDTO>> tickers() {
        return ApiResponseDTO.of("종목 목록 조회 성공", tickerService.list());
    }

    @GetMapping("/tickers/ranking")
    @Operation(summary = "오늘 글이 많이 달린 종목 순위")
    public ApiResponseDTO<List<TickerRankResponseDTO>> tickerRanking(
            @RequestParam(defaultValue = "12") @Min(1) @Max(30) int size) {
        return ApiResponseDTO.of("종목 순위 조회 성공", tickerService.ranking(size));
    }

    @PostMapping("/posts")
    @Operation(summary = "글 쓰기 (CSRF 필수). 본문에 @끼루가 있으면 끼루를 부른다")
    public ApiResponseDTO<PostResponseDTO> write(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @Valid @RequestBody PostWriteRequestDTO request) {
        return ApiResponseDTO.of("글 작성 완료", postService.write(principal.customerId(), request));
    }

    @PostMapping("/posts/{postId}/kkiru/retry")
    @Operation(summary = "내 글의 끼루 호출 다시 묻기 (CSRF 필수). 상태가 FAILED인 본인 글만")
    public ApiResponseDTO<PostResponseDTO> retryKkiru(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive Long postId) {
        return ApiResponseDTO.of(
                "끼루에게 다시 물었습니다", postService.retryKkiru(principal.customerId(), postId));
    }

    @GetMapping("/posts")
    @Operation(summary = "글 목록(최신순 커서). ticker 비움=전체, GENERAL=종목 없는 글")
    public ApiResponseDTO<PostPageResponseDTO> list(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @RequestParam(required = false) @Size(max = 20) String ticker,
            @RequestParam(name = "q", required = false) @Size(max = 100) String keyword,
            @RequestParam(required = false) @Positive Long cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return ApiResponseDTO.of(
                "글 목록 조회 성공",
                postService.list(principal.customerId(), ticker, keyword, cursor, size));
    }

    @GetMapping("/posts/popular")
    @Operation(summary = "인기글: 최근 7일 글 중 댓글 많은 순. ticker 비움=전체 종목")
    public ApiResponseDTO<PostPageResponseDTO> popular(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @RequestParam(required = false) @Size(max = 20) String ticker,
            @RequestParam(defaultValue = "10") @Min(1) @Max(30) int size) {
        return ApiResponseDTO.of(
                "인기글 조회 성공", postService.popular(principal.customerId(), ticker, size));
    }

    @GetMapping("/posts/{postId}")
    @Operation(summary = "글 한 건")
    public ApiResponseDTO<PostResponseDTO> detail(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive Long postId) {
        return ApiResponseDTO.of("글 조회 성공", postService.detail(principal.customerId(), postId));
    }

    @PutMapping("/posts/{postId}")
    @Operation(summary = "본인 글 수정 (CSRF 필수). 끼루가 답했거나 답하는 중이면 거부")
    public ApiResponseDTO<PostResponseDTO> edit(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive Long postId,
            @Valid @RequestBody PostUpdateRequestDTO request) {
        return ApiResponseDTO.of(
                "글 수정 완료", postService.edit(principal.customerId(), postId, request));
    }

    @DeleteMapping("/posts/{postId}")
    @Operation(summary = "본인 글 삭제 (CSRF 필수)")
    public ApiResponseDTO<Void> delete(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive Long postId) {
        postService.delete(principal.customerId(), postId);
        return ApiResponseDTO.of("글 삭제 완료");
    }
}
