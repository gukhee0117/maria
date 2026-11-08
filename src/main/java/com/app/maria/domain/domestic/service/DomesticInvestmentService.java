package com.app.maria.domain.domestic.service;

import com.app.maria.domain.domestic.dto.DomesticAccountDetailDTO;
import com.app.maria.domain.domestic.dto.DomesticInvestmentPageDTO;
import com.app.maria.domain.domestic.dto.request.DomesticInvestmentSearchRequestDTO;
import com.app.maria.domain.domestic.dto.response.*;
import java.util.List;

public interface DomesticInvestmentService {
    DomesticInvestmentPageDTO getInvestments(DomesticInvestmentSearchRequestDTO request);

    DomesticAccountDetailDTO getAccountDetail(Long accountId);

    DomesticInvestmentSummaryResponseDTO getSummary(int days);

    List<DomesticRestrictedHoldingResponseDTO> getRestrictedHoldings();

    DomesticCashHeavyPageResponseDTO getCashHeavyAccounts(int page, int size);

    DomesticCashHeavyPageResponseDTO getCashHeavyAccounts(
            int page, int size, String sort, String direction);

    List<DomesticUnpurchasableHoldingResponseDTO> getUnpurchasableHoldings();

    List<DomesticAccountLiteResponseDTO> getRecentBuyAccounts(int days, boolean hasRecentBuy);
}
