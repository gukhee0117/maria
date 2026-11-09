package com.app.maria.domain.tax.service;

import com.app.maria.domain.tax.dto.response.CustomerTaxBasisResponseDTO;
import com.app.maria.domain.tax.dto.response.CustomerTaxBriefingResponseDTO;
import com.app.maria.domain.tax.dto.response.TaxActionSummaryResponseDTO;
import com.app.maria.domain.tax.dto.response.TaxBatchHistoryResponseDTO;
import com.app.maria.domain.tax.dto.response.TaxCalculationPreviewResponseDTO;
import com.app.maria.domain.tax.dto.response.TaxCalculationSaveResponseDTO;
import com.app.maria.domain.tax.dto.response.TaxExpectedReliefResponseDTO;
import com.app.maria.domain.tax.dto.response.TaxSnapshotBatchResultResponseDTO;
import com.app.maria.domain.tax.dto.response.TaxSnapshotResponseDTO;
import java.util.List;

public interface TaxCalculationService {
    TaxCalculationPreviewResponseDTO taxCalculate(Long accountId);

    TaxCalculationSaveResponseDTO calculateAndSave(Long accountId);

    List<TaxSnapshotResponseDTO> findSnapshots(List<Long> accountIds);

    TaxSnapshotBatchResultResponseDTO triggerSnapshotBatch();

    List<TaxBatchHistoryResponseDTO> getRecentBatchHistory();

    TaxExpectedReliefResponseDTO previewExpectedRelief(Long accountId);

    TaxActionSummaryResponseDTO getActionSummary();

    CustomerTaxBriefingResponseDTO getCustomerTaxBriefing(Long customerId);

    /** 세액 계산 근거(구간별 값·5단계 중간값). 스냅샷이 아니라 지금 입력으로 다시 계산한다. */
    CustomerTaxBasisResponseDTO getCustomerTaxBasis(Long customerId);

    CustomerTaxBriefingResponseDTO.ExpectedTaxScenario getCustomerExpectedTaxBriefing(
            Long customerId);
}
