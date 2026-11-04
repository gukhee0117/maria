package com.app.maria.domain.stockpackage.api;

import com.app.maria.domain.stockpackage.dto.request.StockCandidateRequestDTO;
import com.app.maria.domain.stockpackage.dto.request.StockConditionParseRequestDTO;
import com.app.maria.domain.stockpackage.dto.response.StockCandidateResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockConditionParseResponseDTO;
import com.app.maria.domain.stockpackage.service.StockCandidateService;
import com.app.maria.domain.stockpackage.service.StockConditionService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 후보 보기. 조건으로 거른 상품을 중립 정렬로 보여주고, 순위·점수·이유는 만들지 않는다. */
@RestController
@RequestMapping("/api/customer/stock-packages/candidates")
@RequiredArgsConstructor
public class StockCandidateApi {
    private final StockCandidateService stockCandidateService;
    private final StockConditionService stockConditionService;

    @PostMapping
    @Operation(
            summary =
                    "후보 보기 (CSRF 필수). 상품군·테마·위험등급 범위·검색어로 거르고 가나다순(기본)·위험등급순·변동성순으로 보여준다. RIA 계좌에서 살 수 없는 상품은 제외한다")
    public ApiResponseDTO<StockCandidateResponseDTO> search(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @Valid @RequestBody StockCandidateRequestDTO request) {
        return ApiResponseDTO.of(
                "후보 조회 성공", stockCandidateService.search(principal.customerId(), request));
    }

    @GetMapping("/themes")
    @Operation(summary = "후보 조건으로 고를 수 있는 테마 이름")
    public ApiResponseDTO<List<String>> themes() {
        return ApiResponseDTO.of("테마 조회 성공", stockCandidateService.themeNames());
    }

    @PostMapping("/parse")
    @Operation(
            summary =
                    "말로 조건 찾기 (CSRF 필수). 60자까지의 말을 후보 보기 조건으로 바꾼다. AI는 조건 값만 뽑고 고객에게 보이는 문장은 검증된 값으로 만든다. 투자 판단·추천을 요구하는 말은 조건으로 바꾸지 않고 안내만 준다")
    public ApiResponseDTO<StockConditionParseResponseDTO> parse(
            @Valid @RequestBody StockConditionParseRequestDTO request) {
        return ApiResponseDTO.of("조건 변환 완료", stockConditionService.parse(request));
    }
}
