package com.app.maria.domain.chat.tool;

import com.app.maria.domain.tax.dto.response.CustomerTaxBriefingResponseDTO;
import com.app.maria.domain.tax.service.TaxCalculationService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.text.NumberFormat;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TaxChatTool {

    private final TaxCalculationService taxCalculationService;

    @Tool(
            name = "tax_estimate",
            description =
                    "로그인한 고객의 RIA 예상 감면세액과 예상 납부 세액을 조회합니다. 가장 최근 세액 스냅샷 기반 예상값이며, 확정신고 결과가 아닙니다.")
    public String getTaxEstimate(ToolContext context) {
        Object raw = context.getContext().get("customerId");
        if (raw == null) {
            log.warn("[CHAT_TOOL] tax_estimate: customerId 없음");
            return "조회 실패: 고객 정보를 확인할 수 없어요.";
        }
        Long customerId = ((Number) raw).longValue();
        try {
            NumberFormat fmt = NumberFormat.getIntegerInstance(Locale.KOREA);
            CustomerTaxBriefingResponseDTO dto =
                    taxCalculationService.getCustomerTaxBriefing(customerId);
            CustomerTaxBriefingResponseDTO.TaxScenario current = dto.getCurrent();
            return "예상 감면세액: "
                    + fmt.format(current.getReliefAmount())
                    + "원"
                    + " / 예상 납부 세액: "
                    + fmt.format(current.getRiaTax())
                    + "원"
                    + " / 계산 기준: "
                    + dto.getCalculatedAt().toLocalDate();
        } catch (AppException e) {
            if (e.getErrorType() == ErrorType.TAX_SNAPSHOT_NOT_FOUND) {
                return "아직 예상 세액이 계산되지 않았어요. 세액예상 페이지에서 먼저 조회해 보세요.";
            }
            if (e.getErrorType() == ErrorType.ACCOUNT_NOT_FOUND) {
                return "조회 실패: 계좌를 찾을 수 없어요.";
            }
            log.error("[CHAT_TOOL] tax_estimate 조회 실패 customerId={}", customerId, e);
            return "조회 실패: 잠시 후 다시 시도해 주세요.";
        } catch (Exception e) {
            log.error("[CHAT_TOOL] tax_estimate 조회 실패 customerId={}", customerId, e);
            return "조회 실패: 잠시 후 다시 시도해 주세요.";
        }
    }
}
