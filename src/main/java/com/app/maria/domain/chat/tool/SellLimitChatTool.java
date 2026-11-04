package com.app.maria.domain.chat.tool;

import com.app.maria.domain.sellorder.dto.response.SellLimitStatusDTO;
import com.app.maria.domain.sellorder.service.SellLimitService;
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
public class SellLimitChatTool {

    private final SellLimitService sellLimitService;

    @Tool(
            name = "sell_limit",
            description = "로그인한 고객의 RIA 계좌 남은 매도 한도를 조회합니다. 타사 RIA 누적 매도액과 5천만원 상한을 모두 반영한 값입니다.")
    public String getSellLimit(ToolContext context) {
        Object raw = context.getContext().get("customerId");
        if (raw == null) {
            log.warn("[CHAT_TOOL] sell_limit: customerId 없음");
            return "조회 실패: 고객 정보를 확인할 수 없어요.";
        }
        Long customerId = ((Number) raw).longValue();
        try {
            NumberFormat fmt = NumberFormat.getIntegerInstance(Locale.KOREA);
            SellLimitStatusDTO dto = sellLimitService.getStatusByCustomerId(customerId);
            return "계좌 설정 한도: "
                    + fmt.format(dto.limitAmount())
                    + "원"
                    + " / 사용 금액: "
                    + fmt.format(dto.usedAmount())
                    + "원"
                    + " / 남은 한도: "
                    + fmt.format(dto.remainingLimit())
                    + "원";
        } catch (Exception e) {
            log.error("[CHAT_TOOL] sell_limit 조회 실패 customerId={}", customerId, e);
            return "조회 실패: 잠시 후 다시 시도해 주세요.";
        }
    }
}
