package com.app.maria.domain.news.api;

import com.app.maria.domain.news.dto.NewsContract.*;
import com.app.maria.domain.news.service.NewsBriefingOperations;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customer/news")
@RequiredArgsConstructor
public class CustomerNewsApi {
    private final NewsBriefingOperations service;

    @GetMapping("/briefing")
    public ResponseEntity<ApiResponseDTO<Briefing>> briefing(
            @AuthenticationPrincipal CustomerPrincipal user) {
        return response(service.briefing(user.customerId()));
    }

    @PostMapping("/articles/{id}/summary")
    public ResponseEntity<ApiResponseDTO<Answer>> summary(
            @AuthenticationPrincipal CustomerPrincipal user,
            @PathVariable String id,
            @Valid @RequestBody SummaryRequest request) {
        return response(service.summary(user.customerId(), id, request.articleVersion()));
    }

    @PostMapping("/articles/{id}/questions")
    public ResponseEntity<ApiResponseDTO<Answer>> question(
            @AuthenticationPrincipal CustomerPrincipal user,
            @PathVariable String id,
            @Valid @RequestBody QuestionRequest request) {
        return response(service.question(user.customerId(), id, request));
    }

    private <T> ResponseEntity<ApiResponseDTO<T>> response(T data) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponseDTO.of("조회 성공", data));
    }
}
