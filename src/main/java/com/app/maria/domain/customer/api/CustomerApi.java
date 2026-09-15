package com.app.maria.domain.customer.api;

import com.app.maria.domain.customer.dto.response.CustomerSearchResponseDTO;
import com.app.maria.domain.customer.service.CustomerSearchService;
import com.app.maria.global.response.ApiResponseDTO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/customers")
@PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT', 'REVIEWER', 'VIEWER')")
public class CustomerApi {

    private final CustomerSearchService customerSearchService;

    @GetMapping("/search")
    public ResponseEntity<ApiResponseDTO<List<CustomerSearchResponseDTO>>> search(
            @RequestParam
                    @NotBlank(message = "고객 이름을 입력해 주세요.")
                    @Size(max = 50, message = "고객 이름은 50자 이하여야 합니다.")
                    String name) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "계좌 개설 가능 고객 검색", customerSearchService.searchEligibleCustomers(name)));
    }
}
