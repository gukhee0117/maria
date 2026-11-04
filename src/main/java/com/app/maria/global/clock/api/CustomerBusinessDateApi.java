package com.app.maria.global.clock.api;

import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.response.ApiResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 고객 화면이 "오늘"로 쓰는 업무일. 로그인 전 화면(랜딩)도 쓰므로 인증 없이 읽을 수 있고, 날짜만 돌려준다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/customer/business-date")
public class CustomerBusinessDateApi {

    private final BusinessClockService businessClockService;

    @GetMapping
    public ResponseEntity<ApiResponseDTO<String>> getBusinessDate() {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "업무일 조회 성공", businessClockService.now().toLocalDate().toString()));
    }
}
