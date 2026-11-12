package com.app.maria.domain.chat.service;

import com.app.maria.domain.chat.dto.request.ChatRequestDTO;
import com.app.maria.domain.chat.dto.request.MessageDTO;
import com.app.maria.domain.chat.tool.AccountChatTool;
import com.app.maria.domain.chat.tool.SellLimitChatTool;
import com.app.maria.domain.chat.tool.TaxChatTool;
import com.app.maria.domain.chat.tool.WithdrawalChatTool;
import com.app.maria.domain.settlement.component.SettlementBusinessDayCalculator;
import com.app.maria.domain.tax.dto.TaxRuleDTO;
import com.app.maria.domain.tax.mapper.TaxMapper;
import com.app.maria.domain.tax.service.TaxCalculations;
import com.app.maria.domain.tax.type.TaxRuleType;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class KkiruChatServiceImpl implements KkiruChatService {

    private static final Logger log = LoggerFactory.getLogger(KkiruChatServiceImpl.class);

    private final ChatClient chatClient;
    private final BusinessClockService businessClockService;
    private final TaxMapper taxMapper;
    private final SettlementBusinessDayCalculator settlementBusinessDayCalculator;
    private final AccountChatTool accountChatTool;
    private final WithdrawalChatTool withdrawalChatTool;
    private final SellLimitChatTool sellLimitChatTool;
    private final TaxChatTool taxChatTool;

    public KkiruChatServiceImpl(
            @Qualifier("kkiruChatClient") ChatClient chatClient,
            BusinessClockService businessClockService,
            TaxMapper taxMapper,
            SettlementBusinessDayCalculator settlementBusinessDayCalculator,
            AccountChatTool accountChatTool,
            WithdrawalChatTool withdrawalChatTool,
            SellLimitChatTool sellLimitChatTool,
            TaxChatTool taxChatTool) {
        this.chatClient = chatClient;
        this.businessClockService = businessClockService;
        this.taxMapper = taxMapper;
        this.settlementBusinessDayCalculator = settlementBusinessDayCalculator;
        this.accountChatTool = accountChatTool;
        this.withdrawalChatTool = withdrawalChatTool;
        this.sellLimitChatTool = sellLimitChatTool;
        this.taxChatTool = taxChatTool;
    }

    @Override
    public Flux<String> stream(ChatRequestDTO request, Long customerId) {
        LocalDate today = businessClockService.now().toLocalDate();
        String bandLabel = currentBandLabel(today);
        List<Message> messages = new ArrayList<>();
        messages.add(
                new SystemMessage("오늘 날짜: " + today + ", 지금 매도 시 결제일 기준 공제율 구간: " + bandLabel));
        request.getMessages().stream().map(this::toMessage).forEach(messages::add);

        return chatClient
                .prompt()
                .messages(messages)
                .user(request.getQuestion())
                .tools(accountChatTool, withdrawalChatTool, sellLimitChatTool, taxChatTool)
                .toolContext(buildCtx(customerId))
                .stream()
                .content();
    }

    /** T+2 영업일(공휴일 포함) 기준 현재 공제율 구간 문자열 반환. 예: "8~12월 50%" */
    private String currentBandLabel(LocalDate today) {
        try {
            LocalDate settlement =
                    settlementBusinessDayCalculator
                            .calculateFinalAt(today.atStartOfDay())
                            .toLocalDate();
            List<TaxRuleDTO> rules = taxMapper.selectTaxRules();
            return rules.stream()
                    .filter(r -> r.getRuleType() == TaxRuleType.RELIEF_RATE)
                    .filter(
                            r ->
                                    !settlement.isBefore(r.getValidFrom())
                                            && !settlement.isAfter(r.getValidTo()))
                    .findFirst()
                    .map(
                            r -> {
                                BigDecimal weight = TaxCalculations.weightOf(r.getRuleValue());
                                if (weight.compareTo(BigDecimal.ZERO) == 0) {
                                    return "2026년 RIA 가입 기간이 종료되어 공제율이 적용되지 않음";
                                }
                                String pct =
                                        weight.multiply(BigDecimal.valueOf(100))
                                                .stripTrailingZeros()
                                                .toPlainString();
                                return r.getValidFrom().getMonthValue()
                                        + "~"
                                        + r.getValidTo().getMonthValue()
                                        + "월 "
                                        + pct
                                        + "%";
                            })
                    .orElse("구간 정보 없음");
        } catch (Exception e) {
            log.warn("공제율 구간 조회 실패", e);
            return "구간 정보 없음";
        }
    }

    /** Map.of()는 null 값을 허용하지 않으므로 HashMap 사용 (비로그인 시 customerId = null). */
    private static Map<String, Object> buildCtx(Long customerId) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("customerId", customerId);
        return ctx;
    }

    private Message toMessage(MessageDTO dto) {
        return "user".equals(dto.getRole())
                ? new UserMessage(dto.getContent())
                : new AssistantMessage(dto.getContent());
    }
}
