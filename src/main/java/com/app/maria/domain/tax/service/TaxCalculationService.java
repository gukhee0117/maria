package com.app.maria.domain.tax.service;

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
}
