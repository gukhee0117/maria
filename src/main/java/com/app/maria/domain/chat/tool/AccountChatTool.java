package com.app.maria.domain.chat.tool;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
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
public class AccountChatTool {

    private final AccountMapper accountMapper;

    @Tool(name = "account_summary", description = "로그인한 고객의 RIA 계좌 예수금·상태·혜택 여부를 조회합니다.")
    public String getAccountSummary(ToolContext context) {
        Object raw = context.getContext().get("customerId");
        if (raw == null) {
            log.warn("[CHAT_TOOL] customerId 없음 — 비로그인 또는 ToolContext 미전달");
            return "조회 실패: 고객 정보를 확인할 수 없어요.";
        }
        Long customerId = ((Number) raw).longValue();
        try {
            NumberFormat KRW = NumberFormat.getIntegerInstance(Locale.KOREA);
            return accountMapper
                    .selectByCustomerId(customerId)
                    .map(
                            account ->
                                    "예수금: "
                                            + KRW.format(account.getAmount())
                                            + "원"
                                            + " / 계좌상태: "
                                            + account.getStatus()
                                            + " / 세제혜택: "
                                            + benefitLabel(account))
                    .orElse("조회 실패: 계좌를 찾을 수 없어요.");
        } catch (Exception e) {
            log.error("[CHAT_TOOL] 계좌 조회 실패 customerId={}", customerId, e);
            return "조회 실패: 잠시 후 다시 시도해 주세요.";
        }
    }

    private String benefitLabel(AccountDTO account) {
        if (account.getBenefit() == null) return "미분류";
        return account.getBenefit().name();
    }
}
