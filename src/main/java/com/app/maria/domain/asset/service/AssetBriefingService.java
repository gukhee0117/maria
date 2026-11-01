package com.app.maria.domain.asset.service;

import com.app.maria.domain.asset.dto.response.CustomerAssetBriefingResponseDTO;

public interface AssetBriefingService {
    CustomerAssetBriefingResponseDTO getBriefing(Long customerId);
}
