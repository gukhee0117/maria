package com.app.maria.domain.stockpackage.dto.request;

import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.stockpackage.type.CandidateSort;
import com.app.maria.domain.stockpackage.type.SortDirection;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;

/**
 * 후보 보기 조건. 모두 선택 사항이다. 위험등급은 1(매우 높은 위험)~6(매우 낮은 위험)이고 범위는 양 끝을 포함한다. 정렬을 주지 않으면 종목명 가나다순이다.
 * includeOutOfProfile이 false면 진단이 유효한 고객에게는 내 투자성향 범위 안의 상품만 보여준다.
 */
public record StockCandidateRequestDTO(
        @Size(max = 2) Set<Type> productTypes,
        @Size(max = 16) List<@NotBlank @Size(max = 30) String> themes,
        @Min(1) @Max(6) Integer minGrade,
        @Min(1) @Max(6) Integer maxGrade,
        @Size(max = 30) String keyword,
        boolean includeOutOfProfile,
        CandidateSort sort,
        SortDirection direction) {}
