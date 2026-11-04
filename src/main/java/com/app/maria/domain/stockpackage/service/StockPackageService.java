package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.stockpackage.dto.request.StockPackageSaveRequestDTO;
import com.app.maria.domain.stockpackage.dto.response.StockPackageResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockPackageSummaryResponseDTO;
import java.util.List;

public interface StockPackageService {
    /** 패키지를 만든다. 종목이 있으면 비중 합 100, 담을 수 있는 종목, 성향 밖 종목 확인을 모두 검증한다. */
    StockPackageResponseDTO create(Long customerId, StockPackageSaveRequestDTO request);

    List<StockPackageSummaryResponseDTO> list(Long customerId);

    /** 상세 조회. 종목별 위험등급과 내 성향 범위 밖 여부를 함께 준다. */
    StockPackageResponseDTO get(Long customerId, Long stockPackageId);

    /** 이름·총 투자금액·구성 종목을 요청 내용으로 통째로 바꾼다. */
    StockPackageResponseDTO update(
            Long customerId, Long stockPackageId, StockPackageSaveRequestDTO request);

    /** 패키지를 지운다. 이 패키지로 낸 주문이 있으면 지울 수 없다. */
    void delete(Long customerId, Long stockPackageId);
}
