package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.stockpackage.dto.request.StockCandidateRequestDTO;
import com.app.maria.domain.stockpackage.dto.response.StockCandidateResponseDTO;
import java.util.List;

public interface StockCandidateService {
    /**
     * 조건에 맞는 상품을 중립 정렬로 돌려준다. RIA 계좌에서 살 수 없는 상품은 제외한다. 순위·점수·이유는 만들지 않는다. 위험등급이 아직 없는 상품은 보이되 담을 수
     * 없는 상품으로 표시한다.
     */
    StockCandidateResponseDTO search(Long customerId, StockCandidateRequestDTO request);

    /** 후보 조건으로 고를 수 있는 테마 이름. */
    List<String> themeNames();
}
