package com.app.maria.domain.targetproduct.service;

import com.app.maria.domain.externaltradesync.dto.response.MydataTradeResponseDTO;
import com.app.maria.domain.targetproduct.dto.TargetProductJudgementDTO;
import com.app.maria.domain.targetproduct.dto.TargetProductJudgementFailurePageDTO;
import com.app.maria.domain.targetproduct.dto.TargetProductJudgementPageDTO;
import com.app.maria.domain.targetproduct.dto.TargetProductSummaryDTO;
import com.app.maria.domain.targetproduct.dto.request.TargetProductSearchRequestDTO;

public interface TargetProductService {

    public TargetProductJudgementDTO judge(MydataTradeResponseDTO trade);

    TargetProductJudgementPageDTO getJudgements(TargetProductSearchRequestDTO request);

    TargetProductSummaryDTO getSummary();

    TargetProductJudgementFailurePageDTO getPermanentFailures(int page, int size);
}
