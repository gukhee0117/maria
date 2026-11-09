package com.app.maria.domain.asset.service;

import com.app.maria.domain.asset.dto.response.CustomerAssetHoldingResponseDTO;
import java.util.List;

public interface CustomerAssetHoldingsService {

    List<CustomerAssetHoldingResponseDTO> getHoldings(Long customerId);
}
