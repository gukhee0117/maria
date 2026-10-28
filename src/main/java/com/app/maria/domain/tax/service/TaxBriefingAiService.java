package com.app.maria.domain.tax.service;

import com.app.maria.domain.tax.dto.response.CustomerTaxBriefingResponseDTO;

public interface TaxBriefingAiService {

    String generateExplanation(CustomerTaxBriefingResponseDTO briefing);
}
