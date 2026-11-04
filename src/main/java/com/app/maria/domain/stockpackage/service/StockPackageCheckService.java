package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.stockpackage.dto.request.StockPackageCheckRequestDTO;
import com.app.maria.domain.stockpackage.dto.response.StockPackageCheckResponseDTO;

public interface StockPackageCheckService {
    /**
     * 패키지 구성(종목·비중, 합 100)을 점검한다. 저장하지 않는다. 위험등급·집중도는 코드가 계산하고 연 변동성은 risk에 요청한다. risk를 쓸 수 없으면
     * 변동성만 계산 불가로 두고 나머지는 그대로 돌려준다.
     */
    StockPackageCheckResponseDTO check(Long customerId, StockPackageCheckRequestDTO request);
}
