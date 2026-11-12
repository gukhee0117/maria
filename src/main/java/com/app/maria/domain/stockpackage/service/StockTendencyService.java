package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.stockpackage.dto.response.StockTendencyResponseDTO;

public interface StockTendencyService {
    /** 설문 투자성향(허용 위험등급 범위)을 돌려준다. 진단이 없거나 만료되면 available=false다. */
    StockTendencyResponseDTO getTendency(Long customerId);
}
