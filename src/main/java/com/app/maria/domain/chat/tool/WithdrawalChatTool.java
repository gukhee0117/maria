package com.app.maria.domain.chat.tool;

import com.app.maria.domain.withdrawal.dto.response.CustomerWithdrawalAvailabilityResponseDTO;
import com.app.maria.domain.withdrawal.service.CustomerWithdrawalService;
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
public class WithdrawalChatTool {

    private final CustomerWithdrawalService withdrawalService;

    @Tool(
            name = "withdrawal_schedule",
            description = "로그인한 고객의 인출 가능 금액, 묶인 원금, 납입 건별 1년 유지기간 만료일을 조회합니다.")
    public String getWithdrawalSchedule(ToolContext context) {
        Object raw = context.getContext().get("customerId");
        if (raw == null) {
            log.warn("[CHAT_TOOL] withdrawal_schedule: customerId 없음");
            return "조회 실패: 고객 정보를 확인할 수 없어요.";
        }
        Long customerId = ((Number) raw).longValue();
        try {
            NumberFormat fmt = NumberFormat.getIntegerInstance(Locale.KOREA);
            CustomerWithdrawalAvailabilityResponseDTO dto =
                    withdrawalService.getAvailability(customerId);

            StringBuilder sb = new StringBuilder();
            sb.append("세제혜택 불이익 없이 인출 가능: ")
                    .append(fmt.format(dto.getWithdrawableWithoutPenalty()))
                    .append("원");
            sb.append(" / 1년 미경과 묶인 원금: ")
                    .append(fmt.format(dto.getImmaturePrincipalAmount()))
                    .append("원");

            if (dto.getLeftAmounts() != null) {
                dto.getLeftAmounts().stream()
                        .filter(item -> !item.isMatured())
                        .forEach(
                                item ->
                                        sb.append(" / 1년 유지기간 만료 시각 ")
                                                .append(item.getMaturesAt())
                                                .append(" (해당 시각 이후부터 인출 가능, 납입분 ")
                                                .append(fmt.format(item.getCurAmount()))
                                                .append("원)"));
            }
            sb.append(" / 조회 기준: ").append(dto.getAsOf());
            return sb.toString();
        } catch (Exception e) {
            log.error("[CHAT_TOOL] withdrawal_schedule 조회 실패 customerId={}", customerId, e);
            return "조회 실패: 잠시 후 다시 시도해 주세요.";
        }
    }
}
