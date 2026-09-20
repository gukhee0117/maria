package com.app.maria.domain.foreignproduct.api;

import com.app.maria.domain.foreignproduct.dto.response.ForeignProductResponseDTO;
import com.app.maria.domain.foreignproduct.service.ForeignProductService;
import com.app.maria.global.response.ApiResponseDTO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/foreign-products")
public class ForeignProductApi {

    private final ForeignProductService foreignProductService;

    @GetMapping
    public ResponseEntity<ApiResponseDTO<List<ForeignProductResponseDTO>>> getAllForeignProducts() {
        List<ForeignProductResponseDTO> result = foreignProductService.getAllForeignProducts();
        return ResponseEntity.ok(ApiResponseDTO.of("종목 전체 조회 성공", result));
    }

    @GetMapping("/{foreignProductId}")
    public ResponseEntity<ApiResponseDTO<ForeignProductResponseDTO>> getForeignProduct(
            @PathVariable Long foreignProductId) {
        ForeignProductResponseDTO result =
                foreignProductService.getForeignProduct(foreignProductId);
        return ResponseEntity.ok(ApiResponseDTO.of("종목 단건 조회 성공", result));
    }
}
